package com.gsv.benchmark.benchmark;

import com.gsv.benchmark.data.DataGenerator;
import com.gsv.benchmark.data.SampleRecord;
import com.gsv.benchmark.model.GoldenPerson;
import com.gsv.benchmark.mongodb.MongoRepository;
import com.gsv.benchmark.postgres.PostgresRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concurrent stress test: runs {@code threadCount} workers against each database
 * simultaneously for {@code durationSeconds} seconds and reports throughput and
 * latency percentiles.
 *
 * <h3>Operation mix (configurable)</h3>
 * <ul>
 *   <li><b>SELECT</b> ({@code selectPct}%) — randomly picks one of Q1–Q10</li>
 *   <li><b>INSERT</b> ({@code insertPct}%) — single-record INSERT using the pool;
 *       no cleanup during the stress run (row delta is negligible at 1 M scale)</li>
 *   <li><b>UPDATE</b> (remainder) — randomly picks Q13 (SSN update) or Q14 (prior_values append)</li>
 * </ul>
 *
 * <h3>Thread model</h3>
 * {@code threadCount} threads are launched against PostgreSQL, and separately
 * {@code threadCount} threads are launched against MongoDB — both database pools
 * are stressed simultaneously. A shared {@link CountDownLatch} ensures all
 * threads start at the same instant.
 */
public class StressTestRunner {

    private static final String QUERY_SSN       = DataGenerator.sampleSsn();
    private static final String QUERY_RULE_ID   = "RULE_001";
    private static final String QUERY_ADDR_TYPE = "PRIMARY";
    private static final String UPDATE_SSN      = "999-88-7777";

    // Pre-built once — reused across all UPDATE operations in the hot path
    private static final GoldenPerson.DemographicHistory SAMPLE_HISTORY =
            DataGenerator.buildSampleHistory();

    private final PostgresRepository  pg;
    private final MongoRepository     mongo;
    private final List<GoldenPerson>  insertPool;
    private final List<String>        updateSampleIds;
    private final List<SampleRecord>  samplePool;   // randomised per-operation — kills buffer-cache bias

    private final int    threadCount;
    private final int    durationSeconds;
    private final int    selectPct;
    private final int    insertPct;

    public StressTestRunner(PostgresRepository pg,
                            MongoRepository    mongo,
                            List<GoldenPerson> insertPool,
                            List<String>       updateSampleIds,
                            List<SampleRecord> samplePool,
                            int threadCount,
                            int durationSeconds,
                            int selectPct,
                            int insertPct) {
        this.pg              = pg;
        this.mongo           = mongo;
        this.insertPool      = insertPool;
        this.updateSampleIds = updateSampleIds;
        this.samplePool      = samplePool;
        this.threadCount     = threadCount;
        this.durationSeconds = durationSeconds;
        this.selectPct       = selectPct;
        this.insertPct       = insertPct;
    }

