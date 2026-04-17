package com.gsv.benchmark.data;

/**
 * A lightweight snapshot of one seeded record used as a randomised query parameter
 * during the concurrent stress test.
 *
 * <p>A pool of these is loaded once after seeding (via
 * {@code PostgresRepository.loadSamplePool()}) and then picked at random by each
 * stress-test worker thread on every SELECT operation, so that no single record
 * dominates the buffer cache.
 */
public record SampleRecord(
        String id,          // "global_pid|global_cid" — used by Q1, Q2, Q3
        String globalPid,   // used by Q6
        String lastName,    // used by Q5
        String dob          // used by Q5
) {}
