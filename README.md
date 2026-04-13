# MDM Golden Record Benchmark — PostgreSQL JSONB vs MongoDB

A self-contained Java benchmark comparing how **Amazon RDS PostgreSQL (JSONB)**
and **Amazon DocumentDB (MongoDB-compatible)** perform on realistic MDM
(Master Data Management) query patterns, using a golden person schema with
demographic data, addresses, and full prior-values history.

---

## Overview

The POC seeds **100,000 MDM golden person records** into both databases, then
runs **10 query patterns** in two passes — once with no indexes (sequential scan
baseline) and once with indexes.  
Each query is executed **500 times** after 5 warmup rounds; avg / P50 / P95 / P99
latencies are reported.

---

## Data Model

### DocumentDB — `mdm_golden_person` collection

```json
{
  "_id": "global_pid|global_cid",
  "global_pid": "3fa85f64-...",
  "global_cid": "7cb12e91-...",
  "mastered_date_ts": "2025-06-14T08:30:00Z",
  "demographic": {
    "first_name": "James",
    "last_name":  "Smith",
    "date_of_birth": "1985-03-22",
    "ssn":            "234-56-7890",
    "mastered_date_ts": "2025-06-14T08:30:00Z",
    "rule_id":        "RULE_003",
    "rule_version":   "v2.0",
    "prior_values": [
      {
        "first_name": "Jim",
        "last_name":  "Smith",
        "date_of_birth": "1985-03-22",
        "ssn":            "234-56-0001",
        "mastered_date_ts": "2024-12-01T10:00:00Z",
        "rule_id":        "RULE_001",
        "rule_version":   "v1.0",
        "lost_against_rule": "RULE_003"
      }
    ]
  },
  "address": [
    {
      "address_type": "PRIMARY",
      "address1":  "4821 Main St",
      "address2":  "",
      "city":      "New York",
      "state":     "NY",
      "zipcode":   "10001",
      "country":   "US",
      "mastered_date_ts": "2025-06-14T08:30:00Z",
      "rule_id":   "RULE_003",
      "rule_version": "v2.0",
      "prior_values": [
        {
          "address_type": "PRIMARY",
          "address1":  "100 Oak Ave",
          "address2":  "",
          "city":      "Chicago",
          "state":     "IL",
          "zipcode":   "60601",
          "country":   "US",
          "mastered_date_ts": "2024-08-10T09:00:00Z",
          "rule_id":   "RULE_001",
          "rule_version": "v1.0",
          "lost_against_rule": "RULE_003"
        }
      ]
    }
  ]
}
```

### PostgreSQL — `mdm_golden_record` table

| Column | Type | Notes |
|--------|------|-------|
| `id` | `TEXT PRIMARY KEY` | `"global_pid\|global_cid"` |
| `global_pid` | `TEXT NOT NULL` | Indexed for Q6 |
| `global_cid` | `TEXT NOT NULL` | |
| `mastered_date_ts` | `TIMESTAMPTZ NOT NULL` | Indexed for Q8, Q10 |
| `created_date` | `TIMESTAMPTZ DEFAULT now()` | Set by DB on insert |
| `updated_date` | `TIMESTAMPTZ DEFAULT now()` | Set by DB on insert |
| `golden_record` | `JSONB NOT NULL` | Full document (demographic + address) |

The relational columns (`global_pid`, `mastered_date_ts`) are denormalized
extractions from the JSONB for efficient SQL filtering; the full document is
also kept in `golden_record` for nested and history queries.

---

## Benchmark Queries

| # | Description | PostgreSQL | MongoDB |
|---|-------------|-----------|---------|
| Q1 | Composite PK fetch (full record) | `WHERE id = ?` | `find({_id: ?})` |
| Q2 | Latest prior_values entry (previous winner) | `jsonb_path_query_first(…, '$.demographic.prior_values[last]')` | `find({_id:?}, {$slice:-1})` |
| Q3 | Address by type for known person | `jsonb_path_query_first(…, '$.address[*] ? (@.address_type == $t)')` | `find({_id:?}).projection($elemMatch)` |
| Q4 | `demographic.ssn` exact match | `golden_record->'demographic'->>'ssn' = ?` | `{"demographic.ssn": ?}` |
| Q5 | `last_name` + `date_of_birth` compound | `last_name = ? AND date_of_birth = ?` | `{last_name:?, date_of_birth:?}` |
| Q6 | `global_pid` only (partial key) | `WHERE global_pid = ?` | `{"global_pid": ?}` |
| Q7 | `demographic.rule_id` lookup | `golden_record->'demographic'->>'rule_id' = ?` | `{"demographic.rule_id": ?}` |
| Q8 | Mastered in last 30 days | `mastered_date_ts > ?::timestamptz` | `{mastered_date_ts: {$gt: ?}}` |
| Q9 | Prior SSN history search | `jsonb_path_exists(…, '$.demographic.prior_values[*] ? (@.ssn == $v)')` | `{"demographic.prior_values.ssn": ?}` |
| Q10 | Mastered between start and end timestamp | `mastered_date_ts >= ? AND mastered_date_ts <= ?` | `{mastered_date_ts: {$gte:?, $lte:?}}` |

