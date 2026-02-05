package org.ratmuds.minenav.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.NonNull;

import java.util.Locale;
import java.util.Optional;

public final class BlockMatcher {
    private BlockMatcher() {}

    /**
     * Attempts to find the closest matching Block for the given query.
     *
     * Matching considers:
     * - exact id match (e.g. "minecraft:stone")
     * - exact path match (e.g. "stone")
     * - substring matches (e.g. "polished diorite" -> "polished_diorite")
     * - edit-distance similarity (Levenshtein) against id/path/display-ish forms
     *
     * @param query user input (any casing / spaces ok)
     * @param minScore 0..1, e.g. 0.72. Higher = stricter.
     */
    public static Optional<Block> findClosestBlock(String query, double minScore) {
        if (query == null) return Optional.empty();
        String qSpaces = normalizeSpaces(query);
        String qUnderscore = normalizeUnderscore(query);
        if (qSpaces.isEmpty() && qUnderscore.isEmpty()) return Optional.empty();

        Block bestBlock = null;
        double bestScore = 0.0;

        for (Block block : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(block); // e.g. minecraft:polished_diorite
            if (id == null) continue;

            String idFullUnderscore = id.toString().toLowerCase(Locale.ROOT);
            String idPathUnderscore = id.getPath().toLowerCase(Locale.ROOT);
            String idFullSpaces = normalizeSpaces(idFullUnderscore);
            String idPathSpaces = normalizeSpaces(idPathUnderscore);

            double score = scoreCandidate(qSpaces, qUnderscore, idFullSpaces, idPathSpaces, idFullUnderscore, idPathUnderscore);

            if (score > bestScore) {
                bestScore = score;
                bestBlock = block;
            }
        }

        return (bestBlock != null && bestScore >= minScore) ? Optional.of(bestBlock) : Optional.empty();
    }

    /** Convenience overload with a reasonable default strictness. */
    public static Optional<Block> findClosestBlock(String query) {
        return findClosestBlock(query, 0.72);
    }

    // ---------------- scoring ----------------

    private static double scoreCandidate(
            String qSpaces,
            String qUnderscore,
            String idFullSpaces,
            String idPathSpaces,
            String idFullUnderscore,
            String idPathUnderscore
    ) {
        // Strong signals first
        if (qUnderscore.equals(idFullUnderscore) || qSpaces.equals(idFullSpaces)) return 1.00;
        if (qUnderscore.equals(idPathUnderscore) || qSpaces.equals(idPathSpaces)) return 0.98;

        // If user typed without namespace but included "minecraft stone" etc.
        if (qUnderscore.equals(idPathUnderscore)) return 0.96;

        // Substring boosts (common for partial names)
        double containsBoost = 0.0;
        if (idPathSpaces.contains(qSpaces) || idFullSpaces.contains(qSpaces)) containsBoost = 0.12;
        if (qSpaces.contains(idPathSpaces)) containsBoost = Math.max(containsBoost, 0.10);

        // Similarity via normalized Levenshtein to several forms
        double simFullSpaces = similarity(qSpaces, idFullSpaces);
        double simPathSpaces = similarity(qSpaces, idPathSpaces);
        double simFullUnderscore = similarity(qUnderscore, idFullUnderscore);
        double simPathUnderscore = similarity(qUnderscore, idPathUnderscore);

        double base = Math.max(
                Math.max(simFullSpaces, simPathSpaces),
                Math.max(simFullUnderscore, simPathUnderscore)
        );
        double score = clamp01(base + containsBoost);

        // Small extra boost if words match (space-separated)
        score = clamp01(score + wordOverlapBoost(qSpaces, idPathSpaces));

        return score;
    }

    private static double wordOverlapBoost(String q, String candidateSpaced) {
        String[] qw = q.split("\\s+");
        String[] cw = candidateSpaced.split("\\s+");
        if (qw.length == 0 || cw.length == 0) return 0.0;

        int hit = 0;
        for (String a : qw) {
            if (a.isEmpty()) continue;
            for (String b : cw) {
                if (a.equals(b)) { hit++; break; }
            }
        }
        double overlap = (double) hit / (double) Math.max(qw.length, cw.length);
        return overlap * 0.10; // up to +0.10
    }

    // ---------------- normalization ----------------

    private static String normalizeSpaces(String s) {
        s = s.toLowerCase(Locale.ROOT).trim();
        // unify separators; keep ':' for namespace matching
        s = s.replace('-', ' ')
                .replace('_', ' ')
                .replaceAll("\\s+", " ");
        return s;
    }

    private static String normalizeUnderscore(String s) {
        s = s.toLowerCase(Locale.ROOT).trim();
        // keep ':' for namespace matching; unify spaces/dashes to underscores
        s = s.replace('-', ' ')
                .replaceAll("\\s+", " ");
        s = s.replace(' ', '_');
        return s;
    }

    // ---------------- similarity (Levenshtein) ----------------

    /**
     * Returns similarity in [0..1] where 1 is identical.
     */
    private static double similarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1.0;
        int dist = levenshtein(a, b);
        return 1.0 - ((double) dist / (double) maxLen);
    }

    /**
     * Iterative Levenshtein distance, O(n*m) time, O(m) memory.
     */
    private static int levenshtein(String a, String b) {
        int n = a.length();
        int m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;

        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];

        for (int j = 0; j <= m; j++) prev[j] = j;

        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);

            for (int j = 1; j <= m; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                cur[j] = Math.min(
                        Math.min(cur[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost
                );
            }

            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }

        return prev[m];
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
