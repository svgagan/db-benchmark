package com.gsv.benchmark;

import com.gsv.benchmark.benchmark.BenchmarkResult;
import com.gsv.benchmark.benchmark.BenchmarkRunner;
import com.gsv.benchmark.benchmark.ResultPrinter;
import com.gsv.benchmark.config.AppConfig;
import com.gsv.benchmark.data.DataGenerator;
import com.gsv.benchmark.model.GoldenPerson;
import com.gsv.benchmark.mongodb.MongoRepository;
import com.gsv.benchmark.postgres.PostgresRepository;

import java.util.List;

/**
 * Entry point for the MDM Golden Record benchmark POC.
 *
 * <p>Compares Amazon RDS PostgreSQL (JSONB) against Amazon DocumentDB
 * (MongoDB-compatible) using an MDM-style golden person schema with:
 * <ul>
 *   <li>Demographic data (first_name, last_name, SSN, DOB) with prior_values history</li>
 *   <li>Address array (PRIMARY / SECONDARY / BILLING) with prior_values history</li>
 * </ul>
 *
 * <h3>Profile selection (priority order)</h3>
 * <ol>
 *   <li>JVM system property: {@code -Dbenchmark.profile=prod}</li>
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
 * <h3>Prod usage (on EC2 / ECS with IAM role attached)</h3>
 * <pre>
 *   export BENCHMARK_PROFILE=prod
 *   export PG_HOST=mydb.xxxx.us-east-1.rds.amazonaws.com
 *   export PG_USER=bench_iam_user
 *   export AWS_REGION=us-east-1
 *   export MONGO_HOST=mydb.xxxx.us-east-1.docdb.amazonaws.com
 *   export MONGO_USER=docdbuser
 *   export MONGO_PASSWORD=secret
 *   java -jar target/db-benchmark-1.0.0.jar
 * </pre>
 *
 * <h3>Workflow</h3>
 * <ol>
 *   <li>Generate 100 k synthetic MDM golden person records in memory.</li>
 *   <li>Seed both databases (single collection / table — no separate audit log).</li>
 *   <li>Run all 9 benchmark queries — "no index" baseline.</li>
 *   <li>Create indexes on both databases.</li>
 *   <li>Run all 9 benchmark queries again — "indexed" run.</li>
 *   <li>Print formatted comparison table with speedup ratios.</li>
 * </ol>
 */
public class Main {

    public static void main(String[] args) throws Exception {

        AppConfig config = AppConfig.load();

        banner();
        System.out.printf("Profile    : %s%n", config.getProfile());
        System.out.printf("Records    : %,d%n", config.getUserCount());
        System.out.printf("Warmup     : %d iterations%n", config.getWarmup());
        System.out.printf("Measure    : %d iterations%n%n", config.getIterations());

        // ── 1. Generate data ───────────────────────────────────────────
        System.out.println("[ 1/6 ] Generating MDM golden person records...");
        long t0 = System.currentTimeMillis();
        List<GoldenPerson> persons = DataGenerator.generatePersons(config.getUserCount());
        System.out.printf("        Done in %,d ms (%,d records generated)%n%n",
                System.currentTimeMillis() - t0, persons.size());

        // ── 2. Open DB connections ─────────────────────────────────────
        try (PostgresRepository pg    = new PostgresRepository(config.buildPostgresConnection());
             MongoRepository    mongo = new MongoRepository(config.buildMongoClient(), config.getMongoDb())) {

            // ── 3. Schema setup ────────────────────────────────────────
            System.out.println("[ 2/6 ] Dropping and recreating schema / collections...");
            pg.dropAndCreateTables();
            mongo.dropAndCreateCollections();
            System.out.println();

            // ── 4. Seed data ───────────────────────────────────────────
            System.out.println("[ 3/6 ] Seeding PostgreSQL (mdm_golden_record)...");
            pg.seedPersons(persons);
            System.out.println();

            System.out.println("[ 3/6 ] Seeding MongoDB (mdm_golden_person)...");
            mongo.seedPersons(persons);
            System.out.println();

            // ── 5. Benchmark WITHOUT indexes ───────────────────────────
            System.out.println("[ 4/6 ] Running benchmarks WITHOUT indexes...");
            BenchmarkRunner runner = new BenchmarkRunner(
                    pg, mongo, config.getWarmup(), config.getIterations());
            List<BenchmarkResult> noIndexResults = runner.runAll(false);
            System.out.println("        Done.");
            System.out.println();

            // ── 6. Create indexes ──────────────────────────────────────
            System.out.println("[ 5/6 ] Creating indexes...");
            pg.createIndexes();
            mongo.createIndexes();
            System.out.println();

            // ── 7. Benchmark WITH indexes ──────────────────────────────
            System.out.println("[ 6/6 ] Running benchmarks WITH indexes...");
            List<BenchmarkResult> indexedResults = runner.runAll(true);
            System.out.println("        Done.");

            // ── 8. Print results ───────────────────────────────────────
            ResultPrinter.print(noIndexResults, indexedResults);
        }
    }

    // ------------------------------------------------------------------

    private static void banner() {
        System.out.println("╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║   MDM Golden Record Benchmark  —  PostgreSQL JSONB vs MongoDB  ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