**Query parameters used:**

| Query | Parameter | Rationale |
|-------|-----------|-----------|
| Q1 | Composite ID captured from first seeded record with prior_values | Single-record PK fetch |
| Q2 | Same ID as Q1 | Previous winner = last element of prior_values |
| Q3 | Same ID + `address_type = "PRIMARY"` | Targeted address sub-document |
| Q4 | `SSN_POOL[0]` (pool of 1,000 → ~100 matches per SSN) | Identity resolution |
| Q5 | `last_name` + `date_of_birth` captured from same seeded record | Probabilistic matching |
| Q6 | `global_pid` captured from same seeded record | Partial key lookup |
| Q7 | `rule_id = "RULE_001"` (~10% of records, 10 rules uniform) | Operational audit |
| Q8 | 30 days ago (cutoff fixed at benchmark start) | Recent change window |
| Q9 | Same SSN as Q4 | Pre-merge dedup — SSN ever seen in history? |
| Q10 | 6 months ago → 3 months ago (~12,500 records in window) | Time-slice sync / audit |

---

## Benchmark Results

Results from a local run on Apple M-series · Docker Compose · 100,000 records · 500 iterations each.

### PostgreSQL Query Performance

| Query | No-Index Avg | No-Index P99 | Indexed Avg | Indexed P99 | Rows |
|-------|-------------|-------------|------------|------------|------|
| Q1  composite PK fetch | 0.147 ms | 0.272 ms | 0.135 ms | 0.190 ms | 1 |
| Q2  latest prior_values (prev winner) | 0.117 ms | 0.241 ms | 0.106 ms | 0.133 ms | 1 |
| Q3  address by type for known PK | 0.135 ms | 0.178 ms | 0.130 ms | 0.157 ms | 1 |
| Q4  demographic.ssn exact match | 50.818 ms | 61.647 ms | 0.131 ms | 0.171 ms | 101 |
| Q5  last_name + date_of_birth compound | 22.919 ms | 24.154 ms | 0.115 ms | 0.193 ms | 1 |
| Q6  global_pid lookup (partial key) | 12.875 ms | 15.150 ms | 0.111 ms | 0.194 ms | 1 |
| Q7  demographic.rule_id = 'RULE_001' | 53.284 ms | 61.903 ms | 2.494 ms | 3.109 ms | 10,091 |
| Q8  mastered last 30 days | 27.492 ms | 64.047 ms | 0.265 ms | 0.389 ms | 4,089 |
| Q9  prior_values[*].ssn history search | 81.861 ms | 92.319 ms | 84.938 ms | 96.453 ms | 154 |
| Q10 mastered_date_ts range (6mo→3mo) | 13.715 ms | 14.878 ms | 0.459 ms | 0.541 ms | 12,382 |

### MongoDB Query Performance

| Query | No-Index Avg | No-Index P99 | Indexed Avg | Indexed P99 | Rows |
|-------|-------------|-------------|------------|------------|------|
| Q1  composite PK fetch | 0.302 ms | 0.603 ms | 0.150 ms | 0.185 ms | 1 |
| Q2  latest prior_values (prev winner) | 0.195 ms | 0.274 ms | 0.152 ms | 0.194 ms | 1 |
| Q3  address by type for known PK | 0.149 ms | 0.219 ms | 0.143 ms | 0.192 ms | 1 |
| Q4  demographic.ssn exact match | 21.955 ms | 26.275 ms | 0.170 ms | 0.294 ms | 101 |
| Q5  last_name + date_of_birth compound | 23.574 ms | 27.278 ms | 0.161 ms | 0.278 ms | 1 |
| Q6  global_pid lookup (partial key) | 29.936 ms | 32.688 ms | 0.161 ms | 0.315 ms | 1 |
| Q7  demographic.rule_id = 'RULE_001' | 24.649 ms | 29.235 ms | 1.866 ms | 1.985 ms | 10,091 |
| Q8  mastered last 30 days | 23.934 ms | 26.943 ms | 0.841 ms | 0.966 ms | 4,089 |
| Q9  prior_values[*].ssn history search | 35.972 ms | 41.243 ms | 0.191 ms | 0.256 ms | 154 |
| Q10 mastered_date_ts range (6mo→3mo) | 28.568 ms | 30.801 ms | 2.213 ms | 2.296 ms | 12,382 |