    /**
     * Runs the stress test and returns {@code [pgResult, mongoResult]}.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public StressTestResult[] run() throws InterruptedException {
        System.out.printf("  Stress test: %d threads × %ds  (SELECT %d%% / INSERT %d%% / UPDATE %d%%)%n",
                threadCount, durationSeconds, selectPct, insertPct, 100 - selectPct - insertPct);

        // Fixed timestamps for Q8/Q10 — same value for every iteration
        String cutoff30Days = Instant.now().minus(30, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS).toString();
        String rangeStart   = Instant.now().minus(180, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS).toString();
        String rangeEnd     = Instant.now().minus(90, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS).toString();

        // PG accumulators
        ConcurrentLinkedQueue<Long> pgLatencies = new ConcurrentLinkedQueue<>();
        AtomicLong pgOps    = new AtomicLong(0);
        AtomicLong pgErrors = new AtomicLong(0);

        // Mongo accumulators
        ConcurrentLinkedQueue<Long> mongoLatencies = new ConcurrentLinkedQueue<>();
        AtomicLong mongoOps    = new AtomicLong(0);
        AtomicLong mongoErrors = new AtomicLong(0);

        // Shared start gate — all threads fire simultaneously
        CountDownLatch startGate = new CountDownLatch(1);
        Instant deadline = Instant.now().plusSeconds(durationSeconds);

        ExecutorService pgPool    = Executors.newFixedThreadPool(threadCount);
        ExecutorService mongoPool = Executors.newFixedThreadPool(threadCount);

        // Submit PG workers
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            pgPool.submit(() -> {
                Random rng = new Random(42L + threadId);
                try {
                    startGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                while (Instant.now().isBefore(deadline)) {
                    try {
                        long ns = executePg(rng, cutoff30Days, rangeStart, rangeEnd);
                        pgLatencies.add(ns);
                        pgOps.incrementAndGet();
                    } catch (Exception e) {
                        pgErrors.incrementAndGet();
                    }
                }
            });
        }

        // Submit Mongo workers
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            mongoPool.submit(() -> {
                Random rng = new Random(100L + threadId);
                try {
                    startGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                while (Instant.now().isBefore(deadline)) {
                    try {
                        long ns = executeMongo(rng, cutoff30Days, rangeStart, rangeEnd);
                        mongoLatencies.add(ns);
                        mongoOps.incrementAndGet();
                    } catch (Exception e) {
                        mongoErrors.incrementAndGet();
                    }
                }
            });
        }

        // Fire all threads simultaneously
        long wallStart = System.nanoTime();
        startGate.countDown();

        // Wait for completion
        pgPool.shutdown();
        mongoPool.shutdown();
        pgPool.awaitTermination(durationSeconds + 30L, TimeUnit.SECONDS);
        mongoPool.awaitTermination(durationSeconds + 30L, TimeUnit.SECONDS);

        double actualDuration = (System.nanoTime() - wallStart) / 1_000_000_000.0;

        return new StressTestResult[]{
            toResult("PostgreSQL", pgOps.get(),    pgErrors.get(),    actualDuration, pgLatencies),
            toResult("MongoDB",    mongoOps.get(),  mongoErrors.get(), actualDuration, mongoLatencies)
        };
    }

    // ------------------------------------------------------------------
    // Per-operation dispatch
    // ------------------------------------------------------------------

    private long executePg(Random rng, String cutoff30, String rangeStart, String rangeEnd)
            throws Exception {
        int op = rng.nextInt(100);
        if (op < selectPct) {
            return timePg(() -> dispatchPgSelect(rng, cutoff30, rangeStart, rangeEnd));
        } else if (op < selectPct + insertPct) {
            GoldenPerson p = DataGenerator.withNewId(
                    insertPool.get(rng.nextInt(insertPool.size())));
            return timePg(() -> pg.insertSingle(p));   // full wall time: borrow + execute + return
        } else {
            return timePg(() -> dispatchPgUpdate(rng));
        }
    }

    private long executeMongo(Random rng, String cutoff30, String rangeStart, String rangeEnd) {
        int op = rng.nextInt(100);
        if (op < selectPct) {
            return timeMongo(() -> dispatchMongoSelect(rng, cutoff30, rangeStart, rangeEnd));
        } else if (op < selectPct + insertPct) {
            GoldenPerson p = DataGenerator.withNewId(
                    insertPool.get(rng.nextInt(insertPool.size())));
            return timeMongo(() -> mongo.insertSingle(p));  // full wall time: driver + execute
        } else {
            return timeMongo(() -> dispatchMongoUpdate(rng));
        }
    }

    // ── SELECT dispatch ────────────────────────────────────────────────

    /**
     * Picks a random Q1–Q10 SELECT and executes it against PostgreSQL.
     *
     * <p>Every call draws fresh parameters from the pool so no single record
     * or SSN stays pinned in the buffer cache across iterations:
     * <ul>
     *   <li>Q1/Q2/Q3 — random record {@code id} from {@code samplePool}</li>
     *   <li>Q4/Q9    — random SSN from the 1,000-entry pool</li>
     *   <li>Q5       — random {@code (last_name, dob)} from {@code samplePool}</li>
     *   <li>Q6       — random {@code global_pid} from {@code samplePool}</li>
     *   <li>Q7       — random rule ID (RULE_001…RULE_010)</li>
     *   <li>Q8/Q10   — time-range parameters (same across all threads; no record dependency)</li>
     * </ul>
     */
    private void dispatchPgSelect(Random rng, String cutoff30, String rangeStart, String rangeEnd)
            throws Exception {
        SampleRecord rec = samplePool.get(rng.nextInt(samplePool.size()));
        switch (rng.nextInt(10)) {
            case 0 -> pg.queryByPrimaryKey(rec.id());
            case 1 -> pg.queryLatestPriorValue(rec.id());
            case 2 -> pg.queryAddressByType(rec.id(), QUERY_ADDR_TYPE);
            case 3 -> pg.queryBySsn(DataGenerator.randomSsn(rng));
            case 4 -> pg.queryByLastNameAndDob(rec.lastName(), rec.dob());
            case 5 -> pg.queryByGlobalPid(rec.globalPid());
            case 6 -> pg.queryByRuleId(DataGenerator.randomRuleId(rng));
            case 7 -> pg.queryMasteredLast30Days(cutoff30);
            case 8 -> pg.queryPriorSsn(DataGenerator.randomSsn(rng));
            default -> pg.queryMasteredBetween(rangeStart, rangeEnd);
        }
    }

