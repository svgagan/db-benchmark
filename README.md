# MDM Golden Record Benchmark — PostgreSQL JSONB vs MongoDB

A self-contained Java benchmark comparing **Amazon RDS PostgreSQL (JSONB)**
and **Amazon DocumentDB (MongoDB-compatible)** on realistic MDM
(Master Data Management) workloads — including SELECT, INSERT, UPDATE, and
concurrent mixed-load stress testing at 1 million record scale.

---

## Overview

| Dimension | Value |
|-----------|-------|
| Dataset | **1,000,000** MDM golden person records (configurable) |
| Seeding | **4 parallel threads**, chunked to keep heap usage bounded |
| SELECT queries | **15 patterns** (Q1–Q15), 500 iterations each after 5 warmup rounds |
| Write benchmarks | Q11 single INSERT · Q12 batch INSERT (100 records) · Q13 scalar UPDATE · Q14 array APPEND |
| TOAST test | Q15 scalar projection vs Q1 full-record fetch — measures PostgreSQL TOAST overhead |
| Stress test | **20 concurrent threads** × 60 seconds · mixed 60 % SELECT / 25 % INSERT / 15 % UPDATE |
| Latency reported | Avg / P50 / P95 / P99 per query per database |
| Authentication | Username + password for both RDS and DocumentDB (TLS enforced on prod) |

---

## 8-Step Workflow

```
[ 1/8 ]  Generate INSERT pool          50,000 records held in memory for write benchmarks
[ 2/8 ]  Parallel seeding              4 threads × chunked generation → both databases
[ 3/8 ]  Load query parameter pools    1,000 update IDs · 500 random sample records
[ 4/8 ]  Benchmark WITHOUT indexes     Q1–Q15 sequential baseline
[ 5/8 ]  Create indexes                PG expression/GIN indexes · Mongo compound/multikey indexes
[ 6/8 ]  Benchmark WITH indexes        Q1–Q15 indexed pass
[ 7/8 ]  Concurrent stress test        20 threads × 60s · randomised mixed workload
[ 8/8 ]  Print results                 Per-query latency tables + stress test throughput
```

---

## Data Model

### DocumentDB — `benchmark` database · `mdm_golden_person` collection

```json
{
  "_id": "global_pid|global_cid",
  "global_pid": "3fa85f64-...",
  "global_cid": "7cb12e91-...",
  "mastered_date_ts": "2025-06-14T08:30:00Z",
  "demographic": {
    "first_name": "James",  "last_name": "Smith",
    "date_of_birth": "1985-03-22",  "ssn": "234-56-7890",
    "mastered_date_ts": "2025-06-14T08:30:00Z",
    "rule_id": "RULE_003",  "rule_version": "v2.0",
    "prior_values": [
      {
        "first_name": "Jim",  "last_name": "Smith",
        "date_of_birth": "1985-03-22",  "ssn": "234-56-0001",
        "mastered_date_ts": "2024-12-01T10:00:00Z",
        "rule_id": "RULE_001",  "rule_version": "v1.0",
        "lost_against_rule": "RULE_003"
      }
    ]
  },
  "address": [
    {
      "address_type": "PRIMARY",
      "address1": "4821 Main St",  "city": "New York",  "state": "NY",
      "zipcode": "10001",  "country": "US",
      "mastered_date_ts": "2025-06-14T08:30:00Z",
      "rule_id": "RULE_003",  "rule_version": "v2.0",
      "prior_values": [ { "address_type": "PRIMARY", "city": "Chicago", ... } ]
    }
  ]
}
```

### PostgreSQL — `core_data_db` database · `benchmark.mdm_golden_record` table

| Column | Type | Notes |
|--------|------|-------|
| `id` | `TEXT PRIMARY KEY` | `"global_pid\|global_cid"` |
| `global_pid` | `TEXT NOT NULL` | Indexed for Q6 |
| `global_cid` | `TEXT NOT NULL` | |
| `mastered_date_ts` | `TIMESTAMPTZ NOT NULL` | Indexed for Q8, Q10 |
| `created_date` | `TIMESTAMPTZ DEFAULT now()` | |
| `updated_date` | `TIMESTAMPTZ DEFAULT now()` | Updated by Q13/Q14 |
| `golden_record` | `JSONB NOT NULL` | Full document (demographic + address arrays) |

---

## Benchmark Queries

### SELECT benchmarks (Q1–Q10)

