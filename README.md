# PostgreSQL JSONB vs MongoDB — Query Benchmark POC

A self-contained Java benchmark comparing how **PostgreSQL 16 (JSONB)** and
**MongoDB 7** perform on common document query patterns, using a realistic
*user profile + audit log* domain.

---

## Overview

The POC seeds **100,000 golden-record user profiles** and **100,000 audit log
documents** into both databases, then runs **9 query patterns** in two passes:
once with no indexes (sequential scan baseline) and once with indexes.  
Each query is executed **500 times** after 5 warmup rounds; avg / P50 / P95 / P99
latencies are reported.

---

## Data Model

### `users` — Golden Record (current state)

| Store      | Design |
|------------|--------|
| PostgreSQL | `id UUID PK`, `updated_at TIMESTAMPTZ`, `data JSONB` (all profile fields) |
| MongoDB    | `_id = <uuid>`, all profile fields as top-level document fields |

**Profile JSON structure:**
```json
{
  "id": "uuid",
  "name": "Alice Smith",
  "email": "alice.smith42@gmail.com",
  "age": 32,
  "subscription_tier": "premium",
  "address": { "city": "New York", "state": "NY", "zip": "10001" },
  "tags": ["verified", "early_adopter"],
  "preferences": { "theme": "dark", "language": "en", "notifications": true },
  "billing": {
    "account_balance": 1250.75,
    "currency": "USD",
    "credit_score": 720,
    "payment_method": "credit_card",
    "billing_address": { "city": "Brooklyn", "state": "NY", "zip": "11201" }
  },
  "social": {
    "interests": ["technology", "travel", "cooking"],
    "languages_spoken": ["en", "es"],
    "followers_count": 340,
    "following_count": 210,
    "referral_source": "organic"
  }
}
```

### `user_audit_logs` — Attribute-Level History

**One document per user.** All historical changes live inside a single
`audit_data` column/field.  Each tracked attribute maps to an ordered list
of `{value, timestamp}` objects — the last entry equals the current golden
record value.

| Store      | Design |
|------------|--------|
| PostgreSQL | `user_id UUID PK (FK → users.id)`, `audit_data JSONB` |
| MongoDB    | `_id = <user uuid>`, `audit_data` nested object |

**Tracked fields in `audit_data`:**

| Key | Type | Example |
|-----|------|---------|
| `subscription_tier` | `[{value: String, timestamp}]` | `basic → premium → enterprise` |
| `age` | `[{value: Int, timestamp}]` | birthday increments |
| `address_city` | `[{value: String, timestamp}]` | user moved cities |
| `billing_credit_score` | `[{value: Int, timestamp}]` | score improved over time |
| `billing_account_balance` | `[{value: Double, timestamp}]` | balance changes |
| `tags` | `[{value: [String], timestamp}]` | tags added/removed |

```json
{
  "subscription_tier": [
    { "value": "basic",   "timestamp": "2024-01-01T00:00:00Z" },
    { "value": "premium", "timestamp": "2024-01-15T10:30:00Z" }
  ],
  "billing_credit_score": [
    { "value": 680, "timestamp": "2024-01-01T00:00:00Z" },
    { "value": 720, "timestamp": "2024-01-15T10:30:00Z" }
  ]
}
```

---

## Benchmark Queries

### On `users` (golden record)

| # | Pattern | PostgreSQL JSONB | MongoDB |
|---|---------|-----------------|---------|
| Q1 | Equality | `data @> '{"subscription_tier":"premium"}'` | `find({"subscription_tier":"premium"})` |
| Q2 | Range (age) | `(data->>'age')::int BETWEEN 25 AND 40` | `find({"age":{$gte:25,$lte:40}})` |
| Q3 | Range (balance) | `(data->'billing'->>'account_balance')::numeric > 500` | `find({"billing.account_balance":{$gt:500}})` |
| Q4 | Nested field | `data->'address'->>'city' = 'New York'` | `find({"address.city":"New York"})` |
| Q5 | Array containment | `data->'tags' @> '"verified"'` | `find({"tags":"verified"})` |
| Q6 | Array containment | `data->'social'->'interests' @> '"travel"'` | `find({"social.interests":"travel"})` |

### On `user_audit_logs`

