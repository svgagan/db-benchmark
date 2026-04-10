package com.gsv.benchmark.benchmark;

import com.gsv.benchmark.mongodb.MongoRepository;
import com.gsv.benchmark.postgres.PostgresRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Orchestrates all benchmark queries.
 * <p>
 * For each query it:
 * <ol>
 *   <li>Runs {@code warmupIterations} executions (results discarded).</li>
 *   <li>Runs {@code measureIterations} executions, recording wall-clock latency
 *       per iteration using {@link System#nanoTime()}.</li>
 *   <li>Wraps results in a {@link BenchmarkResult}.</li>
 * </ol>
 */
public class BenchmarkRunner {

    private final PostgresRepository pg;
    private final MongoRepository    mongo;
    private final int warmupIterations;
    private final int measureIterations;

    public BenchmarkRunner(PostgresRepository pg, MongoRepository mongo,
                           int warmupIterations, int measureIterations) {
        this.pg                 = pg;
        this.mongo              = mongo;
        this.warmupIterations   = warmupIterations;
        this.measureIterations  = measureIterations;
    }

    /**
     * Runs all 9 queries against both databases.
     *
     * @param indexed {@code true} if indexes have already been created
     * @return one {@link BenchmarkResult} per (query, database) pair — 18 results total
     */
    public List<BenchmarkResult> runAll(boolean indexed) throws Exception {
        List<BenchmarkResult> results = new ArrayList<>();

        // ---- Golden record queries (users) ----

        results.add(measure("Q1  equality: subscription_tier='premium'", "PostgreSQL", indexed,
                () -> pg.queryEqualitySubscriptionTier("premium")));
        results.add(measure("Q1  equality: subscription_tier='premium'", "MongoDB", indexed,
                () -> mongo.queryEqualitySubscriptionTier("premium")));

        results.add(measure("Q2  range: age BETWEEN 25 AND 40", "PostgreSQL", indexed,
                () -> pg.queryRangeAge(25, 40)));
        results.add(measure("Q2  range: age BETWEEN 25 AND 40", "MongoDB", indexed,
                () -> mongo.queryRangeAge(25, 40)));

        results.add(measure("Q3  range: billing.account_balance > 500", "PostgreSQL", indexed,
                () -> pg.queryRangeBalance(500)));
        results.add(measure("Q3  range: billing.account_balance > 500", "MongoDB", indexed,
                () -> mongo.queryRangeBalance(500)));

        results.add(measure("Q4  nested: address.city = 'New York'", "PostgreSQL", indexed,
                () -> pg.queryNestedCity("New York")));
        results.add(measure("Q4  nested: address.city = 'New York'", "MongoDB", indexed,
                () -> mongo.queryNestedCity("New York")));

        results.add(measure("Q5  array: tags @> 'verified'", "PostgreSQL", indexed,
                () -> pg.queryArrayTags("verified")));
        results.add(measure("Q5  array: tags @> 'verified'", "MongoDB", indexed,
                () -> mongo.queryArrayTags("verified")));

        results.add(measure("Q6  array: interests @> 'travel'", "PostgreSQL", indexed,
                () -> pg.queryArrayInterests("travel")));
        results.add(measure("Q6  array: interests @> 'travel'", "MongoDB", indexed,
                () -> mongo.queryArrayInterests("travel")));

        // ---- Audit log queries ----

        results.add(measure("Q7  audit: fetch history by user_id", "PostgreSQL", indexed,
                () -> pg.queryAuditByUser()));
        results.add(measure("Q7  audit: fetch history by user_id", "MongoDB", indexed,
                () -> mongo.queryAuditByUser()));

        results.add(measure("Q8  audit: ever on subscription_tier='basic'", "PostgreSQL", indexed,
                () -> pg.queryAuditEverOnTier("basic")));
        results.add(measure("Q8  audit: ever on subscription_tier='basic'", "MongoDB", indexed,
                () -> mongo.queryAuditEverOnTier("basic")));

        results.add(measure("Q9  audit: credit_score ever > 700", "PostgreSQL", indexed,
                () -> pg.queryAuditCreditScoreExceeds(700)));
        results.add(measure("Q9  audit: credit_score ever > 700", "MongoDB", indexed,
                () -> mongo.queryAuditCreditScoreExceeds(700)));

        return results;
    }

    // ------------------------------------------------------------------
    // Internal timing harness
    // ------------------------------------------------------------------

    private BenchmarkResult measure(String queryName, String database, boolean indexed,
                                    Callable<Long> query) throws Exception {
        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            query.call();
        }

        long resultCount = 0;
        long[] latencies = new long[measureIterations];
        for (int i = 0; i < measureIterations; i++) {
            long t0 = System.nanoTime();
            long count = query.call();
            latencies[i] = System.nanoTime() - t0;
            if (i == 0) resultCount = count;    // capture result count from first measured run
        }

        return new BenchmarkResult(queryName, database, indexed, latencies, resultCount);
    }
}