| # | Description | PostgreSQL | MongoDB |
|---|-------------|-----------|---------|
| Q1 | Composite PK fetch (full record) | `WHERE id = ?` | `find({_id: ?})` |
| Q2 | Latest prior_values entry (previous winner) | `jsonb_path_query_first(…, '[last]')` | `find({_id:?}, {$slice:-1})` |
| Q3 | Address by type for known person | `jsonb_path_query_first(…, '@.address_type == $t')` | `$elemMatch` projection |
| Q4 | `demographic.ssn` exact match | Expression B-tree index | B-tree on `demographic.ssn` |
| Q5 | `last_name` + `date_of_birth` compound | Compound expression B-tree | Compound index |
| Q6 | `global_pid` only (partial key) | B-tree on relational column | B-tree on `global_pid` |
| Q7 | `demographic.rule_id` lookup | Expression B-tree index | B-tree on `demographic.rule_id` |
| Q8 | Mastered in last 30 days | `TIMESTAMPTZ` B-tree range | ISO-8601 string B-tree range |
| Q9 | Prior SSN history search | `jsonb_path_exists` + GIN ⚠️ | Multikey index on `prior_values.ssn` |
| Q10 | Mastered between two timestamps | `TIMESTAMPTZ` range | ISO-8601 string range |

### TOAST pressure test (Q15)

| # | Description | PostgreSQL | MongoDB |
|---|-------------|-----------|---------|
| Q15 | Scalar projection — 4 demographic fields only | `SELECT golden_record->'demographic'->>'ssn', …` (no full blob) | `find({_id:?}, {demographic.ssn:1, …})` |

> **Q15 vs Q1 delta reveals PostgreSQL TOAST overhead.**  
> A fully populated golden record (2–3 prior_values + 2–3 addresses) can reach 3–6 KB,
> exceeding PostgreSQL's ~2 KB in-line threshold. Q1 must chase an out-of-line TOAST
> pointer (second I/O); Q15 returns only extracted scalar values, avoiding it.  
> MongoDB has no TOAST equivalent — WiredTiger reads the full document regardless
> of projection; Q15 vs Q1 measures only network payload reduction.

### Write benchmarks (Q11–Q14)

| # | Description | PostgreSQL | MongoDB |
|---|-------------|-----------|---------|
| Q11 | Single INSERT latency | One-record `executeBatch` | `insertOne` |
| Q12 | Batch INSERT (100 records) | 100-record JDBC batch | `insertMany(ordered:false)` |
| Q13 | SSN point UPDATE | `jsonb_set(golden_record, '{demographic,ssn}', to_jsonb(?))` | `$set: {"demographic.ssn": ?}` |
| Q14 | prior_values array APPEND | `jsonb_set(…, prior_values \|\| ?::jsonb)` — rewrites full blob | `$push: {"demographic.prior_values": ?}` — in-place |

> **Q14 write amplification note.**  
> PostgreSQL cannot update JSONB in-place — every Q14 rewrites the entire `golden_record`
> blob and creates a dead tuple, adding autovacuum pressure under high UPDATE rates.
> MongoDB's `$push` operates in-place on the WiredTiger B-tree, with no dead-tuple accumulation.

### GIN index note (Q9)

PostgreSQL's GIN `jsonb_path_ops` index gives **no benefit** for doubly-nested
`prior_values[*].ssn` queries — the JSONPath predicate `@.ssn == $v` inside an array
cannot be resolved via the path-hash-based GIN index structure. Q9 remains a full scan
on PostgreSQL. MongoDB's multikey index on `demographic.prior_values.ssn` handles this natively.

---

## Stress Test

The concurrent stress test fires **N threads against each database simultaneously**
using a shared `CountDownLatch` start gate.

```
CountDownLatch.countDown()
      │
      ├── 20 PG threads    each loop: pick random op → execute → record latency
      └── 20 Mongo threads each loop: pick random op → execute → record latency
```

**Operation mix per thread (configurable):**

| Operation | Default % | What runs |
|-----------|-----------|-----------|
| SELECT | 60 % | Random query from Q1–Q10 |
| INSERT | 25 % | Single INSERT from pre-generated pool (`withNewId()` clone) |
| UPDATE | 15 % | Randomly picks Q13 (scalar update) or Q14 (array append) |

**Randomisation per SELECT operation** — prevents buffer-cache bias:

| Query | Parameter source |
|-------|-----------------|
| Q1 / Q2 / Q3 | Random `id` from 500-record pool loaded after seeding |
| Q4 / Q9 | Random SSN from the 1,000-entry `SSN_POOL` |
| Q5 | Random `(last_name, dob)` from pool |
| Q6 | Random `global_pid` from pool |
| Q7 | Random rule ID (`RULE_001`…`RULE_010`) |
| Q8 / Q10 | Fixed time-range (no record dependency) |