### Index Speedup (no-index avg ÷ indexed avg)

| Query | PG Speedup | Mongo Speedup | Winner (indexed) |
|-------|-----------|--------------|-----------------|
| Q1  composite PK fetch | 1.1x | 2.0x | Tie (~0.13–0.15 ms) |
| Q2  latest prior_values (prev winner) | 1.1x | 1.3x | Tie (~0.11–0.15 ms) |
| Q3  address by type for known PK | 1.0x | 1.0x | Tie (~0.13–0.14 ms) |
| Q4  demographic.ssn exact match | **387.9x** | 129.1x | **PostgreSQL** (0.131 ms vs 0.170 ms) |
| Q5  last_name + date_of_birth compound | **199.3x** | 146.4x | **PostgreSQL** (0.115 ms vs 0.161 ms) |
| Q6  global_pid lookup (partial key) | 116.0x | 185.9x | Tie (~0.11–0.16 ms) |
| Q7  demographic.rule_id = 'RULE_001' | 21.4x | 13.2x | **MongoDB** (1.866 ms vs 2.494 ms) |
| Q8  mastered last 30 days | **103.7x** | 28.5x | **PostgreSQL** (0.265 ms vs 0.841 ms) |
| Q9  prior_values[*].ssn history search | **1.0x** ⚠️ | **188.3x** | **MongoDB** (0.191 ms vs 84.938 ms) |
| Q10 mastered_date_ts range (6mo→3mo) | **29.9x** | 12.9x | **PostgreSQL** (0.459 ms vs 2.213 ms) |

### Key findings

- **Q1 / Q2 / Q3 (PK-based fetches):** Both databases are sub-millisecond. For the core MDM fetch/save pattern the choice of database makes no practical difference.
- **Q4 SSN & Q5 compound match:** PostgreSQL's expression and compound expression indexes are faster in absolute terms (0.13–0.12 ms) vs MongoDB (0.17–0.16 ms). For identity-resolution hot paths this matters.
- **Q8 & Q10 timestamp range queries:** PostgreSQL's native `TIMESTAMPTZ` B-tree is 3–5× faster than MongoDB's ISO string comparison (0.27 ms vs 0.84 ms for Q8; 0.46 ms vs 2.21 ms for Q10). Critical for sync pipelines and audit windows.
- **Q9 prior_values history search:** PostgreSQL's GIN index gives **zero benefit** on nested `prior_values[*].ssn` — stays at ~83 ms both passes. MongoDB's multikey index is **188× faster** (35 ms → 0.19 ms). If history searches are frequent, this is a meaningful MongoDB advantage or requires schema normalisation in PostgreSQL.
- **Q7 rule_id (~10 k rows):** MongoDB slightly faster in the indexed case (1.87 ms vs 2.49 ms) for high-count result sets.

---

## Indexes

### PostgreSQL

Table lives in `core_data_db` database under the `benchmark` schema.

```sql
-- Primary key (implicit, always present) — Q1, Q2, Q3
-- Relational column indexes
CREATE INDEX idx_mgr_global_pid  ON benchmark.mdm_golden_record (global_pid);          -- Q6
CREATE INDEX idx_mgr_mastered_ts ON benchmark.mdm_golden_record (mastered_date_ts);    -- Q8, Q10
-- GIN: @> containment and jsonb_path_exists — Q3, Q9
CREATE INDEX idx_mgr_gin ON benchmark.mdm_golden_record USING GIN (golden_record jsonb_path_ops);
-- Expression indexes on scalar JSONB fields
CREATE INDEX idx_mgr_ssn      ON benchmark.mdm_golden_record ((golden_record->'demographic'->>'ssn'));     -- Q4
CREATE INDEX idx_mgr_name_dob ON benchmark.mdm_golden_record                                               -- Q5
    ((golden_record->'demographic'->>'last_name'),
     (golden_record->'demographic'->>'date_of_birth'));
CREATE INDEX idx_mgr_rule_id  ON benchmark.mdm_golden_record ((golden_record->'demographic'->>'rule_id')); -- Q7
```

