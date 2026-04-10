package com.gsv.benchmark.benchmark;

import java.util.Arrays;

/**
 * Holds raw latency samples (in nanoseconds) for one query/database/index combination
 * and derives summary statistics on demand.
 */
public class BenchmarkResult {

    private final String queryName;
    private final String database;
    private final boolean indexed;
    private final long[] latenciesNs;   // sorted ascending after construction
    private final long resultCount;     // sanity-check: number of rows returned

    public BenchmarkResult(String queryName, String database, boolean indexed,
                           long[] latenciesNs, long resultCount) {
        this.queryName  = queryName;
        this.database   = database;
        this.indexed    = indexed;
        this.resultCount = resultCount;
        this.latenciesNs = Arrays.copyOf(latenciesNs, latenciesNs.length);
        Arrays.sort(this.latenciesNs);
    }

    // ------------------------------------------------------------------
    // Statistics (all returned in milliseconds, rounded to 3 decimals)
    // ------------------------------------------------------------------

    public double avgMs() {
        long sum = 0;
        for (long l : latenciesNs) sum += l;
        return round3((double) sum / latenciesNs.length / 1_000_000.0);
    }

    public double p50Ms()  { return percentileMs(50); }
    public double p95Ms()  { return percentileMs(95); }
    public double p99Ms()  { return percentileMs(99); }

    private double percentileMs(int pct) {
        int idx = (int) Math.ceil(pct / 100.0 * latenciesNs.length) - 1;
        return round3(latenciesNs[Math.max(0, idx)] / 1_000_000.0);
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public String  getQueryName()   { return queryName; }
    public String  getDatabase()    { return database; }
    public boolean isIndexed()      { return indexed; }
    public long    getResultCount() { return resultCount; }
    public int     getSampleCount() { return latenciesNs.length; }
}