---

## Indexes

### PostgreSQL (`benchmark.mdm_golden_record`)

```sql
-- Relational column indexes
CREATE INDEX idx_mgr_global_pid  ON benchmark.mdm_golden_record (global_pid);         -- Q6
CREATE INDEX idx_mgr_mastered_ts ON benchmark.mdm_golden_record (mastered_date_ts);   -- Q8, Q10

-- GIN: jsonb_path_exists and @> containment — Q3, Q9 (see Q9 note above)
CREATE INDEX idx_mgr_gin ON benchmark.mdm_golden_record
    USING GIN (golden_record jsonb_path_ops);

-- Expression indexes on scalar JSONB fields
CREATE INDEX idx_mgr_ssn     ON benchmark.mdm_golden_record ((golden_record->'demographic'->>'ssn'));       -- Q4
CREATE INDEX idx_mgr_name_dob ON benchmark.mdm_golden_record                                               -- Q5
    ((golden_record->'demographic'->>'last_name'),
     (golden_record->'demographic'->>'date_of_birth'));
CREATE INDEX idx_mgr_rule_id ON benchmark.mdm_golden_record ((golden_record->'demographic'->>'rule_id'));   -- Q7
```

### MongoDB / DocumentDB (`benchmark.mdm_golden_person`)

```js
db.mdm_golden_person.createIndex({ "global_pid": 1 })                                            // Q6
db.mdm_golden_person.createIndex({ "mastered_date_ts": 1 })                                      // Q8, Q10
db.mdm_golden_person.createIndex({ "demographic.ssn": 1 })                                       // Q4
db.mdm_golden_person.createIndex({ "demographic.last_name": 1, "demographic.date_of_birth": 1 }) // Q5
db.mdm_golden_person.createIndex({ "demographic.rule_id": 1 })                                   // Q7
db.mdm_golden_person.createIndex({ "demographic.prior_values.ssn": 1 })                          // Q9 (multikey)
```

---

## Prerequisites

| Tool | Version | Required for |
|------|---------|-------------|
| Docker Desktop | any recent | local profile; prod image build |
| Java 17+ | 17+ | local JAR run only |
| Maven 3.8+ | 3.8+ | local build only |

> No Java or Maven needed for prod — Docker builds and runs everything inside the container.

---

## RDS User Setup (prod only)

Run once as the RDS **master user** before the first prod run.
The app creates the schema and table automatically; these grants give the benchmark user permission to do so.

```sql
-- 1. Create the benchmark user with a password
CREATE USER bench_user WITH PASSWORD 'your_strong_password';

-- 2. Allow connection to the database
GRANT CONNECT ON DATABASE core_data_db TO bench_user;

-- 3. Grant schema-level usage (schema is created by the app on first run)
GRANT CREATE, USAGE ON SCHEMA benchmark TO bench_user;

-- 4. Grant read/write on all current and future tables in the schema
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA benchmark TO bench_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA benchmark
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO bench_user;

-- 5. Grant sequence usage (needed for default timestamp columns)
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA benchmark TO bench_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA benchmark
    GRANT USAGE, SELECT ON SEQUENCES TO bench_user;
```

---

## Profiles

| Profile | Databases | Auth |
|---------|-----------|------|
| `local` | Docker Compose PostgreSQL + MongoDB | Plain username/password, no SSL |
| `prod` | Amazon RDS PostgreSQL + Amazon DocumentDB | Username/password · RDS: TLS (verify-full) · DocumentDB: TLS mandatory |

Profile is selected by the `BENCHMARK_PROFILE` environment variable (default: `local`).

---

## How to Run

### Local (Docker Compose)

```bash
# 1 — Start databases
docker compose up -d
docker compose ps     # wait until both show "healthy"

# 2 — Build
mvn package -q

# 3 — Run (profile defaults to 'local')
java -jar target/db-benchmark-1.0.0.jar
```

Scale down for a quick smoke test:
```bash
BENCHMARK_RECORD_COUNT=100000 \
BENCHMARK_CONCURRENT_USERS=5 \
BENCHMARK_STRESS_DURATION_SECONDS=30 \
java -jar target/db-benchmark-1.0.0.jar
```

Wipe data and start fresh:
```bash
docker compose down -v && docker compose up -d
```

---

### Prod (RDS + DocumentDB) — via Docker

No Java or Maven install needed on your laptop.

**Step 1 — Build the image**

```powershell
# Windows PowerShell
docker build -t db-benchmark .
```

