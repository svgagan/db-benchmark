package com.gsv.benchmark;

import com.gsv.benchmark.benchmark.BenchmarkResult;
import com.gsv.benchmark.benchmark.BenchmarkRunner;
import com.gsv.benchmark.benchmark.ResultPrinter;
import com.gsv.benchmark.benchmark.StressTestResult;
import com.gsv.benchmark.benchmark.StressTestRunner;
import com.gsv.benchmark.config.AppConfig;
import com.gsv.benchmark.data.DataGenerator;
import com.gsv.benchmark.data.SampleRecord;
import com.gsv.benchmark.model.GoldenPerson;
import com.gsv.benchmark.mongodb.MongoRepository;
import com.gsv.benchmark.postgres.PostgresRepository;
import com.zaxxer.hikari.HikariDataSource;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Entry point for the MDM Golden Record benchmark.
 *
 * <p>Compares Amazon RDS PostgreSQL (JSONB) against Amazon DocumentDB
 * (MongoDB-compatible) using an MDM-style golden person schema with
 * demographic data and address arrays — each with full prior_values history.
 *
 * <h3>8-step workflow</h3>
 * <ol>
 *   <li>Generate INSERT pool (in-memory, used for Q11/Q12 and stress INSERTs)</li>
 *   <li>Seed both databases in parallel ({@code seed-threads} × chunked generation)</li>
 *   <li>Run benchmarks WITHOUT indexes (Q1–Q14)</li>
 *   <li>Create indexes on both databases</li>
 *   <li>Run benchmarks WITH indexes (Q1–Q14)</li>
 *   <li>Load UPDATE sample IDs for stress test</li>
 *   <li>Run concurrent stress test ({@code concurrent-users} threads × {@code stress-duration-seconds})</li>
 *   <li>Print all results</li>
 * </ol>
 *
 * <h3>Profile selection</h3>
 * <ol>
 *   <li>Environment variable: {@code BENCHMARK_PROFILE=prod}</li>
 *   <li>Default: {@code local} (Docker Compose)</li>
 * </ol>
 *
 * <h3>Local usage</h3>
 * <pre>
 *   docker compose up -d
 *   mvn package -q
 *   java -jar target/db-benchmark-1.0.0.jar
 * </pre>
 *
 * <h3>Scaling down for quick tests</h3>
 * <pre>
 *   BENCHMARK_RECORD_COUNT=100000 \
 *   BENCHMARK_CONCURRENT_USERS=5 \
 *   BENCHMARK_STRESS_DURATION_SECONDS=30 \
 *   java -jar target/db-benchmark-1.0.0.jar
 * </pre>
 */
public class Main {

    public static void main(String[] args) throws Exception {

        AppConfig config = AppConfig.load();

        banner();
        System.out.printf("Profile      : %s%n", config.getProfile());
        System.out.printf("Records      : %,d%n", config.getRecordCount());
        System.out.printf("Seed threads : %d (chunk %,d)%n",
                config.getSeedThreads(), config.getSeedChunkSize());
        System.out.printf("Warmup       : %d iterations%n", config.getWarmup());
        System.out.printf("Measure      : %d iterations%n", config.getIterations());
        System.out.printf("Stress       : %d threads × %ds  (SELECT %d%% / INSERT %d%% / UPDATE %d%%)%n%n",
                config.getConcurrentUsers(), config.getStressDurationSec(),
                config.getStressSelectPct(), config.getStressInsertPct(),
                100 - config.getStressSelectPct() - config.getStressInsertPct());

        // ── 1/8 — Generate INSERT pool ─────────────────────────────────────
        System.out.printf("[ 1/8 ] Generating INSERT pool (%,d records in memory)...%n",
                config.getInsertPoolSize());
        long t0 = System.currentTimeMillis();
        List<GoldenPerson> insertPool = DataGenerator.generateInsertPool(config.getInsertPoolSize());
        System.out.printf("        Done in %,d ms%n%n", System.currentTimeMillis() - t0);

        // ── Open DB connections (HikariCP pool + MongoClient) ──────────────
        try (HikariDataSource    pgDataSource = config.buildPostgresDataSource();
             MongoRepository     mongo        = new MongoRepository(config.buildMongoClient(), config.getMongoDb())) {

            PostgresRepository pg = new PostgresRepository(pgDataSource, config.getPgSchema());

            // ── Schema / collection setup ──────────────────────────────────
            System.out.println("        Dropping and recreating schema / collections...");
            pg.dropAndCreateTables();
            mongo.dropAndCreateCollections();
            System.out.println();

            // ── 2/8 — Parallel seeding ─────────────────────────────────────
            System.out.printf("[ 2/8 ] Seeding %,d records using %d threads...%n",
                    config.getRecordCount(), config.getSeedThreads());
            seedInParallel(config, pg, mongo);
            System.out.println();

            // Load sample values for benchmark query parameters
            System.out.println("        Loading sample values...");
            pg.loadSampleValues();
            mongo.loadSampleValues();
            System.out.println();

            // ── 3/8 — Load query parameter pools ──────────────────────────
            System.out.println("[ 3/8 ] Loading query parameter pools...");
            List<String>       updateSampleIds = pg.loadUpdateSampleIds(1_000);
            List<SampleRecord> samplePool      = pg.loadSamplePool(500);
            System.out.printf("        %,d update IDs · %,d sample records loaded.%n%n",
                    updateSampleIds.size(), samplePool.size());

            // ── 4/8 — Benchmark WITHOUT indexes ───────────────────────────
            System.out.println("[ 4/8 ] Running benchmarks WITHOUT indexes (Q1–Q14)...");
            BenchmarkRunner runner = new BenchmarkRunner(
                    pg, mongo,
                    config.getWarmup(), config.getIterations(),
                    insertPool, updateSampleIds);
            List<BenchmarkResult> noIndexResults = runner.runAll(false);
            System.out.println("        Done.");
            System.out.println();

            // ── 5/8 — Create indexes ───────────────────────────────────────
            System.out.println("[ 5/8 ] Creating indexes...");
            pg.createIndexes();
            mongo.createIndexes();
            System.out.println();

            // ── 6/8 — Benchmark WITH indexes ──────────────────────────────
            System.out.println("[ 6/8 ] Running benchmarks WITH indexes (Q1–Q14)...");
            List<BenchmarkResult> indexedResults = runner.runAll(true);
            System.out.println("        Done.");
            System.out.println();

            // ── 7/8 — Concurrent stress test ──────────────────────────────
            System.out.printf("[ 7/8 ] Running concurrent stress test (%d threads × %ds)...%n",
                    config.getConcurrentUsers(), config.getStressDurationSec());
            StressTestRunner stressRunner = new StressTestRunner(
                    pg, mongo,
                    insertPool, updateSampleIds, samplePool,
                    config.getConcurrentUsers(),
                    config.getStressDurationSec(),
                    config.getStressSelectPct(),
                    config.getStressInsertPct());
            StressTestResult[] stressResults = stressRunner.run();
            System.out.println("        Done.");
            System.out.println();

            // ── 8/8 — Print results ────────────────────────────────────────
            System.out.println("[ 8/8 ] Results:");
            ResultPrinter.print(noIndexResults, indexedResults);
            ResultPrinter.printStressResults(stressResults[0], stressResults[1]);
        }
    }

