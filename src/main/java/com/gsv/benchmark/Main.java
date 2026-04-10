package com.gsv.benchmark;

import com.gsv.benchmark.benchmark.BenchmarkResult;
import com.gsv.benchmark.benchmark.BenchmarkRunner;
import com.gsv.benchmark.benchmark.ResultPrinter;
import com.gsv.benchmark.config.AppConfig;
import com.gsv.benchmark.data.DataGenerator;
import com.gsv.benchmark.model.AuditLog;
import com.gsv.benchmark.model.UserProfile;
import com.gsv.benchmark.mongodb.MongoRepository;
import com.gsv.benchmark.postgres.PostgresRepository;

import java.util.List;

/**
 * Entry point for the PostgreSQL JSONB vs MongoDB query benchmark POC.
 *
 * <h3>Profile selection</h3>
 * Set the {@code BENCHMARK_PROFILE} environment variable before running:
 * <ul>
 *   <li>{@code local} (default) — Docker Compose PostgreSQL + MongoDB,
 *       plain username/password, no SSL.</li>
 *   <li>{@code prod} — Amazon RDS PostgreSQL (IAM auth + SSL) +
 *       Amazon DocumentDB (TLS + env-var credentials).</li>
 * </ul>
 *
 * <h3>Local usage</h3>
 * <pre>
 *   docker compose up -d
 *   java -jar target/db-benchmark-1.0.0.jar
 * </pre>
 *
 * <h3>Prod usage</h3>
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
 *   <li>Generate synthetic data in memory.</li>
 *   <li>Seed both databases.</li>
 *   <li>Run all 9 benchmark queries — "no index" baseline.</li>
 *   <li>Create indexes on both databases.</li>
 *   <li>Run all 9 benchmark queries again — "indexed" run.</li>
 *   <li>Print formatted comparison table.</li>
 * </ol>
 */
public class Main {

    public static void main(String[] args) throws Exception {

        // ---- Load profile-aware configuration ----
        AppConfig config = AppConfig.load();

        banner(config);
        System.out.printf("Profile    : %s%n", config.getProfile());
        System.out.printf("Users      : %,d%n", config.getUserCount());
        System.out.printf("Warmup     : %d iterations%n", config.getWarmup());
        System.out.printf("Measure    : %d iterations%n%n", config.getIterations());

        // ---- Data generation ----
        System.out.println("[ 1/6 ] Generating data...");
        long t0 = System.currentTimeMillis();
        List<UserProfile> users     = DataGenerator.generateUsers(config.getUserCount());
        List<AuditLog>    auditLogs = DataGenerator.generateAuditLogs(users);
        System.out.printf("        Done in %,d ms (%,d users, %,d audit logs)%n%n",
                System.currentTimeMillis() - t0, users.size(), auditLogs.size());

        // ---- Open connections (profile-specific) ----
        try (PostgresRepository pg    = new PostgresRepository(config.buildPostgresConnection());
             MongoRepository    mongo = new MongoRepository(config.buildMongoClient(), config.getMongoDb())) {

            // ---- Phase 1: schema setup ----
            System.out.println("[ 2/6 ] Dropping and recreating tables/collections...");
            pg.dropAndCreateTables();
            mongo.dropAndCreateCollections();
            System.out.println();

            // ---- Phase 2: seeding ----
            System.out.println("[ 3/6 ] Seeding PostgreSQL...");
            pg.seedUsers(users);
            pg.seedAuditLogs(auditLogs);
            System.out.println();

            System.out.println("[ 3/6 ] Seeding MongoDB...");
            mongo.seedUsers(users);
            mongo.seedAuditLogs(auditLogs);
            System.out.println();

            // ---- Phase 3: benchmark WITHOUT indexes ----
            System.out.println("[ 4/6 ] Running benchmarks WITHOUT indexes...");
            BenchmarkRunner runner = new BenchmarkRunner(pg, mongo,
                    config.getWarmup(), config.getIterations());
            List<BenchmarkResult> noIndexResults = runner.runAll(false);
            System.out.println("        Done.");
            System.out.println();

            // ---- Phase 4: create indexes ----
            System.out.println("[ 5/6 ] Creating indexes...");
            pg.createIndexes();
            mongo.createIndexes();
            System.out.println();

            // ---- Phase 5: benchmark WITH indexes ----
            System.out.println("[ 6/6 ] Running benchmarks WITH indexes...");
            List<BenchmarkResult> indexedResults = runner.runAll(true);
            System.out.println("        Done.");

            // ---- Results ----
            ResultPrinter.print(noIndexResults, indexedResults);
        }
    }

    // ------------------------------------------------------------------

    private static void banner(AppConfig config) {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║     PostgreSQL JSONB  vs  MongoDB  —  Query Benchmark    ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
