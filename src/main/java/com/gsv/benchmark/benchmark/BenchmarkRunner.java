package com.gsv.benchmark.benchmark;

import com.gsv.benchmark.data.DataGenerator;
import com.gsv.benchmark.mongodb.MongoRepository;
import com.gsv.benchmark.postgres.PostgresRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Orchestrates all 9 MDM benchmark queries against both databases.
 *
 * <h3>Query parameters</h3>
 * <table border="1">
 *   <tr><th>#</th><th>Description</th><th>Parameter</th><th>Expected rows</th></tr>
 *   <tr><td>Q1</td><td>Composite PK fetch</td><td>sampleId captured during seeding</td><td>1</td></tr>
 *   <tr><td>Q2</td><td>Latest prior_values entry</td><td>same sampleId</td><td>1 (last array element)</td></tr>
 *   <tr><td>Q3</td><td>Address by type</td><td>same sampleId + "PRIMARY"</td><td>1</td></tr>
 *   <tr><td>Q4</td><td>SSN exact match</td><td>SSN_POOL[0] (~100 matches)</td><td>~100</td></tr>
 *   <tr><td>Q5</td><td>last_name + DOB compound</td><td>captured from seeded record</td><td>&ge;1</td></tr>
 *   <tr><td>Q6</td><td>global_pid only</td><td>sampleGlobalPid from seeding</td><td>1</td></tr>
 *   <tr><td>Q7</td><td>rule_id</td><td>"RULE_001" (~10% of records)</td><td>~10,000</td></tr>
 *   <tr><td>Q8</td><td>mastered last 30 days</td><td>fixed cutoff at benchmark start</td><td>~4,100</td></tr>
 *   <tr><td>Q9</td><td>prior SSN history search</td><td>SSN_POOL[0] (same as Q4)</td><td>~150</td></tr>
 * </table>
 */
public class BenchmarkRunner {

    private final PostgresRepository pg;
    private final MongoRepository    mongo;
    private final int warmupIterations;
    private final int measureIterations;

    // Fixed query parameters — set once at construction time so all 500
    // iterations of each query use identical inputs (no time drift, no variance)
    private final String sampleId;
    private final String sampleGlobalPid;
    private final String sampleLastName;
    private final String sampleDob;

    private static final String QUERY_SSN       = DataGenerator.sampleSsn();   // SSN_POOL[0]
    private static final String QUERY_RULE_ID   = "RULE_001";
    private static final String QUERY_ADDR_TYPE = "PRIMARY";

    // Q8: 30-day cutoff captured at construction — same value for every iteration
    private final String cutoff30Days;

    // Q10: 3-month window within the 2-year data spread (~12.5 % of records = ~12,500)
    //      start = 6 months ago,  end = 3 months ago
    private final String rangeStart;
    private final String rangeEnd;

    public BenchmarkRunner(PostgresRepository pg, MongoRepository mongo,
                           int warmupIterations, int measureIterations) {
        this.pg                = pg;
        this.mongo             = mongo;
        this.warmupIterations  = warmupIterations;
        this.measureIterations = measureIterations;

        // Pull sample values from PG repo (both repos seeded same data in same order)
        this.sampleId        = pg.getSampleId();
        this.sampleGlobalPid = pg.getSampleGlobalPid();
        this.sampleLastName  = pg.getSampleLastName();
        this.sampleDob       = pg.getSampleDob();

        this.cutoff30Days = Instant.now()
                .minus(30, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS)
                .toString();

        // Q10: 3-month window — 6 months ago → 3 months ago
        this.rangeStart = Instant.now()
                .minus(180, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS)
                .toString();
        this.rangeEnd = Instant.now()
                .minus(90, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS)
                .toString();
    }