| # | Pattern | PostgreSQL JSONB | MongoDB |
|---|---------|-----------------|---------|
| Q7 | Fetch history by user | `WHERE user_id = ?` | `find({"_id": uuid})` |
| Q8 | Ever on 'basic' tier | JSONPath `$.subscription_tier[*] ? (@.value == "basic")` | `find({"audit_data.subscription_tier.value":"basic"})` |
| Q9 | Credit score ever > 700 | JSONPath `$.billing_credit_score[*] ? (@.value > 700)` | `find({"audit_data.billing_credit_score.value":{$gt:700}})` |

---

## Indexes Created (before the "indexed" benchmark pass)

### PostgreSQL
```sql
-- Golden record
CREATE INDEX idx_users_gin     ON users USING GIN (data jsonb_path_ops);
CREATE INDEX idx_users_age     ON users (((data->>'age')::int));
CREATE INDEX idx_users_city    ON users ((data->'address'->>'city'));
CREATE INDEX idx_users_subtier ON users ((data->>'subscription_tier'));
CREATE INDEX idx_users_credit  ON users (((data->'billing'->>'credit_score')::int));
CREATE INDEX idx_users_balance ON users (((data->'billing'->>'account_balance')::numeric));
-- Audit log
CREATE INDEX idx_audit_gin     ON user_audit_logs USING GIN (audit_data jsonb_path_ops);
```

### MongoDB
```js
db.users.createIndex({ "subscription_tier": 1 })
db.users.createIndex({ "age": 1 })
db.users.createIndex({ "address.city": 1 })
db.users.createIndex({ "tags": 1 })
db.users.createIndex({ "billing.credit_score": 1 })
db.users.createIndex({ "billing.account_balance": 1 })
db.user_audit_logs.createIndex({ "audit_data.subscription_tier.value": 1 })
db.user_audit_logs.createIndex({ "audit_data.subscription_tier.timestamp": 1 })
db.user_audit_logs.createIndex({ "audit_data.billing_credit_score.value": 1 })
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

> **Note:** The `prod` profile **only works from inside AWS** (EC2, ECS, EKS) where
> an IAM role is automatically injected by the instance metadata service.
> There are no hardcoded credentials anywhere in the code.

---

## How to Run

### Local (Docker Compose)

#### 1 — Start the databases
```bash
cd gsv-workspace/db-benchmark
docker compose up -d
docker compose ps     # wait until both show "healthy"
```

#### 2 — Build
```bash
mvn package -q
```

#### 3 — Run
```bash
# Profile defaults to 'local' — no env vars needed
java -jar target/db-benchmark-1.0.0.jar
```

The program will:
1. Generate 100k users and audit logs in memory
2. Seed both PostgreSQL and MongoDB
3. Run all 9 queries × 2 databases × 500 iterations **without** indexes
4. Create indexes on both databases
5. Run all 9 queries × 2 databases × 500 iterations **with** indexes
6. Print a formatted results table

---

### Prod (RDS + DocumentDB)

#### Prerequisites on AWS side (one-time setup)

**RDS PostgreSQL — grant IAM auth to the DB user:**
```sql
GRANT rds_iam TO bench_iam_user;
```

**IAM policy on the compute role (EC2 / ECS task / EKS pod):**
```json
{
  "Effect": "Allow",
  "Action": "rds-db:connect",
  "Resource": "arn:aws:rds-db:REGION:ACCOUNT_ID:dbuser:DB_RESOURCE_ID/bench_iam_user"
}
```

**RDS CA bundle (for SSL verify-full — recommended):**
```bash
curl -O https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem
```

#### Run

```bash
# Option A: Java system property for profile (recommended — mirrors -Dspring.profiles.active)
java -Dbenchmark.profile=prod \
     -jar target/db-benchmark-1.0.0.jar

# Option B: environment variable for profile
BENCHMARK_PROFILE=prod java -jar target/db-benchmark-1.0.0.jar
```

Set the required env vars before running (or export them in your shell):

```bash
# RDS PostgreSQL — no password needed, IAM role generates the auth token at runtime
export PG_HOST=mydb.xxxx.us-east-1.rds.amazonaws.com
export PG_USER=bench_iam_user
export AWS_REGION=us-east-1
export PG_SSL_CA_FILE=/path/to/global-bundle.pem   # omit to use JVM default trust store
export PG_SSL_MODE=verify-full                       # or 'require' to skip CA verification

