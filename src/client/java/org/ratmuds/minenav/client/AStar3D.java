package org.ratmuds.minenav.client;

import java.util.*;

public class AStar3D {

    private static class Node implements Comparable<Node> {
        int x, y, z;
        double g, h; // g = cost from start, h = heuristic to end
        Node parent;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        double f() { return g + h; }

        @Override
        public int compareTo(Node other) {
            return Double.compare(this.f(), other.f());
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Node)) return false;
            Node n = (Node) obj;
            return x == n.x && y == n.y && z == n.z;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, z);
        }
    }

    // 6 directions: N, S, E, W, Up, Down
    private static final int[][] DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 0, 1}, {0, 0, -1},
            {0, 1, 0}, {0, -1, 0}
    };

    /**
     * Finds a path from start to end in a 3D grid
     * @param costs 3D array where costs[x][y][z] represents the cost of that block
     *              Use Double.POSITIVE_INFINITY for unwalkable blocks
     * @param startX, startY, startZ - starting position (feet position)
     * @param endX, endY, endZ - ending position (feet position)
     * @return List of int[3] representing the path as {x, y, z} coordinates, or null if no path
     */
    public static List<int[]> findPath(double[][][] costs,
                                       int startX, int startY, int startZ,
                                       int endX, int endY, int endZ) {

        int maxX = costs.length;
        int maxY = costs[0].length;
        int maxZ = costs[0][0].length;

        PriorityQueue<Node> openSet = new PriorityQueue<>();
        Set<Node> closedSet = new HashSet<>();
        Map<Node, Node> nodeMap = new HashMap<>();

        Node startNode = new Node(startX, startY, startZ);
        startNode.g = 0;
        startNode.h = heuristic(startX, startY, startZ, endX, endY, endZ);

        openSet.add(startNode);
        nodeMap.put(startNode, startNode);

        while (!openSet.isEmpty()) {
            Node current = openSet.poll();

            // Goal reached
            if (current.x == endX && current.y == endY && current.z == endZ) {
                return reconstructPath(current);
            }

            closedSet.add(current);

            // Check all 6 directions
            for (int[] dir : DIRECTIONS) {
                int nx = current.x + dir[0];
                int ny = current.y + dir[1];
                int nz = current.z + dir[2];

                // Bounds check
                if (nx < 0 || nx >= maxX || ny < 0 || ny >= maxY || nz < 0 || nz >= maxZ) {
                    continue;
                }

                // Check if this position is walkable (entity is 2 blocks tall)
                if (!isWalkable(costs, nx, ny, nz)) {
                    continue;
                }

                Node neighbor = new Node(nx, ny, nz);
                if (closedSet.contains(neighbor)) {
                    continue;
                }

                double tentativeG = current.g + costs[nx][ny][nz];

                Node existingNode = nodeMap.get(neighbor);
                if (existingNode == null) {
                    neighbor.g = tentativeG;
                    neighbor.h = heuristic(nx, ny, nz, endX, endY, endZ);
                    neighbor.parent = current;
                    openSet.add(neighbor);
                    nodeMap.put(neighbor, neighbor);
                } else if (tentativeG < existingNode.g) {
                    openSet.remove(existingNode);
                    existingNode.g = tentativeG;
                    existingNode.parent = current;
                    openSet.add(existingNode);
                }
            }
        }

        return null; // No path found
    }

    /**
     * Check if a position is walkable for a 2-block tall entity
     * Position (x,y,z) is the feet position, so we need y and y+1 to be passable
     */
    private static boolean isWalkable(double[][][] costs, int x, int y, int z) {
        int maxY = costs[0].length;

        // Check feet block
        if (costs[x][y][z] == Double.POSITIVE_INFINITY) {
            return false;
        }

        // Check head block (y+1)
        if (y + 1 >= maxY || costs[x][y + 1][z] == Double.POSITIVE_INFINITY) {
            return false;
        }

        return true;
    }

    /**
     * Manhattan distance heuristic
     */
    private static double heuristic(int x1, int y1, int z1, int x2, int y2, int z2) {
        return Math.abs(x1 - x2) + Math.abs(y1 - y2) + Math.abs(z1 - z2);
    }

    /**
     * Reconstruct the path from start to goal
     */
    private static List<int[]> reconstructPath(Node goal) {
        List<int[]> path = new ArrayList<>();
        Node current = goal;

        while (current != null) {
            path.add(new int[]{current.x, current.y, current.z});
            current = current.parent;
        }

        Collections.reverse(path);
        return path;
    }
}