```bash
# macOS / Linux
docker build -t db-benchmark .
```

If your corporate network requires a CA certificate:
```bash
# Copy cert to project root first, then:
docker build --build-arg CORPORATE_CA_CERT=corporate-ca.crt -t db-benchmark .
```

---

**Step 2 — Run**

PowerShell (Windows):
```powershell
docker run --rm `
  -e BENCHMARK_PROFILE=prod `
  -e PG_HOST=mydb.xxxx.us-east-1.rds.amazonaws.com `
  -e PG_USER=bench_user `
  -e PG_PASSWORD=your_strong_password `
  -e PG_SSL_CA_FILE=/certs/global-bundle.pem `
  -e MONGO_HOST=mydb.xxxx.us-east-1.docdb.amazonaws.com `
  -e MONGO_USER=docdbuser `
  -e MONGO_PASSWORD=your_mongo_password `
  -e MONGO_TLS_CA_FILE=/certs/global-bundle.pem `
  -v C:\path\to\global-bundle.pem:/certs/global-bundle.pem `
  db-benchmark
```

bash / macOS / Linux:
```bash
docker run --rm \
  -e BENCHMARK_PROFILE=prod \
  -e PG_HOST=mydb.xxxx.us-east-1.rds.amazonaws.com \
  -e PG_USER=bench_user \
  -e PG_PASSWORD=your_strong_password \
  -e PG_SSL_CA_FILE=/certs/global-bundle.pem \
  -e MONGO_HOST=mydb.xxxx.us-east-1.docdb.amazonaws.com \
  -e MONGO_USER=docdbuser \
  -e MONGO_PASSWORD=your_mongo_password \
  -e MONGO_TLS_CA_FILE=/certs/global-bundle.pem \
  -v /path/to/global-bundle.pem:/certs/global-bundle.pem \
  db-benchmark
```

> The RDS CA bundle (`global-bundle.pem`) can be downloaded from:  
> `https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem`  
> The same bundle covers both RDS and DocumentDB TLS verification.

---

## Configuration Reference

All values can be overridden by the corresponding environment variable
(`pg.host` → `PG_HOST`, `benchmark.record-count` → `BENCHMARK_RECORD_COUNT`, etc.).

### Connection settings

| Property | Local default | Prod default | Env var | Notes |
|----------|--------------|-------------|---------|-------|
| `pg.host` | `localhost` | *(required)* | `PG_HOST` | RDS endpoint |
| `pg.port` | `5432` | `5432` | `PG_PORT` | |
| `pg.db` | `core_data_db` | `core_data_db` | `PG_DB` | |
| `pg.schema` | `benchmark` | `benchmark` | `PG_SCHEMA` | Created automatically |
| `pg.user` | `bench` | *(required)* | `PG_USER` | |
| `pg.password` | `bench` | *(required)* | `PG_PASSWORD` | |
| `pg.ssl.mode` | *(none)* | `verify-full` | `PG_SSL_MODE` | Prod only |
| `pg.ssl.ca-file` | *(none)* | *(optional)* | `PG_SSL_CA_FILE` | Path to `global-bundle.pem` |
| `mongo.host` | `localhost` | *(required)* | `MONGO_HOST` | DocumentDB cluster endpoint |
| `mongo.port` | `27017` | `27017` | `MONGO_PORT` | |
| `mongo.db` | `benchmark` | `benchmark` | `MONGO_DB` | Created automatically |
| `mongo.user` | *(none)* | *(required)* | `MONGO_USER` | |
| `mongo.password` | *(none)* | *(required)* | `MONGO_PASSWORD` | |
| `mongo.tls.ca-file` | *(none)* | *(optional)* | `MONGO_TLS_CA_FILE` | Path to `global-bundle.pem` |

### Benchmark settings

| Property | Default | Env var | Notes |
|----------|---------|---------|-------|
| `benchmark.record-count` | `1000000` | `BENCHMARK_RECORD_COUNT` | Total records seeded |
| `benchmark.warmup` | `5` | `BENCHMARK_WARMUP` | Warmup iterations per query |
| `benchmark.iterations` | `500` | `BENCHMARK_ITERATIONS` | Measured iterations per query |
| `benchmark.seed-threads` | `4` | `BENCHMARK_SEED_THREADS` | Parallel seeding threads |
| `benchmark.seed-chunk-size` | `10000` | `BENCHMARK_SEED_CHUNK_SIZE` | Records per chunk in memory |
| `benchmark.pg-seed-batch-size` | `5000` | `BENCHMARK_PG_SEED_BATCH_SIZE` | JDBC batch size during seeding |
| `benchmark.mongo-seed-batch-size` | `1000` | `BENCHMARK_MONGO_SEED_BATCH_SIZE` | MongoDB batch size during seeding |
| `benchmark.insert-pool-size` | `50000` | `BENCHMARK_INSERT_POOL_SIZE` | Pre-generated INSERT pool size |
| `benchmark.concurrent-users` | `20` | `BENCHMARK_CONCURRENT_USERS` | Stress test threads per database |
| `benchmark.stress-duration-seconds` | `60` | `BENCHMARK_STRESS_DURATION_SECONDS` | Stress test duration |
| `benchmark.stress-select-pct` | `60` | `BENCHMARK_STRESS_SELECT_PCT` | % of SELECT ops in stress test |
| `benchmark.stress-insert-pct` | `25` | `BENCHMARK_STRESS_INSERT_PCT` | % of INSERT ops in stress test |
| `benchmark.stress-update-pct` | `15` | `BENCHMARK_STRESS_UPDATE_PCT` | % of UPDATE ops in stress test |

---

## How to Read the Results

### Sequential benchmark output

```
  Query                                          │  Avg ms  │  P50 ms  │  P95 ms  │  P99 ms  │  Rows
  Q4  demographic.ssn exact match                │   50.818 │   50.401 │   53.041 │   61.647 │    101  [no index]
                                                 │    0.131 │    0.129 │    0.151 │    0.171 │    101  [indexed]
  Q15 scalar projection (4 fields vs full Q1)    │    0.089 │    0.082 │    0.121 │    0.145 │      1  [indexed]
