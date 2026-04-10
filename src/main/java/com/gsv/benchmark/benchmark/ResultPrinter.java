package com.gsv.benchmark.benchmark;

import java.util.*;

/**
 * Formats and prints the benchmark results as a side-by-side table comparing
 * without-index and with-index runs for each query/database combination.
 */
public class ResultPrinter {

    private static final String COL_SEP = " │ ";

    public static void print(List<BenchmarkResult> withoutIndex,
                             List<BenchmarkResult> withIndex) {

        // Merge both lists keyed by (queryName, database)
        Map<String, BenchmarkResult> woMap = index(withoutIndex);
        Map<String, BenchmarkResult> wiMap = index(withIndex);

        // Determine ordered unique query names (preserving insertion order)
        List<String> queryNames = orderedQueryNames(withoutIndex);

        String[] databases = {"PostgreSQL", "MongoDB"};

        System.out.println();
        printTitle("BENCHMARK RESULTS  —  100,000 users · 100,000 audit logs · 500 iterations each");
        System.out.println();

        for (String db : databases) {
            printDbSection(db, queryNames, woMap, wiMap);
            System.out.println();
        }

        printSpeedupSection(queryNames, woMap, wiMap);
    }

    // ------------------------------------------------------------------

    private static void printDbSection(String db, List<String> queryNames,
                                       Map<String, BenchmarkResult> woMap,
                                       Map<String, BenchmarkResult> wiMap) {

        String header = String.format("  %-46s │ %9s │ %9s │ %9s │ %9s │  Rows",
                db, "Avg ms", "P50 ms", "P95 ms", "P99 ms");
        String rule = "─".repeat(header.length());

        System.out.println("  ╔" + "═".repeat(rule.length() - 2) + "╗");
        System.out.println("  ║ " + centerPad(db + " Query Performance", rule.length() - 4) + " ║");
        System.out.println("  ╚" + "═".repeat(rule.length() - 2) + "╝");
        System.out.println();
        System.out.printf("  %-46s │ %9s │ %9s │ %9s │ %9s │  Rows%n",
                "Query", "Avg ms", "P50 ms", "P95 ms", "P99 ms");
        System.out.println("  " + rule);

        for (String q : queryNames) {
            String key = key(q, db);
            BenchmarkResult wo = woMap.get(key);
            BenchmarkResult wi = wiMap.get(key);

            // No-index row
            if (wo != null) {
                System.out.printf("  %-46s │ %9.3f │ %9.3f │ %9.3f │ %9.3f │ %,6d  [no index]%n",
                        truncate(q, 46),
                        wo.avgMs(), wo.p50Ms(), wo.p95Ms(), wo.p99Ms(),
                        wo.getResultCount());
            }
            // With-index row
            if (wi != null) {
                System.out.printf("  %-46s │ %9.3f │ %9.3f │ %9.3f │ %9.3f │ %,6d  [indexed]%n",
                        "",
                        wi.avgMs(), wi.p50Ms(), wi.p95Ms(), wi.p99Ms(),
                        wi.getResultCount());
            }
            System.out.println("  " + rule);
        }
    }

    private static void printSpeedupSection(List<String> queryNames,
                                            Map<String, BenchmarkResult> woMap,
                                            Map<String, BenchmarkResult> wiMap) {

        System.out.println("  Index Speedup  (no-index avg / indexed avg — higher = bigger benefit)");
        System.out.println();
        System.out.printf("  %-46s │ %12s │ %12s%n", "Query", "PG speedup", "Mongo speedup");
        System.out.println("  " + "─".repeat(76));

        for (String q : queryNames) {
            double pgSp    = speedup(woMap.get(key(q, "PostgreSQL")), wiMap.get(key(q, "PostgreSQL")));
            double mongoSp = speedup(woMap.get(key(q, "MongoDB")),    wiMap.get(key(q, "MongoDB")));

            System.out.printf("  %-46s │ %11.1fx │ %11.1fx%n",
                    truncate(q, 46), pgSp, mongoSp);
        }
        System.out.println();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Map<String, BenchmarkResult> index(List<BenchmarkResult> results) {
        Map<String, BenchmarkResult> map = new LinkedHashMap<>();
        for (BenchmarkResult r : results) {
            map.put(key(r.getQueryName(), r.getDatabase()), r);
        }
        return map;
    }

    private static List<String> orderedQueryNames(List<BenchmarkResult> results) {
        List<String> names = new ArrayList<>();
        for (BenchmarkResult r : results) {
            if (!names.contains(r.getQueryName())) names.add(r.getQueryName());
        }
        return names;
    }

    private static String key(String query, String db) {
        return db + "::" + query;
    }

    private static double speedup(BenchmarkResult wo, BenchmarkResult wi) {
        if (wo == null || wi == null || wi.avgMs() == 0) return 1.0;
        return wo.avgMs() / wi.avgMs();
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }

    private static String centerPad(String s, int width) {
        int pad = Math.max(0, width - s.length());
        int left = pad / 2;
        int right = pad - left;
        return " ".repeat(left) + s + " ".repeat(right);
    }

    private static void printTitle(String title) {
        String border = "═".repeat(title.length() + 4);
        System.out.println("  ╔" + border + "╗");
        System.out.println("  ║  " + title + "  ║");
        System.out.println("  ╚" + border + "╝");
    }
}
