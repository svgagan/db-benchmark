package com.gsv.benchmark.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsv.benchmark.model.GoldenPerson;
import org.postgresql.util.PGobject;

import com.gsv.benchmark.data.SampleRecord;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * All PostgreSQL operations for the MDM benchmark.
 *
 * <p>Thread-safe: backed by a HikariCP {@link DataSource}; every method borrows a
 * connection from the pool, uses it, and returns it automatically via try-with-resources.
 * No shared mutable JDBC state — safe to call from multiple threads concurrently.
 *
 * <h3>Table: benchmark.mdm_golden_record</h3>
 * <pre>
 *   id                TEXT PRIMARY KEY        "global_pid|global_cid"
 *   global_pid        TEXT NOT NULL
 *   global_cid        TEXT NOT NULL
 *   mastered_date_ts  TIMESTAMPTZ NOT NULL
 *   created_date      TIMESTAMPTZ DEFAULT now()
 *   updated_date      TIMESTAMPTZ DEFAULT now()
 *   golden_record     JSONB NOT NULL
 * </pre>
 *
 * <h3>Benchmark queries</h3>
 * <ol>
 *   <li>Q1  — Composite PK fetch — {@code WHERE id = ?}</li>
 *   <li>Q2  — Latest prior_values entry — {@code jsonb_path_query_first(... [last])}</li>
 *   <li>Q3  — Address by type for known person</li>
 *   <li>Q4  — SSN exact match — expression B-tree</li>
 *   <li>Q5  — Compound last_name + date_of_birth lookup</li>
 *   <li>Q6  — Partial key: global_pid only</li>
 *   <li>Q7  — Rule ID lookup</li>
 *   <li>Q8  — Records mastered in last 30 days</li>
 *   <li>Q9  — Prior demographic SSN search</li>
 *   <li>Q10 — Range query: records mastered between two timestamps</li>
 *   <li>Q11 — Single INSERT latency</li>
 *   <li>Q12 — Batch INSERT (100 records)</li>
 *   <li>Q13 — SSN point UPDATE via jsonb_set</li>
 *   <li>Q14 — prior_values array APPEND via jsonb concat</li>
 * </ol>
 */
public class PostgresRepository implements AutoCloseable {

    private static final String TABLE = "mdm_golden_record";

    private final DataSource   dataSource;
    private final String       schema;
    private final String       qualifiedTable;   // schema.mdm_golden_record
    private final ObjectMapper mapper = new ObjectMapper();

    // Sample values populated by loadSampleValues() after seeding — used by Q1–Q7
    private volatile String sampleId;
    private volatile String sampleGlobalPid;
    private volatile String sampleLastName;
    private volatile String sampleDob;

    public PostgresRepository(DataSource dataSource, String schema) {
        this.dataSource     = dataSource;
        this.schema         = schema;
        this.qualifiedTable = schema + "." + TABLE;
    }

    // ------------------------------------------------------------------
    // Schema / DDL management
    // ------------------------------------------------------------------