```

- **Avg ms** — mean latency across all 500 iterations
- **P50 / P95 / P99** — percentile tail latencies
- **Rows** — result count (identical across both DBs for every query)
- **Q1 vs Q15 delta** — PostgreSQL TOAST overhead; large gap (>2×) means records exceed 2 KB in-line limit

### Stress test output

```
  Database       │      ops/sec │  Total ops │  Avg ms │  P50 ms │  P95 ms │  P99 ms
  PostgreSQL     │        842.3 │     50,538 │  23.751 │  21.441 │  41.882 │  78.221
  MongoDB        │      1,241.7 │     74,502 │  16.101 │  14.221 │  28.441 │  52.101
```

- **ops/sec** — total mixed-workload throughput under concurrent load
- **P99** — worst-case tail under contention (GIN pending-list flush, autovacuum, lock contention)
- **PG P99 spikes** relative to sequential baseline indicate GIN pending-list flushing or autovacuum interference under the 25% INSERT rate

---

## Cleanup

```bash
docker compose down -v          # stop containers + wipe volumes
docker rmi db-benchmark         # remove benchmark image
mvn clean                       # remove built JAR
```

---

## Project Structure

```
db-benchmark/
├── docker-compose.yml
├── Dockerfile                                    # Multi-stage build (Maven + JRE 17)
├── pom.xml                                       # Java 17, HikariCP, fat-JAR (6 MB)
├── README.md
├── docs/
│   └── executive-summary-prod.docx              # Word doc: prod benchmark findings
└── src/main/
    ├── resources/
    │   ├── schema.sql                            # DDL: mdm_golden_record table
    │   ├── application-local.properties
    │   └── application-prod.properties
    └── java/com/gsv/benchmark/
        ├── Main.java                             # 8-step orchestration + parallel seeding
        ├── config/
        │   ├── Profile.java                      # LOCAL | PROD enum
        │   └── AppConfig.java                    # Profile-aware connection + config builder
        ├── model/
        │   └── GoldenPerson.java                 # Demographic + Address + prior_values
        ├── data/
        │   ├── DataGenerator.java                # Synthetic MDM record generation (instance RNG, chunked)
        │   └── SampleRecord.java                 # Lightweight query-parameter holder for stress test pool
        ├── postgres/
        │   └── PostgresRepository.java           # Q1–Q15 + seed/insert/update/load (HikariCP, thread-safe)
        ├── mongodb/
        │   └── MongoRepository.java              # Q1–Q15 + seed/insert/update/load (driver thread-safe)
        └── benchmark/
            ├── BenchmarkResult.java              # Raw latency samples + statistics
            ├── BenchmarkRunner.java              # Q1–Q15 warmup + timing harness
            ├── StressTestResult.java             # Stress test aggregate (ops/sec, percentiles)
            ├── StressTestRunner.java             # Concurrent mixed-workload executor
            └── ResultPrinter.java                # Formatted output tables
```