    /** Same as {@link #dispatchPgSelect} but targets MongoDB. */
    private void dispatchMongoSelect(Random rng, String cutoff30, String rangeStart, String rangeEnd) {
        SampleRecord rec = samplePool.get(rng.nextInt(samplePool.size()));
        switch (rng.nextInt(10)) {
            case 0 -> mongo.queryByPrimaryKey(rec.id());
            case 1 -> mongo.queryLatestPriorValue(rec.id());
            case 2 -> mongo.queryAddressByType(rec.id(), QUERY_ADDR_TYPE);
            case 3 -> mongo.queryBySsn(DataGenerator.randomSsn(rng));
            case 4 -> mongo.queryByLastNameAndDob(rec.lastName(), rec.dob());
            case 5 -> mongo.queryByGlobalPid(rec.globalPid());
            case 6 -> mongo.queryByRuleId(DataGenerator.randomRuleId(rng));
            case 7 -> mongo.queryMasteredLast30Days(cutoff30);
            case 8 -> mongo.queryPriorSsn(DataGenerator.randomSsn(rng));
            default -> mongo.queryMasteredBetween(rangeStart, rangeEnd);
        }
    }

    // ── UPDATE dispatch ───────────────────────────────────────────────

    private long dispatchPgUpdate(Random rng) throws Exception {
        String id = updateSampleIds.get(rng.nextInt(updateSampleIds.size()));
        if (rng.nextBoolean()) {
            return pg.updateSsnById(id, UPDATE_SSN);
        } else {
            return pg.appendDemographicHistory(id, SAMPLE_HISTORY);
        }
    }

    private long dispatchMongoUpdate(Random rng) {
        String id = updateSampleIds.get(rng.nextInt(updateSampleIds.size()));
        if (rng.nextBoolean()) {
            return mongo.updateSsnById(id, UPDATE_SSN);
        } else {
            return mongo.appendDemographicHistory(id, SAMPLE_HISTORY);
        }
    }

    // ------------------------------------------------------------------
    // Timing wrappers (for SELECTs — UPDATEs/INSERTs are timed in repos)
    // ------------------------------------------------------------------

    @FunctionalInterface
    interface PgOp { void run() throws Exception; }

    @FunctionalInterface
    interface MongoOp { void run(); }

    private static long timePg(PgOp op) throws Exception {
        long t0 = System.nanoTime();
        op.run();
        return System.nanoTime() - t0;
    }

    private static long timeMongo(MongoOp op) {
        long t0 = System.nanoTime();
        op.run();
        return System.nanoTime() - t0;
    }

    // ------------------------------------------------------------------
    // Result construction
    // ------------------------------------------------------------------

    private static StressTestResult toResult(String db, long ops, long errors,
                                             double duration,
                                             ConcurrentLinkedQueue<Long> latencyQueue) {
        long[] arr = new long[latencyQueue.size()];
        int i = 0;
        for (Long l : latencyQueue) {
            if (i < arr.length) arr[i++] = l;
        }
        // trim to actual written entries
        if (i < arr.length) {
            long[] trimmed = new long[i];
            System.arraycopy(arr, 0, trimmed, 0, i);
            arr = trimmed;
        }
        return new StressTestResult(db, ops, errors, duration, arr);
    }
}