### MongoDB / DocumentDB

```js
db.mdm_golden_person.createIndex({ "global_pid": 1 })                                           // Q6
db.mdm_golden_person.createIndex({ "mastered_date_ts": 1 })                                     // Q8, Q10
db.mdm_golden_person.createIndex({ "demographic.ssn": 1 })                                      // Q4
db.mdm_golden_person.createIndex({ "demographic.last_name": 1, "demographic.date_of_birth": 1}) // Q5
db.mdm_golden_person.createIndex({ "demographic.rule_id": 1 })                                  // Q7
db.mdm_golden_person.createIndex({ "demographic.prior_values.ssn": 1 })                         // Q9
```

---

## RDS IAM User Setup (prod only)

Run the following as the RDS **master user** once before the first prod run.
The app creates the schema and table automatically — these grants just ensure
the IAM user has permission to do so.

```sql
-- 1. Create the user (no password — IAM token is the credential)
CREATE USER bench_iam_user;

-- 2. Allow IAM token authentication
GRANT rds_iam TO bench_iam_user;

-- 3. Allow connection to the database
GRANT CONNECT ON DATABASE core_data_db TO bench_iam_user;

-- 4. Grant schema usage (schema is created by the app on first run)
GRANT USAGE ON SCHEMA benchmark TO bench_iam_user;

-- 5. Grant read/write on all current and future tables in the schema
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA benchmark TO bench_iam_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA benchmark
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO bench_iam_user;

-- 6. Grant sequence usage (needed for default timestamp columns)
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA benchmark TO bench_iam_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA benchmark
    GRANT USAGE, SELECT ON SEQUENCES TO bench_iam_user;
```

The EC2 / ECS instance role also needs this IAM policy:

```json
{
  "Effect": "Allow",
  "Action": "rds-db:connect",
  "Resource": "arn:aws:rds-db:<region>:<account-id>:dbuser:<db-resource-id>/bench_iam_user"
}
```

---

## Prerequisites

| Tool | Version |
|------|---------|
| Java | 17+ |
| Maven | 3.8+ |
| Docker Desktop | any recent (local profile only) |

---

## Profiles

The app supports two runtime profiles, selected in priority order:

| Priority | Method | Example |
|----------|--------|---------|
| 1st | Java system property | `java -Dbenchmark.profile=prod -jar ...` |
| 2nd | Environment variable | `BENCHMARK_PROFILE=prod java -jar ...` |
| Default | *(not set)* | always `local` |

| Profile | Databases | Auth |
|---------|-----------|------|
| `local` | Docker Compose PostgreSQL + MongoDB | Plain username/password, no SSL |
| `prod` | Amazon RDS PostgreSQL + Amazon DocumentDB | RDS: IAM auth token. DocumentDB: TLS + env-var credentials |

> **Note:** The `prod` profile **only works from inside AWS** (EC2, ECS, EKS)
> where an IAM role is automatically injected by the instance metadata service.
> There are no hardcoded credentials anywhere in the code.

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

To wipe data and start fresh:
```bash
docker compose down -v && docker compose up -d
```

### Prod (RDS + DocumentDB)

```bash
# Set env vars
export PG_HOST=mydb.xxxx.us-east-1.rds.amazonaws.com
export PG_USER=bench_iam_user
export AWS_REGION=us-east-1
export PG_SSL_CA_FILE=/path/to/global-bundle.pem
export MONGO_HOST=mydb.xxxx.us-east-1.docdb.amazonaws.com
export MONGO_USER=docdbuser
export MONGO_PASSWORD=secret
export MONGO_TLS_CA_FILE=/path/to/global-bundle.pem

# Run
java -Dbenchmark.profile=prod -jar target/db-benchmark-1.0.0.jar
```

---

## Configuration Reference

All values overridable by environment variable (`PG_HOST` overrides `pg.host`, etc.).

### Local profile (`application-local.properties`)

