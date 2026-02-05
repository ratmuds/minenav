package org.ratmuds.minenav.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * A stateful 3D Anytime D* (AD*) implementation.
 * Allows for incremental updates and refinement of the path.
 */
public class AnytimeDStar3D {
    private static final double INF = Double.POSITIVE_INFINITY;

    private static final int[][] DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 0, 1}, {0, 0, -1},
            {0, 1, 0}, {0, -1, 0}
    };

    private final double[] costs;
    private final int maxX, maxY, maxZ;
    private final int startX, startY, startZ;
    private final int endX, endY, endZ;
    private final int startIdx, goalIdx;

    private final double[] g;
    private final double[] rhs;
    private final byte[] tag; // 0 = NEW, 1 = OPEN, 2 = CLOSED, 3 = INCONS
    private final long[] stamp;

    private final PriorityQueue<Entry> open;
    private final IntList closedList;
    private final IntList inconsList;

    private double epsilon;
    private boolean done = false;
    private boolean pathFound = false;

    public AnytimeDStar3D(double[] costs,
                          int maxX, int maxY, int maxZ,
                          int startX, int startY, int startZ,
                          int endX, int endY, int endZ) {
        this.costs = costs;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.startX = startX;
        this.startY = startY;
        this.startZ = startZ;
        this.endX = endX;
        this.endY = endY;
        this.endZ = endZ;

        this.epsilon = 2.5;

        int n = maxX * maxY * maxZ;
        this.startIdx = index(maxY, maxZ, startX, startY, startZ);
        this.goalIdx = index(maxY, maxZ, endX, endY, endZ);

        this.g = new double[n];
        this.rhs = new double[n];
        Arrays.fill(g, INF);
        Arrays.fill(rhs, INF);

        this.tag = new byte[n];
        this.stamp = new long[n];

        this.open = new PriorityQueue<>();
        this.closedList = new IntList(4096);
        this.inconsList = new IntList(1024);

        // Validation
        if (costs == null || maxX <= 0 || maxY <= 1 || maxZ <= 0 ||
            !isValid(startX, startY, startZ) || !isValid(endX, endY, endZ) ||
            !isWalkable(startX, startY, startZ) || !isWalkable(endX, endY, endZ)) {
            this.done = true;
            return;
        }

        if (startIdx == goalIdx) {
            this.pathFound = true;
            this.done = true;
            return;
        }

        // Initialize Goal
        rhs[goalIdx] = 0.0;
        putOpen(goalIdx, key(goalIdx));
    }

    private boolean isValid(int x, int y, int z) {
        return x >= 0 && x < maxX && y >= 0 && y < maxY && z >= 0 && z < maxZ;
    }

    public boolean isDone() {
        return done;
    }

    public boolean hasPath() {
        return pathFound || g[startIdx] < INF;
    }

    public double getEpsilon() {
        return epsilon;
    }

    /**
     * Runs the algorithm for a limited number of expansions.
     * @param maxExpansions Budget for this iteration.
     * @return Number of nodes expanded.
     */
    public int iterate(int maxExpansions) {
        if (done) return 0;

        int expanded = computeOrImprovePath(maxExpansions);

        // Check if we satisfied the current epsilon
        if (isEpsilonSatisfied()) {
            if (epsilon <= 1.0) {
                done = true;
            } else {
                // Decrease epsilon and prepare for next phase
                epsilon = Math.max(1.0, epsilon - 0.5);
                rekeyForEpsilon();
            }
        }

        if (g[startIdx] < INF) {
            pathFound = true;
        }

        return expanded;
    }

    private boolean isEpsilonSatisfied() {
        if (open.isEmpty() && inconsList.size == 0) return true;
        if (rhs[startIdx] != g[startIdx]) return false;
        
        Key top = peekValidKey();
        if (top == null) return true;
        
        Key startKey = key(startIdx);
        return compareKeys(top, startKey) >= 0;
    }

    public List<int[]> extractPath() {
        if (!hasPath()) return null;
        if (startIdx == goalIdx) {
            List<int[]> p = new ArrayList<>();
            p.add(new int[]{startX, startY, startZ});
            return p;
        }
        
        List<int[]> path = new ArrayList<>();
        int current = startIdx;
        int steps = 0;
        int maxSteps = maxX * maxY * maxZ;

        while (true) {
            int xyStride = maxY * maxZ;
            int x = current / xyStride;
            int rem = current - (x * xyStride);
            int y = rem / maxZ;
            int z = rem - (y * maxZ);

            path.add(new int[]{x, y, z});
            if (current == goalIdx) break;
            if (steps++ > maxSteps) return null; // Cycle detected

            double best = INF;
            int bestIdx = -1;
            for (int[] dir : DIRECTIONS) {
                int nx = x + dir[0];
                int ny = y + dir[1];
                int nz = z + dir[2];
                if (!isValid(nx, ny, nz)) continue;
                if (!isWalkable(nx, ny, nz)) continue;
                int nIdx = index(maxY, maxZ, nx, ny, nz);
                if (Double.isInfinite(g[nIdx])) continue;
                double cand = costs[nIdx] + g[nIdx];
                if (cand < best) {
                    best = cand;
                    bestIdx = nIdx;
                }
            }

            if (bestIdx < 0) return null;
            current = bestIdx;
        }
        return path;
    }

    private int computeOrImprovePath(int maxExpansions) {
        int expanded = 0;
        while (expanded < maxExpansions) {
            Key startKey = key(startIdx);
            Key topKey = peekValidKey();
            if (topKey == null) break;

            if (compareKeys(topKey, startKey) >= 0 && rhs[startIdx] == g[startIdx]) {
                break; // Satisfied
            }

            Entry e = pollValid();
            if (e == null) break;
            int s = e.idx;
            expanded++;

            if (g[s] > rhs[s]) {
                g[s] = rhs[s];
                if (tag[s] != 2) {
                    tag[s] = 2;
                    closedList.add(s);
                }
                forEachNeighbor(s, (sp) -> updateState(sp));
            } else {
                g[s] = INF;
                updateState(s);
                forEachNeighbor(s, (sp) -> updateState(sp));
            }
        }
        return expanded;
    }

    private void updateState(int s) {
        int xyStride = maxY * maxZ;
        int x = s / xyStride;
        int rem = s - (x * xyStride);
        int y = rem / maxZ;
        int z = rem - (y * maxZ);

        if (!isWalkable(x, y, z)) {
            g[s] = INF;
            rhs[s] = INF;
            removeFromOpen(s);
            if (tag[s] == 3) tag[s] = 0;
            return;
        }

        if (tag[s] != 2 && tag[s] != 3) {
            g[s] = INF;
        }

        if (s != goalIdx) {
            double best = INF;
            for (int[] dir : DIRECTIONS) {
                int nx = x + dir[0];
                int ny = y + dir[1];
                int nz = z + dir[2];
                if (!isValid(nx, ny, nz)) continue;
                if (!isWalkable(nx, ny, nz)) continue;
                int sp = index(maxY, maxZ, nx, ny, nz);
                double cand = costs[sp] + g[sp];
                if (cand < best) best = cand;
            }
            rhs[s] = best;
        }

        removeFromOpen(s);

        if (g[s] != rhs[s]) {
            if (tag[s] != 2 && tag[s] != 3) {
                putOpen(s, key(s));
            } else {
                if (tag[s] != 3) {
                    tag[s] = 3;
                    inconsList.add(s);
                }
            }
        } else {
            if (tag[s] == 3) {
                tag[s] = 2;
            }
        }
    }

    private void rekeyForEpsilon() {
        for (int i = 0; i < inconsList.size; i++) {
            int s = inconsList.data[i];
            if (tag[s] != 3) continue;
            putOpen(s, key(s));
        }
        inconsList.clear();

        for (int i = 0; i < closedList.size; i++) {
            int s = closedList.data[i];
            if (tag[s] == 2) tag[s] = 0;
            else if (tag[s] == 3) tag[s] = 0;
        }
        closedList.clear();

        if (open.isEmpty()) return;
        Entry[] snapshot = open.toArray(new Entry[0]);
        open.clear(); // Rebuild heap
        for (Entry e : snapshot) {
            int s = e.idx;
            if (stamp[s] != e.stamp) continue; // Skip invalid
            if (tag[s] != 1) continue;
            putOpen(s, key(s));
        }
    }

    private Key key(int sIdx) {
        double gs = g[sIdx];
        double rs = rhs[sIdx];
        double h = heuristic(sIdx);
        if (gs > rs) {
            return new Key(rs + epsilon * h, rs);
        }
        return new Key(gs + h, gs);
    }

    private double heuristic(int idx) {
        int xyStride = maxY * maxZ;
        int x = idx / xyStride;
        int rem = idx - (x * xyStride);
        int y = rem / maxZ;
        int z = rem - (y * maxZ);
        return Math.abs(x - startX) + Math.abs(y - startY) + Math.abs(z - startZ);
    }

    private Key peekValidKey() {
        while (true) {
            Entry top = open.peek();
            if (top == null) return null;
            if (top.stamp != stamp[top.idx]) {
                open.poll();
                continue;
            }
            return new Key(top.k1, top.k2);
        }
    }

    private Entry pollValid() {
        while (true) {
            Entry top = open.poll();
            if (top == null) return null;
            if (top.stamp != stamp[top.idx]) continue;
            return top;
        }
    }

    private void putOpen(int idx, Key key) {
        tag[idx] = 1;
        long s = ++stamp[idx];
        open.add(new Entry(idx, key.k1, key.k2, s));
    }

    private void removeFromOpen(int idx) {
        if (tag[idx] != 1) return;
        tag[idx] = 0;
        stamp[idx]++;
    }

    private boolean isWalkable(int x, int y, int z) {
        int i = index(maxY, maxZ, x, y, z);
        if (costs[i] == INF) return false;
        if (y + 1 >= maxY) return false;
        return costs[index(maxY, maxZ, x, y + 1, z)] != INF;
    }

    private static int index(int maxY, int maxZ, int x, int y, int z) {
        return (x * maxY + y) * maxZ + z;
    }

    private static int compareKeys(Key a, Key b) {
        int c1 = Double.compare(a.k1, b.k1);
        if (c1 != 0) return c1;
        return Double.compare(a.k2, b.k2);
    }

    private interface NeighborConsumer {
        void accept(int idx);
    }

    private void forEachNeighbor(int idx, NeighborConsumer consumer) {
        int xyStride = maxY * maxZ;
        int x = idx / xyStride;
        int rem = idx - (x * xyStride);
        int y = rem / maxZ;
        int z = rem - (y * maxZ);

        for (int[] dir : DIRECTIONS) {
            int nx = x + dir[0];
            int ny = y + dir[1];
            int nz = z + dir[2];
            if (!isValid(nx, ny, nz)) continue;
            consumer.accept((nx * maxY + ny) * maxZ + nz);
        }
    }

    private record Key(double k1, double k2) {}

    private record Entry(int idx, double k1, double k2, long stamp) implements Comparable<Entry> {
        @Override
        public int compareTo(Entry o) {
            int c1 = Double.compare(this.k1, o.k1);
            if (c1 != 0) return c1;
            int c2 = Double.compare(this.k2, o.k2);
            if (c2 != 0) return c2;
            return Integer.compare(this.idx, o.idx);
        }
    }

    private static final class IntList {
        int[] data;
        int size;
        IntList(int initialCapacity) {
            this.data = new int[Math.max(8, initialCapacity)];
        }
        void add(int v) {
            if (size >= data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size++] = v;
        }
        void clear() { size = 0; }
    }
}
