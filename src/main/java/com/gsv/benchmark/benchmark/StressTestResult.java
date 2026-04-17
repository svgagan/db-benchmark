package com.gsv.benchmark.benchmark;

import java.util.Arrays;

/**
 * Holds the aggregate results of a concurrent stress test run for one database.
 *
 * <p>Latency statistics are derived from all operation latency samples collected
 * across all worker threads during the stress period.
 */
public class StressTestResult {

    private final String   database;
    private final long     totalOps;
    private final long     errors;
    private final double   durationSeconds;
    private final long[]   sortedLatenciesNs;

    public StressTestResult(String database, long totalOps, long errors,
                            double durationSeconds, long[] latenciesNs) {
        this.database        = database;
        this.totalOps        = totalOps;
        this.errors          = errors;
        this.durationSeconds = durationSeconds;
        this.sortedLatenciesNs = Arrays.copyOf(latenciesNs, latenciesNs.length);
        Arrays.sort(this.sortedLatenciesNs);
    }

    // ------------------------------------------------------------------
    // Derived statistics
    // ------------------------------------------------------------------

    public double opsPerSecond() {
        return durationSeconds > 0 ? totalOps / durationSeconds : 0;
    }

    public double avgMs() {
        if (sortedLatenciesNs.length == 0) return 0;
        long sum = 0;
        for (long l : sortedLatenciesNs) sum += l;
        return round3((double) sum / sortedLatenciesNs.length / 1_000_000.0);
    }

    public double p50Ms() { return percentileMs(50); }
    public double p95Ms() { return percentileMs(95); }
    public double p99Ms() { return percentileMs(99); }

    private double percentileMs(int pct) {
        if (sortedLatenciesNs.length == 0) return 0;
        int idx = (int) Math.ceil(pct / 100.0 * sortedLatenciesNs.length) - 1;
        return round3(sortedLatenciesNs[Math.max(0, idx)] / 1_000_000.0);
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public String getDatabase()      { return database; }
    public long   getTotalOps()      { return totalOps; }
    public long   getErrors()        { return errors; }
    public double getDurationSeconds(){ return durationSeconds; }
    public int    getSampleCount()   { return sortedLatenciesNs.length; }
}