| Property | Default | Env var |
|----------|---------|---------|
| `pg.host` | `localhost` | `PG_HOST` |
| `pg.port` | `5432` | `PG_PORT` |
| `pg.db` | `core_data_db` | `PG_DB` |
| `pg.schema` | `benchmark` | `PG_SCHEMA` |
| `pg.user` | `bench` | `PG_USER` |
| `pg.password` | `bench` | `PG_PASSWORD` |
| `mongo.host` | `localhost` | `MONGO_HOST` |
| `mongo.port` | `27017` | `MONGO_PORT` |
| `mongo.db` | `benchmark` | `MONGO_DB` |
| `benchmark.user-count` | `100000` | `BENCHMARK_USER_COUNT` |
| `benchmark.warmup` | `5` | `BENCHMARK_WARMUP` |
| `benchmark.iterations` | `500` | `BENCHMARK_ITERATIONS` |

### Prod profile (`application-prod.properties`)

| Property | Required | Env var | Notes |
|----------|----------|---------|-------|
| `pg.host` | Yes | `PG_HOST` | RDS endpoint |
| `pg.db` | No | `PG_DB` | Default: `core_data_db` |
| `pg.schema` | No | `PG_SCHEMA` | Default: `benchmark`; created automatically if absent |
| `pg.user` | Yes | `PG_USER` | Must have `rds_iam` role granted |
| `aws.region` | Yes | `AWS_REGION` | Must match RDS region |
| `pg.ssl.mode` | No | `PG_SSL_MODE` | Default: `verify-full` |
| `pg.ssl.ca-file` | No | `PG_SSL_CA_FILE` | Path to `global-bundle.pem` |
| `mongo.host` | Yes | `MONGO_HOST` | DocumentDB cluster endpoint |
| `mongo.db` | No | `MONGO_DB` | Default: `benchmark`; created automatically if absent |
| `mongo.user` | Yes | `MONGO_USER` | DocumentDB username |
| `mongo.password` | Yes | `MONGO_PASSWORD` | Source from Secrets Manager in pipelines |
| `mongo.tls.ca-file` | No | `MONGO_TLS_CA_FILE` | Path to `global-bundle.pem` |

---

## How to Read the Results

```
  Query                         │  Avg ms  │  P50 ms  │  P95 ms  │  P99 ms  │  Rows
  Q4  demographic.ssn exact     │   50.818 │   50.401 │   53.041 │   61.647 │    101  [no index]
                                │    0.131 │    0.129 │    0.151 │    0.171 │    101  [indexed]
```

- **Avg ms** — mean latency across all 500 iterations
- **P50 ms** — median (half of queries are faster)
- **P95 ms** — 95th percentile (1 in 20 queries is slower)
- **P99 ms** — worst-case tail latency (1 in 100)
- **Rows** — result count (identical across both DBs for every query)
- **[no index]** / **[indexed]** — which benchmark pass

**Index Speedup** = no-index avg ÷ indexed avg. A 100× speedup means the index made that query 100× faster.

---

## Cleanup

```bash
docker compose down -v        # stop containers + wipe volumes
docker rmi postgres:16-alpine mongo:7   # optional: free ~500 MB
mvn clean                     # remove built JAR
```

---

## Project Structure

```
db-benchmark/
├── docker-compose.yml
├── pom.xml                                   # Java 17, AWS SDK BOM, fat-JAR
├── README.md
├── executive-summary-v3.docx                 # Word doc: PG vs DocumentDB analysis + architecture comparison
└── src/main/
    ├── resources/
    │   ├── schema.sql                        # DDL: mdm_golden_record table
    │   ├── application-local.properties
    │   └── application-prod.properties
    └── java/com/gsv/benchmark/
        ├── Main.java
        ├── config/
        │   ├── Profile.java                  # LOCAL | PROD; -D flag or env var
        │   ├── AppConfig.java                # profile-aware connection builder
        │   └── RdsIamTokenProvider.java      # IAM auth token for RDS
        ├── model/
        │   └── GoldenPerson.java             # Demographic + Address + prior_values
        ├── data/
        │   └── DataGenerator.java            # 100k synthetic MDM records
        ├── postgres/
        │   └── PostgresRepository.java       # 10 queries + indexes
        ├── mongodb/
        │   └── MongoRepository.java          # 10 queries + indexes
        └── benchmark/
            ├── BenchmarkResult.java
            ├── BenchmarkRunner.java          # Q1–Q10, warmup + timing harness
            └── ResultPrinter.java
```