# DocumentDB — TLS always on; credentials from env vars
export MONGO_HOST=mydb.xxxx.us-east-1.docdb.amazonaws.com
export MONGO_USER=docdbuser
export MONGO_PASSWORD=secret                         # source from Secrets Manager in pipelines
export MONGO_TLS_CA_FILE=/path/to/global-bundle.pem # same bundle works for both RDS and DocDB
```

---

## Configuration Reference

All values can be overridden by environment variable. The env var name is the property
key uppercased with dots/hyphens replaced by underscores (e.g. `pg.host` → `PG_HOST`).

### Local profile defaults (`application-local.properties`)

| Property | Default | Env var override |
|----------|---------|-----------------|
| `pg.host` | `localhost` | `PG_HOST` |
| `pg.port` | `5432` | `PG_PORT` |
| `pg.db` | `benchmark` | `PG_DB` |
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
| `pg.user` | Yes | `PG_USER` | Must have `rds_iam` role granted |
| `aws.region` | Yes | `AWS_REGION` | Must match RDS region |
| `pg.ssl.mode` | No | `PG_SSL_MODE` | Default: `verify-full` |
| `pg.ssl.ca-file` | No | `PG_SSL_CA_FILE` | Path to `global-bundle.pem` |
| `mongo.host` | Yes | `MONGO_HOST` | DocumentDB endpoint |
| `mongo.user` | Yes | `MONGO_USER` | DocumentDB username |
| `mongo.password` | Yes | `MONGO_PASSWORD` | DocumentDB password |
| `mongo.tls.ca-file` | No | `MONGO_TLS_CA_FILE` | Path to `global-bundle.pem` |

---

## How to Read the Results

```
  ┌─ Query name ──────────────────────────── │  Avg ms  │  P50 ms  │  P95 ms  │  P99 ms  │  Rows
  │ Q1  equality: subscription_tier='premium' │    0.800 │    0.750 │    1.200 │    2.100 │ 39,912  [no index]
  │                                           │    0.120 │    0.110 │    0.180 │    0.250 │ 39,912  [indexed]
```

- **Avg ms** — mean latency across all iterations
- **P50 ms** — median latency (half of queries are faster)
- **P95 ms** — 95th percentile (1 in 20 queries is slower)
- **P99 ms** — 99th percentile (worst-case tail latency)
- **Rows** — result count (should be consistent across databases for the same query)
- **[no index]** / **[indexed]** — which benchmark pass the row belongs to

The **Index Speedup** section at the end shows `no-index avg ÷ indexed avg` — a 10× speedup means indexed queries run 10 times faster on average.

---

## Cleanup

```bash
# Stop and remove containers + all data volumes
docker compose down -v

# Optionally remove the pulled images (~500 MB)
docker rmi postgres:16-alpine mongo:7

# Remove the built JAR
mvn clean
```

The `-v` flag to `docker compose down` removes named volumes, so no data lingers on disk.

---

## Project Structure

```
db-benchmark/
├── docker-compose.yml                        # PostgreSQL 16 + MongoDB 7
├── pom.xml                                   # Maven, Java 17, AWS SDK, fat-JAR
├── README.md                                 # this file
└── src/main/
    ├── resources/
    │   ├── schema.sql                        # DDL for users + user_audit_logs
    │   ├── application-local.properties      # local profile defaults (docker-compose)
    │   └── application-prod.properties       # prod profile template (RDS + DocumentDB)
    └── java/com/gsv/benchmark/
        ├── Main.java                         # orchestrator: seed → benchmark → print
        ├── config/
        │   ├── Profile.java                  # enum: LOCAL | PROD; resolved from -D or env var
        │   ├── AppConfig.java                # loads properties, builds profile-aware connections
        │   └── RdsIamTokenProvider.java      # generates short-lived IAM auth token for RDS
        ├── model/
        │   ├── UserProfile.java              # POJO with nested Address/Billing/Social
        │   └── AuditLog.java                 # POJO: userId + Map<field, List<AuditEntry>>
        ├── data/
        │   └── DataGenerator.java            # generates 100k realistic profiles & audit logs
        ├── postgres/
        │   └── PostgresRepository.java       # DDL, seeding, 9 benchmark queries
        ├── mongodb/
        │   └── MongoRepository.java          # collections, seeding, 9 benchmark queries
        └── benchmark/
            ├── BenchmarkResult.java          # latency samples + avg/p50/p95/p99 stats
            ├── BenchmarkRunner.java          # warmup + timed measurement harness
            └── ResultPrinter.java            # formatted ASCII table output
```

---

## Out of Scope

- Write performance (inserts / updates)
- Connection pool tuning
- AWS RDS / DocumentDB deployment (use env vars to point to live endpoints)
- Aggregation pipelines / GROUP BY