    public void dropAndCreateTables() throws SQLException, IOException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);

            // Ensure schema exists
            try (Statement st = c.createStatement()) {
                st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
                c.commit();
            }

            // Drop existing table
            try (Statement st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS " + qualifiedTable);
                c.commit();
            }

            // Read DDL and replace unqualified table name with schema-qualified one
            String ddl;
            try (InputStream is = getClass().getResourceAsStream("/schema.sql")) {
                ddl = new String(is.readAllBytes(), StandardCharsets.UTF_8)
                        .replace(TABLE, qualifiedTable);
            }

            try (Statement st = c.createStatement()) {
                for (String sql : ddl.split(";")) {
                    String trimmed = sql.lines()
                            .filter(line -> !line.stripLeading().startsWith("--"))
                            .reduce("", (a, b) -> a + "\n" + b)
                            .strip();
                    if (!trimmed.isEmpty()) st.execute(trimmed);
                }
            }
            c.commit();
        }
        System.out.printf("  [PG] Schema '%s' ready. Table %s created.%n", schema, qualifiedTable);
    }

    /**
     * Creates all indexes needed for the "indexed" benchmark pass.
     * <ul>
     *   <li>B-tree on {@code global_pid}</li>
     *   <li>B-tree on {@code mastered_date_ts}</li>
     *   <li>GIN (jsonb_path_ops) on {@code golden_record} — Q3 path extraction, Q9 JSONPath</li>
     *   <li>Expression B-tree on {@code demographic.ssn} — Q4</li>
     *   <li>Compound expression B-tree on {@code (last_name, date_of_birth)} — Q5</li>
     *   <li>Expression B-tree on {@code demographic.rule_id} — Q7</li>
     * </ul>
     */
    public void createIndexes() throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement  st = c.createStatement()) {

            st.execute("CREATE INDEX IF NOT EXISTS idx_mgr_global_pid  ON " + qualifiedTable + " (global_pid)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_mgr_mastered_ts ON " + qualifiedTable + " (mastered_date_ts)");

            // GIN: accelerates @> containment and jsonb_path_exists — Q3, Q9
            st.execute("CREATE INDEX IF NOT EXISTS idx_mgr_gin ON " + qualifiedTable
                    + " USING GIN (golden_record jsonb_path_ops)");

            // Expression indexes on scalar JSONB fields
            st.execute("CREATE INDEX IF NOT EXISTS idx_mgr_ssn ON " + qualifiedTable
                    + " ((golden_record->'demographic'->>'ssn'))");

            // Compound expression index — last_name + date_of_birth for Q5
            st.execute("CREATE INDEX IF NOT EXISTS idx_mgr_name_dob ON " + qualifiedTable
                    + " ((golden_record->'demographic'->>'last_name'),"
                    + "  (golden_record->'demographic'->>'date_of_birth'))");

            st.execute("CREATE INDEX IF NOT EXISTS idx_mgr_rule_id ON " + qualifiedTable
                    + " ((golden_record->'demographic'->>'rule_id'))");
        }
        System.out.println("  [PG] Indexes created.");
    }

    // ------------------------------------------------------------------
    // Seeding
    // ------------------------------------------------------------------

    /**
     * Inserts {@code chunk} records using JDBC batch mode sized to {@code batchSize}.
     * Called once per chunk from each seeding thread — thread-safe (borrows its own connection).
     */
    public void seedChunk(List<GoldenPerson> chunk, int batchSize) throws SQLException {
        String sql = "INSERT INTO " + qualifiedTable
                + " (id, global_pid, global_cid, mastered_date_ts, golden_record)"
                + " VALUES (?, ?, ?, ?::timestamptz, ?)";

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int count = 0;
                for (GoldenPerson p : chunk) {
                    ps.setString(1, p.getId());
                    ps.setString(2, p.getGlobalPid());
                    ps.setString(3, p.getGlobalCid());
                    ps.setString(4, p.getMasteredDateTs());
                    ps.setObject(5, toJsonb(p));
                    ps.addBatch();
                    if (++count % batchSize == 0) ps.executeBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /**
     * Loads sample values from the database for use in benchmark query parameters.
     * Must be called after seeding completes, before running benchmarks.
     */
    public void loadSampleValues() throws SQLException {
        // Load a record that has prior_values — needed for Q1, Q2, Q3, Q6
        String sql = "SELECT id, global_pid,"
                + " golden_record->'demographic'->>'last_name',"
                + " golden_record->'demographic'->>'date_of_birth'"
                + " FROM " + qualifiedTable
                + " WHERE jsonb_array_length(golden_record->'demographic'->'prior_values') > 0"
                + " LIMIT 1";

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                sampleId        = rs.getString(1);
                sampleGlobalPid = rs.getString(2);
                sampleLastName  = rs.getString(3);
                sampleDob       = rs.getString(4);
            }
        }

        if (sampleId == null) {
            throw new IllegalStateException("[PG] Could not load sample values — is the table seeded?");
        }
        System.out.printf("  [PG] Sample values loaded (id=%.40s…)%n", sampleId);
    }

    // ------------------------------------------------------------------
    // Q1–Q10: SELECT benchmarks
    // ------------------------------------------------------------------

    /**
     * Q1 — Composite PK fetch (sequential benchmark — uses stored sampleId).
     */
    public long queryByPrimaryKey() throws SQLException { return queryByPrimaryKey(sampleId); }

    /**
     * Q1 — Composite PK fetch with explicit id (used by stress test with randomised pool).
     */
    public long queryByPrimaryKey(String id) throws SQLException {
        String sql = "SELECT golden_record FROM " + qualifiedTable + " WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? 1 : 0;
            }
        }
    }

    /**
     * Q2 — Latest prior_values entry (sequential benchmark — uses stored sampleId).
     */
    public long queryLatestPriorValue() throws SQLException { return queryLatestPriorValue(sampleId); }

    /**
     * Q2 — Latest prior_values entry with explicit id (stress test).
     */
    public long queryLatestPriorValue(String id) throws SQLException {
        String sql = "SELECT jsonb_path_query_first("
                + "  golden_record, '$.demographic.prior_values[last]')"
                + " FROM " + qualifiedTable + " WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1) != null ? 1 : 0;
                return 0;
            }
        }
    }

    /**
     * Q3 — Address by type (sequential benchmark — uses stored sampleId).
     */
    public long queryAddressByType(String addressType) throws SQLException {
        return queryAddressByType(sampleId, addressType);
    }

    /**
     * Q3 — Address by type with explicit id (stress test).
     */
    public long queryAddressByType(String id, String addressType) throws SQLException {
        String sql = "SELECT golden_record,"
                + " jsonb_path_query_first("
                + "   golden_record,"
                + "   '$.address[*] ? (@.address_type == $t)',"
                + "   jsonb_build_object('t', ?::text)) AS matched_address"
                + " FROM " + qualifiedTable + " WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, addressType);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? 1 : 0;
            }
        }
    }

    /**
     * Q4 — SSN exact match.
     * Count records whose current {@code demographic.ssn} equals the given value.
     */
    public long queryBySsn(String ssn) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + qualifiedTable
                + " WHERE golden_record->'demographic'->>'ssn' = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ssn);
            return fetchCount(ps);
        }
    }

    /**
     * Q5 — Compound last_name + date_of_birth lookup.
     * Count records matching both demographic fields simultaneously.
     */
    public long queryByLastNameAndDob(String lastName, String dob) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + qualifiedTable
                + " WHERE golden_record->'demographic'->>'last_name' = ?"
                + "   AND golden_record->'demographic'->>'date_of_birth' = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, lastName);
            ps.setString(2, dob);
            return fetchCount(ps);
        }
    }

    /** Q6 — Partial key (sequential benchmark — uses stored sampleGlobalPid). */
    public long queryByGlobalPid() throws SQLException { return queryByGlobalPid(sampleGlobalPid); }

    /** Q6 — Partial key with explicit pid (stress test). */
    public long queryByGlobalPid(String globalPid) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + qualifiedTable + " WHERE global_pid = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, globalPid);
            return fetchCount(ps);
        }
    }

    /**
     * Q7 — Rule ID lookup.
     * Count records whose current demographic was mastered by a specific rule.
     */
    public long queryByRuleId(String ruleId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + qualifiedTable
                + " WHERE golden_record->'demographic'->>'rule_id' = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ruleId);
            return fetchCount(ps);
        }
    }

    /**
     * Q8 — Records mastered in the last 30 days.
     * Count recently mastered records — B-tree index on TIMESTAMPTZ column.
     */
    public long queryMasteredLast30Days(String cutoffIso) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + qualifiedTable
                + " WHERE mastered_date_ts > ?::timestamptz";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, cutoffIso);
            return fetchCount(ps);
        }
    }

    /**
     * Q9 — History: prior demographic SSN search.
     * Count records where any entry in {@code demographic.prior_values[*]} has a matching SSN.
     */
    public long queryPriorSsn(String ssn) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + qualifiedTable
                + " WHERE jsonb_path_exists(golden_record,"
                + "   '$.demographic.prior_values[*] ? (@.ssn == $v)',"
                + "   jsonb_build_object('v', ?::text))";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ssn);
            return fetchCount(ps);
        }
    }

    /**
     * Q10 — Range query: records mastered between two timestamps.
     * B-tree index on the native TIMESTAMPTZ column covers both bounds.
     */
    public long queryMasteredBetween(String startIso, String endIso) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + qualifiedTable
                + " WHERE mastered_date_ts >= ?::timestamptz"
                + "   AND mastered_date_ts <= ?::timestamptz";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, startIso);
            ps.setString(2, endIso);
            return fetchCount(ps);
        }
    }

    // ------------------------------------------------------------------
    // Q11–Q12: INSERT benchmarks
    // ------------------------------------------------------------------

    /**
     * Q11 — Single INSERT latency.
     * Inserts one record and returns the elapsed nanoseconds.
     * Caller is responsible for cleanup via {@link #deleteByIds}.
     */
    public long insertSingle(GoldenPerson person) throws SQLException {
        String sql = "INSERT INTO " + qualifiedTable
                + " (id, global_pid, global_cid, mastered_date_ts, golden_record)"
                + " VALUES (?, ?, ?, ?::timestamptz, ?)";

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, person.getId());
            ps.setString(2, person.getGlobalPid());
            ps.setString(3, person.getGlobalCid());
            ps.setString(4, person.getMasteredDateTs());
            ps.setObject(5, toJsonb(person));
            long t0 = System.nanoTime();
            ps.executeUpdate();
            return System.nanoTime() - t0;
        }
    }

    /**
     * Q12 — Batch INSERT.
     * Inserts {@code batch.size()} records in a single JDBC batch and returns elapsed nanoseconds.
     * Caller is responsible for cleanup via {@link #deleteByIds}.
     */
    public long insertBatch(List<GoldenPerson> batch) throws SQLException {
        String sql = "INSERT INTO " + qualifiedTable
                + " (id, global_pid, global_cid, mastered_date_ts, golden_record)"
                + " VALUES (?, ?, ?, ?::timestamptz, ?)";

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (GoldenPerson p : batch) {
                    ps.setString(1, p.getId());
                    ps.setString(2, p.getGlobalPid());
                    ps.setString(3, p.getGlobalCid());
                    ps.setString(4, p.getMasteredDateTs());
                    ps.setObject(5, toJsonb(p));
                    ps.addBatch();
                }
                long t0 = System.nanoTime();
                ps.executeBatch();
                c.commit();
                return System.nanoTime() - t0;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /**
     * Deletes records by their {@code id} values.
     * Called after INSERT benchmark iterations to keep row count stable.
     */
    public void deleteByIds(List<String> ids) throws SQLException {
        if (ids.isEmpty()) return;
        String sql = "DELETE FROM " + qualifiedTable + " WHERE id = ?";
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (String id : ids) {
                    ps.setString(1, id);
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------
    // Q13–Q14: UPDATE benchmarks
    // ------------------------------------------------------------------

    /**
     * Q13 — SSN point UPDATE.
     * Updates {@code demographic.ssn} for a single record using {@code jsonb_set}.
     * Models a scalar field correction — the most common MDM point update.
     * Returns elapsed nanoseconds.
     *
     * <p>SQL: {@code UPDATE ... SET golden_record = jsonb_set(golden_record,
     * '{demographic,ssn}', to_jsonb(?::text)) WHERE id = ?}
     */
    public long updateSsnById(String id, String newSsn) throws SQLException {
        String sql = "UPDATE " + qualifiedTable
                + " SET golden_record = jsonb_set(golden_record, '{demographic,ssn}', to_jsonb(?::text)),"
                + "     updated_date = now()"
                + " WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, newSsn);
            ps.setString(2, id);
            long t0 = System.nanoTime();
            ps.executeUpdate();
            return System.nanoTime() - t0;
        }
    }

    /**
     * Q14 — prior_values array APPEND.
     * Appends a new {@link GoldenPerson.DemographicHistory} entry to the
     * {@code demographic.prior_values} array using JSONb {@code ||} concatenation.
     * Models the most common MDM write: pushing the previous winner onto the history stack.
     * Returns elapsed nanoseconds.
     *
     * <p>SQL: {@code UPDATE ... SET golden_record = jsonb_set(golden_record,
     * '{demographic,prior_values}', (golden_record->'demographic'->'prior_values') || ?::jsonb)}
     */
    public long appendDemographicHistory(String id, GoldenPerson.DemographicHistory entry)
            throws SQLException {
        String sql = "UPDATE " + qualifiedTable
                + " SET golden_record = jsonb_set("
                + "       golden_record,"
                + "       '{demographic,prior_values}',"
                + "       (golden_record->'demographic'->'prior_values') || ?::jsonb"
                + "     ),"
                + "     updated_date = now()"
                + " WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "[" + toJson(entry) + "]");
            ps.setString(2, id);
            long t0 = System.nanoTime();
            ps.executeUpdate();
            return System.nanoTime() - t0;
        }
    }

    /**
     * Loads a random pool of {@link SampleRecord}s for use as randomised query
     * parameters in the concurrent stress test.
     *
     * <p>Only records that have at least one {@code prior_values} entry are
     * included so Q2 (latest prior_values fetch) always returns a result.
     * Using a pool of 500+ distinct records prevents any single page from
     * dominating the PostgreSQL buffer cache during stress testing.
     */
    public List<SampleRecord> loadSamplePool(int count) throws SQLException {
        String sql = "SELECT id, global_pid,"
                + " golden_record->'demographic'->>'last_name',"
                + " golden_record->'demographic'->>'date_of_birth'"
                + " FROM " + qualifiedTable
                + " WHERE jsonb_array_length(golden_record->'demographic'->'prior_values') > 0"
                + " ORDER BY random() LIMIT ?";
        List<SampleRecord> pool = new ArrayList<>(count);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, count);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pool.add(new SampleRecord(
                            rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4)));
                }
            }
        }
        if (pool.isEmpty()) {
            throw new IllegalStateException("[PG] Sample pool is empty — is the table seeded?");
        }
        System.out.printf("  [PG] Sample pool loaded (%,d records for stress-test randomisation).%n", pool.size());
        return pool;
    }

    /**
     * Loads a random sample of record IDs from the seeded table.
     * Used to build the UPDATE benchmark target pool — IDs are cycled across
     * Q13/Q14 iterations.
     */
    public List<String> loadUpdateSampleIds(int count) throws SQLException {
        String sql = "SELECT id FROM " + qualifiedTable
                + " ORDER BY random() LIMIT ?";
        List<String> ids = new ArrayList<>(count);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, count);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getString(1));
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // Q15: TOAST pressure test
    // ------------------------------------------------------------------

    /**
     * Q15 — Scalar projection: fetch only four demographic scalar fields.
     *
     * <p>Compared with Q1 (which fetches the entire {@code golden_record} JSONB blob),
     * this query reveals the overhead of PostgreSQL's TOAST mechanism.
     *
     * <h3>Why TOAST matters here</h3>
     * PostgreSQL inlines values up to ~2 KB in the heap page. A fully populated
     * golden record — with 2–3 prior_values history entries and 2–3 addresses each
     * with their own history — can reach 3–6 KB, triggering TOAST storage. When
     * Q1 fetches {@code golden_record}, PostgreSQL must chase an out-of-line TOAST
     * pointer, adding a second I/O per row. Q15 avoids this: the expression indexes
     * on scalar fields let PostgreSQL return just the extracted text values without
     * reading the full TOAST tuple.
     *
     * <p>A large Q1-vs-Q15 latency gap (e.g., 2× or more) is a signal that your
     * application should prefer scalar-field projections over full-document fetches
     * in read-heavy paths. MongoDB does not have an equivalent TOAST problem because
     * WiredTiger stores documents in a B-tree with per-document compression — large
     * documents are still a single contiguous read.
     */
    public long queryScalarProjection() throws SQLException {
        String sql = "SELECT golden_record->'demographic'->>'first_name',"
                + " golden_record->'demographic'->>'last_name',"
                + " golden_record->'demographic'->>'ssn',"
                + " golden_record->'demographic'->>'date_of_birth'"
                + " FROM " + qualifiedTable + " WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sampleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? 1 : 0;
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private long fetchCount(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private PGobject toJsonb(Object obj) {
        try {
            PGobject pg = new PGobject();
            pg.setType("jsonb");
            pg.setValue(mapper.writeValueAsString(obj));
            return pg;
        } catch (Exception e) {
            throw new RuntimeException("JSON serialisation failed", e);
        }
    }

    private String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialisation failed", e);
        }
    }

    // ------------------------------------------------------------------
    // Sample value accessors (populated by loadSampleValues())
    // ------------------------------------------------------------------

    public String getSampleId()        { return sampleId; }
    public String getSampleGlobalPid() { return sampleGlobalPid; }
    public String getSampleLastName()  { return sampleLastName; }
    public String getSampleDob()       { return sampleDob; }

    @Override
    public void close() {
        // DataSource lifecycle is managed by the caller (HikariDataSource.close())
    }
}
