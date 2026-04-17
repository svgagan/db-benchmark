package com.gsv.benchmark.benchmark;

import com.gsv.benchmark.data.DataGenerator;
import com.gsv.benchmark.model.GoldenPerson;
import com.gsv.benchmark.mongodb.MongoRepository;
import com.gsv.benchmark.postgres.PostgresRepository;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates all 14 MDM benchmark queries against both databases.
 *
 * <h3>Q1–Q10: SELECT benchmarks</h3>
 * <table border="1">
 *   <tr><th>#</th><th>Description</th><th>Parameter</th></tr>
 *   <tr><td>Q1</td><td>Composite PK fetch</td><td>sampleId from DB</td></tr>
 *   <tr><td>Q2</td><td>Latest prior_values entry</td><td>same sampleId</td></tr>
 *   <tr><td>Q3</td><td>Address by type</td><td>same sampleId + "PRIMARY"</td></tr>
 *   <tr><td>Q4</td><td>SSN exact match</td><td>SSN_POOL[0] (~100 matches)</td></tr>
 *   <tr><td>Q5</td><td>last_name + DOB compound</td><td>captured from seeded record</td></tr>
 *   <tr><td>Q6</td><td>global_pid only</td><td>sampleGlobalPid from DB</td></tr>
 *   <tr><td>Q7</td><td>rule_id</td><td>"RULE_001" (~10% of records)</td></tr>
 *   <tr><td>Q8</td><td>mastered last 30 days</td><td>fixed cutoff at benchmark start</td></tr>
 *   <tr><td>Q9</td><td>prior SSN history search</td><td>SSN_POOL[0]</td></tr>
 *   <tr><td>Q10</td><td>mastered between two timestamps</td><td>6mo ago → 3mo ago</td></tr>
 * </table>
 *
 * <h3>Q11–Q12: INSERT benchmarks</h3>
 * <table border="1">
 *   <tr><td>Q11</td><td>Single INSERT latency</td><td>one record, cleanup after</td></tr>
 *   <tr><td>Q12</td><td>Batch INSERT (100 records)</td><td>100-record batch, cleanup after</td></tr>
 * </table>
 *
 * <h3>Q13–Q14: UPDATE benchmarks</h3>
 * <table border="1">
 *   <tr><td>Q13</td><td>SSN point UPDATE</td><td>jsonb_set / $set on scalar field</td></tr>
 *   <tr><td>Q14</td><td>prior_values array APPEND</td><td>jsonb || / $push — core MDM write</td></tr>
 * </table>
 */
public class BenchmarkRunner {

    private static final int INSERT_BATCH_SIZE = 100;

    private final PostgresRepository pg;
    private final MongoRepository    mongo;
    private final int warmupIterations;
    private final int measureIterations;

    // Insert pool — 50k pre-generated records; each iteration clones with fresh UUID
    private final List<GoldenPerson> insertPool;

    // Update sample IDs — random subset of seeded records; cycled across Q13/Q14 iterations
    private final List<String> updateSampleIds;

    // Fixed query parameters — set once at construction so all iterations use identical inputs
    private final String sampleId;
    private final String sampleGlobalPid;
    private final String sampleLastName;
    private final String sampleDob;

    private static final String QUERY_SSN       = DataGenerator.sampleSsn();
    private static final String QUERY_RULE_ID   = "RULE_001";
    private static final String QUERY_ADDR_TYPE = "PRIMARY";
    private static final String UPDATE_SSN      = "999-88-7777";   // value written by Q13

    // Q8: 30-day cutoff fixed at construction — same value for every iteration
    private final String cutoff30Days;

    // Q10: 3-month window within the 2-year data spread
    private final String rangeStart;
    private final String rangeEnd;