    /**
     * Runs all 9 queries against both databases.
     *
     * @param indexed {@code true} if indexes have already been created
     * @return 18 results — one per (query × database) pair
     */
    public List<BenchmarkResult> runAll(boolean indexed) throws Exception {
        List<BenchmarkResult> results = new ArrayList<>();

        // Q1 — Composite PK fetch
        results.add(measure("Q1  composite PK fetch (full record)", "PostgreSQL", indexed,
                () -> pg.queryByPrimaryKey()));
        results.add(measure("Q1  composite PK fetch (full record)", "MongoDB", indexed,
                () -> mongo.queryByPrimaryKey()));

        // Q2 — Latest prior_values entry (previous winner)
        results.add(measure("Q2  latest prior_values entry (prev winner)", "PostgreSQL", indexed,
                () -> pg.queryLatestPriorValue()));
        results.add(measure("Q2  latest prior_values entry (prev winner)", "MongoDB", indexed,
                () -> mongo.queryLatestPriorValue()));

        // Q3 — Address by type for known person (full record + matched address)
        results.add(measure("Q3  address by type for known PK", "PostgreSQL", indexed,
                () -> pg.queryAddressByType(QUERY_ADDR_TYPE)));
        results.add(measure("Q3  address by type for known PK", "MongoDB", indexed,
                () -> mongo.queryAddressByType(QUERY_ADDR_TYPE)));

        // Q4 — SSN exact match (identity resolution)
        results.add(measure("Q4  demographic.ssn exact match", "PostgreSQL", indexed,
                () -> pg.queryBySsn(QUERY_SSN)));
        results.add(measure("Q4  demographic.ssn exact match", "MongoDB", indexed,
                () -> mongo.queryBySsn(QUERY_SSN)));

        // Q5 — Compound last_name + date_of_birth (probabilistic matching)
        results.add(measure("Q5  last_name + date_of_birth compound", "PostgreSQL", indexed,
                () -> pg.queryByLastNameAndDob(sampleLastName, sampleDob)));
        results.add(measure("Q5  last_name + date_of_birth compound", "MongoDB", indexed,
                () -> mongo.queryByLastNameAndDob(sampleLastName, sampleDob)));

        // Q6 — global_pid only (person across multiple customer contexts)
        results.add(measure("Q6  global_pid lookup (partial key)", "PostgreSQL", indexed,
                () -> pg.queryByGlobalPid()));
        results.add(measure("Q6  global_pid lookup (partial key)", "MongoDB", indexed,
                () -> mongo.queryByGlobalPid()));

        // Q7 — rule_id (operational audit)
        results.add(measure("Q7  demographic.rule_id = 'RULE_001'", "PostgreSQL", indexed,
                () -> pg.queryByRuleId(QUERY_RULE_ID)));
        results.add(measure("Q7  demographic.rule_id = 'RULE_001'", "MongoDB", indexed,
                () -> mongo.queryByRuleId(QUERY_RULE_ID)));

        // Q8 — Mastered in last 30 days (sync / change detection window)
        results.add(measure("Q8  mastered_date_ts last 30 days", "PostgreSQL", indexed,
                () -> pg.queryMasteredLast30Days(cutoff30Days)));
        results.add(measure("Q8  mastered_date_ts last 30 days", "MongoDB", indexed,
                () -> mongo.queryMasteredLast30Days(cutoff30Days)));

        // Q9 — Prior SSN history search (pre-merge dedup check)
        results.add(measure("Q9  prior_values[*].ssn history search", "PostgreSQL", indexed,
                () -> pg.queryPriorSsn(QUERY_SSN)));
        results.add(measure("Q9  prior_values[*].ssn history search", "MongoDB", indexed,
                () -> mongo.queryPriorSsn(QUERY_SSN)));

        // Q10 — mastered_date_ts range: between start and end timestamp
        results.add(measure("Q10 mastered_date_ts range (6mo ago → 3mo ago)", "PostgreSQL", indexed,
                () -> pg.queryMasteredBetween(rangeStart, rangeEnd)));
        results.add(measure("Q10 mastered_date_ts range (6mo ago → 3mo ago)", "MongoDB", indexed,
                () -> mongo.queryMasteredBetween(rangeStart, rangeEnd)));

        return results;
    }

    // ------------------------------------------------------------------
    // Timing harness
    // ------------------------------------------------------------------

    private BenchmarkResult measure(String queryName, String database, boolean indexed,
                                    Callable<Long> query) throws Exception {
        for (int i = 0; i < warmupIterations; i++) query.call();

        long resultCount = 0;
        long[] latencies = new long[measureIterations];
        for (int i = 0; i < measureIterations; i++) {
            long t0 = System.nanoTime();
            long count = query.call();
            latencies[i] = System.nanoTime() - t0;
            if (i == 0) resultCount = count;
        }
        return new BenchmarkResult(queryName, database, indexed, latencies, resultCount);
    }
}