    // ------------------------------------------------------------------
    // Parallel seeding
    // ------------------------------------------------------------------

    /**
     * Seeds {@code config.getRecordCount()} records into both PostgreSQL and MongoDB
     * using {@code config.getSeedThreads()} parallel threads.
     *
     * <p>Each thread owns a distinct {@link DataGenerator} instance (seeded with
     * {@code 42 + threadIndex}) to avoid shared-mutable-state between threads.
     * Each thread processes its slice in chunks of {@code config.getSeedChunkSize()},
     * keeping heap usage bounded to {@code seedThreads × chunkSize} records at any time.
     *
     * <p>A progress reporter thread prints throughput every 5 seconds.
     */
    private static void seedInParallel(AppConfig config,
                                       PostgresRepository pg,
                                       MongoRepository    mongo) throws Exception {

        int totalRecords  = config.getRecordCount();
        int seedThreads   = config.getSeedThreads();
        int chunkSize     = config.getSeedChunkSize();
        int pgBatchSize   = config.getPgSeedBatchSize();
        int mongoBatchSize= config.getMongoSeedBatchSize();

        int sliceSize     = totalRecords / seedThreads;
        int remainder     = totalRecords % seedThreads;  // thread 0 picks up the extra

        AtomicLong seededCount  = new AtomicLong(0);
        CountDownLatch done     = new CountDownLatch(seedThreads);

        ExecutorService pool = Executors.newFixedThreadPool(seedThreads);
        long seedStart = System.currentTimeMillis();

        for (int t = 0; t < seedThreads; t++) {
            final int threadIdx = t;
            final int mySlice   = sliceSize + (threadIdx == 0 ? remainder : 0);

            pool.submit(() -> {
                DataGenerator gen = new DataGenerator(42L + threadIdx);
                try {
                    gen.generatePersonsChunked(mySlice, chunkSize, chunk -> {
                        try {
                            pg.seedChunk(chunk, pgBatchSize);
                            mongo.seedChunk(chunk, mongoBatchSize);
                            seededCount.addAndGet(chunk.size());
                        } catch (Exception e) {
                            throw new RuntimeException("Seeding chunk failed", e);
                        }
                    });
                } finally {
                    done.countDown();
                }
            });
        }

        // Progress reporter — prints every 5 seconds
        Thread reporter = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(5_000);
                    long n = seededCount.get();
                    long elapsed = System.currentTimeMillis() - seedStart;
                    double rate = elapsed > 0 ? n / (elapsed / 1000.0) : 0;
                    System.out.printf("        Seeded %,d / %,d records  (%.0f rec/s)%n",
                            n, totalRecords, rate);
                    if (n >= totalRecords) break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        reporter.setDaemon(true);
        reporter.start();

        pool.shutdown();
        done.await();
        reporter.interrupt();

        long elapsed = System.currentTimeMillis() - seedStart;
        double rate  = elapsed > 0 ? totalRecords / (elapsed / 1000.0) : 0;
        System.out.printf("        Seeded %,d records in %,d ms  (%.0f rec/s)%n",
                totalRecords, elapsed, rate);
    }

    // ------------------------------------------------------------------

    private static void banner() {
        System.out.println("╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║   MDM Golden Record Benchmark  —  PostgreSQL JSONB vs MongoDB  ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