    public BenchmarkRunner(PostgresRepository pg, MongoRepository mongo,
                           int warmupIterations, int measureIterations,
                           List<GoldenPerson> insertPool, List<String> updateSampleIds) {
        this.pg                = pg;
        this.mongo             = mongo;
        this.warmupIterations  = warmupIterations;
        this.measureIterations = measureIterations;
        this.insertPool        = insertPool;
        this.updateSampleIds   = updateSampleIds;

        // Pull sample values from repositories (populated by loadSampleValues())
        this.sampleId        = pg.getSampleId();
        this.sampleGlobalPid = pg.getSampleGlobalPid();
        this.sampleLastName  = pg.getSampleLastName();
        this.sampleDob       = pg.getSampleDob();

        this.cutoff30Days = Instant.now()
                .minus(30, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS)
                .toString();
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
     * Runs all 14 queries (Q1–Q14) against both databases.
     *
     * @param indexed {@code true} if indexes have already been created
     * @return list of results — one per (query × database) pair
     */
    public List<BenchmarkResult> runAll(boolean indexed) throws Exception {
        List<BenchmarkResult> results = new ArrayList<>();

        // ── SELECT benchmarks (Q1–Q10) ────────────────────────────────────────
        results.add(measure("Q1  composite PK fetch (full record)", "PostgreSQL", indexed,
                () -> pg.queryByPrimaryKey()));
        results.add(measure("Q1  composite PK fetch (full record)", "MongoDB", indexed,
                () -> (long) mongo.queryByPrimaryKey()));

        results.add(measure("Q2  latest prior_values entry (prev winner)", "PostgreSQL", indexed,
                () -> pg.queryLatestPriorValue()));
        results.add(measure("Q2  latest prior_values entry (prev winner)", "MongoDB", indexed,
                () -> (long) mongo.queryLatestPriorValue()));

        results.add(measure("Q3  address by type for known PK", "PostgreSQL", indexed,
                () -> pg.queryAddressByType(QUERY_ADDR_TYPE)));
        results.add(measure("Q3  address by type for known PK", "MongoDB", indexed,
                () -> (long) mongo.queryAddressByType(QUERY_ADDR_TYPE)));

        results.add(measure("Q4  demographic.ssn exact match", "PostgreSQL", indexed,
                () -> pg.queryBySsn(QUERY_SSN)));
        results.add(measure("Q4  demographic.ssn exact match", "MongoDB", indexed,
                () -> mongo.queryBySsn(QUERY_SSN)));

        results.add(measure("Q5  last_name + date_of_birth compound", "PostgreSQL", indexed,
                () -> pg.queryByLastNameAndDob(sampleLastName, sampleDob)));
        results.add(measure("Q5  last_name + date_of_birth compound", "MongoDB", indexed,
                () -> mongo.queryByLastNameAndDob(sampleLastName, sampleDob)));

        results.add(measure("Q6  global_pid lookup (partial key)", "PostgreSQL", indexed,
                () -> pg.queryByGlobalPid()));
        results.add(measure("Q6  global_pid lookup (partial key)", "MongoDB", indexed,
                () -> mongo.queryByGlobalPid()));

        results.add(measure("Q7  demographic.rule_id = 'RULE_001'", "PostgreSQL", indexed,
                () -> pg.queryByRuleId(QUERY_RULE_ID)));
        results.add(measure("Q7  demographic.rule_id = 'RULE_001'", "MongoDB", indexed,
                () -> mongo.queryByRuleId(QUERY_RULE_ID)));

        results.add(measure("Q8  mastered_date_ts last 30 days", "PostgreSQL", indexed,
                () -> pg.queryMasteredLast30Days(cutoff30Days)));
        results.add(measure("Q8  mastered_date_ts last 30 days", "MongoDB", indexed,
                () -> mongo.queryMasteredLast30Days(cutoff30Days)));

        results.add(measure("Q9  prior_values[*].ssn history search", "PostgreSQL", indexed,
                () -> pg.queryPriorSsn(QUERY_SSN)));
        results.add(measure("Q9  prior_values[*].ssn history search", "MongoDB", indexed,
                () -> mongo.queryPriorSsn(QUERY_SSN)));

        results.add(measure("Q10 mastered_date_ts range (6mo ago → 3mo ago)", "PostgreSQL", indexed,
                () -> pg.queryMasteredBetween(rangeStart, rangeEnd)));
        results.add(measure("Q10 mastered_date_ts range (6mo ago → 3mo ago)", "MongoDB", indexed,
                () -> mongo.queryMasteredBetween(rangeStart, rangeEnd)));

        // ── Q15: TOAST pressure / scalar projection ───────────────────────────
        // PG: shows TOAST overhead — delta vs Q1 reveals how much penalty large
        //     JSONB blobs incur when stored out-of-line.
        // Mongo: shows network payload savings — no storage I/O difference.
        results.add(measure("Q15 scalar projection (4 fields vs full record Q1)", "PostgreSQL", indexed,
                () -> pg.queryScalarProjection()));
        results.add(measure("Q15 scalar projection (4 fields vs full record Q1)", "MongoDB", indexed,
                () -> (long) mongo.queryScalarProjection()));

        // ── INSERT benchmarks (Q11–Q12) ───────────────────────────────────────
        results.addAll(measureInsertSingle(indexed));
        results.addAll(measureInsertBatch(indexed));

        // ── UPDATE benchmarks (Q13–Q14) ───────────────────────────────────────
        AtomicInteger pgUpdateIdx    = new AtomicInteger(0);
        AtomicInteger mongoUpdateIdx = new AtomicInteger(0);

        GoldenPerson.DemographicHistory histEntry = DataGenerator.buildSampleHistory();

        results.add(measure("Q13 SSN point UPDATE (jsonb_set / $set)", "PostgreSQL", indexed,
                () -> pg.updateSsnById(
                        updateSampleIds.get(pgUpdateIdx.getAndIncrement() % updateSampleIds.size()),
                        UPDATE_SSN)));
        results.add(measure("Q13 SSN point UPDATE (jsonb_set / $set)", "MongoDB", indexed,
                () -> mongo.updateSsnById(
                        updateSampleIds.get(mongoUpdateIdx.getAndIncrement() % updateSampleIds.size()),
                        UPDATE_SSN)));

        pgUpdateIdx.set(0);
        mongoUpdateIdx.set(0);

        results.add(measure("Q14 prior_values APPEND (jsonb || / $push)", "PostgreSQL", indexed,
                () -> pg.appendDemographicHistory(
                        updateSampleIds.get(pgUpdateIdx.getAndIncrement() % updateSampleIds.size()),
                        histEntry)));
        results.add(measure("Q14 prior_values APPEND (jsonb || / $push)", "MongoDB", indexed,
                () -> mongo.appendDemographicHistory(
                        updateSampleIds.get(mongoUpdateIdx.getAndIncrement() % updateSampleIds.size()),
                        histEntry)));

        return results;
    }

    // ------------------------------------------------------------------
    // INSERT benchmark helpers (Q11, Q12)
    // ------------------------------------------------------------------

    /**
     * Q11 — Single INSERT latency for both databases.
     * Timing is captured inside each repository method ({@code insertSingle}).
     * Warm-up inserts and measure inserts are cleaned up after each phase
     * so the row count remains stable for subsequent queries.
     */
    private List<BenchmarkResult> measureInsertSingle(boolean indexed) throws Exception {
        List<BenchmarkResult> results = new ArrayList<>();
        AtomicInteger poolIdx = new AtomicInteger(0);

        for (String db : List.of("PostgreSQL", "MongoDB")) {
            boolean isPg = db.equals("PostgreSQL");

            // Warm-up
            List<String> warmupIds = new ArrayList<>(warmupIterations);
            for (int i = 0; i < warmupIterations; i++) {
                GoldenPerson p = DataGenerator.withNewId(
                        insertPool.get(poolIdx.getAndIncrement() % insertPool.size()));
                if (isPg) pg.insertSingle(p);
                else      mongo.insertSingle(p);
                warmupIds.add(p.getId());
            }
            if (isPg) pg.deleteByIds(warmupIds);
            else      mongo.deleteByIds(warmupIds);

            // Measure
            long[]      latencies   = new long[measureIterations];
            List<String> measureIds = new ArrayList<>(measureIterations);
            for (int i = 0; i < measureIterations; i++) {
                GoldenPerson p = DataGenerator.withNewId(
                        insertPool.get(poolIdx.getAndIncrement() % insertPool.size()));
                latencies[i] = isPg ? pg.insertSingle(p) : mongo.insertSingle(p);
                measureIds.add(p.getId());
            }
            if (isPg) pg.deleteByIds(measureIds);
            else      mongo.deleteByIds(measureIds);

            results.add(new BenchmarkResult(
                    "Q11 single INSERT latency (1 record)", db, indexed,
                    latencies, measureIterations));
        }
        return results;
    }

    /**
     * Q12 — Batch INSERT (100 records) for both databases.
     * Each timing call covers the full 100-record batch.
     * Cleanup happens after each phase.
     */
    private List<BenchmarkResult> measureInsertBatch(boolean indexed) throws Exception {
        List<BenchmarkResult> results = new ArrayList<>();
        AtomicInteger poolIdx = new AtomicInteger(0);

        for (String db : List.of("PostgreSQL", "MongoDB")) {
            boolean isPg = db.equals("PostgreSQL");

            // Warm-up
            List<String> warmupIds = new ArrayList<>(warmupIterations * INSERT_BATCH_SIZE);
            for (int i = 0; i < warmupIterations; i++) {
                List<GoldenPerson> batch = buildInsertBatch(poolIdx);
                if (isPg) pg.insertBatch(batch);
                else      mongo.insertBatch(batch);
                batch.forEach(p -> warmupIds.add(p.getId()));
            }
            if (isPg) pg.deleteByIds(warmupIds);
            else      mongo.deleteByIds(warmupIds);

            // Measure
            long[]      latencies   = new long[measureIterations];
            List<String> measureIds = new ArrayList<>(measureIterations * INSERT_BATCH_SIZE);
            for (int i = 0; i < measureIterations; i++) {
                List<GoldenPerson> batch = buildInsertBatch(poolIdx);
                latencies[i] = isPg ? pg.insertBatch(batch) : mongo.insertBatch(batch);
                batch.forEach(p -> measureIds.add(p.getId()));
            }
            if (isPg) pg.deleteByIds(measureIds);
            else      mongo.deleteByIds(measureIds);

            results.add(new BenchmarkResult(
                    "Q12 batch INSERT (100 records)", db, indexed,
                    latencies, measureIterations));
        }
        return results;
    }

    private List<GoldenPerson> buildInsertBatch(AtomicInteger poolIdx) {
        List<GoldenPerson> batch = new ArrayList<>(INSERT_BATCH_SIZE);
        for (int j = 0; j < INSERT_BATCH_SIZE; j++) {
            batch.add(DataGenerator.withNewId(
                    insertPool.get(poolIdx.getAndIncrement() % insertPool.size())));
        }
        return batch;
    }

    // ------------------------------------------------------------------
    // Generic timing harness
    // ------------------------------------------------------------------

    /**
     * Runs warmup iterations (untimed), then {@code measureIterations} timed calls.
     * The callable returns the result count (used for sanity checking in output).
     * The callable itself may return pre-captured elapsed nanoseconds (for INSERT/UPDATE)
     * or a plain count (for SELECT — timing is done here with System.nanoTime()).
     */
    private BenchmarkResult measure(String queryName, String database, boolean indexed,
                                    Callable<Long> query) throws Exception {
        // Warmup
        for (int i = 0; i < warmupIterations; i++) query.call();

        long resultCount = 0;
        long[] latencies = new long[measureIterations];
        for (int i = 0; i < measureIterations; i++) {
            long t0    = System.nanoTime();
            long count = query.call();
            latencies[i] = System.nanoTime() - t0;
            if (i == 0) resultCount = count;
        }
        return new BenchmarkResult(queryName, database, indexed, latencies, resultCount);
    }
}
