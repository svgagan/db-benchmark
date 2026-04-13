package com.gsv.benchmark.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsv.benchmark.model.GoldenPerson;
import org.postgresql.util.PGobject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.List;

/**
 * All PostgreSQL operations for the MDM benchmark.
 *
 * <h3>Table: mdm_golden_record</h3>
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
 * <h3>9 Benchmark queries</h3>
 * <ol>
 *   <li>Q1 — Composite PK fetch — {@code WHERE id = ?}  (full record)</li>
 *   <li>Q2 — Latest prior_values entry — last element of {@code demographic.prior_values[]}</li>
 *   <li>Q3 — Address by type for known person — full record + matched address element</li>
 *   <li>Q4 — SSN exact match — {@code demographic.ssn} (expression index)</li>
 *   <li>Q5 — Compound match — {@code demographic.last_name + date_of_birth}</li>
 *   <li>Q6 — Partial key — {@code global_pid} only (relational column B-tree)</li>
 *   <li>Q7 — Rule lookup — {@code demographic.rule_id} (expression index)</li>
 *   <li>Q8 — Recent records — {@code mastered_date_ts} in last 30 days (range)</li>
 *   <li>Q9 — History search — any {@code demographic.prior_values[*].ssn} match</li>
 * </ol>
 */
public class PostgresRepository implements AutoCloseable {

    private static final String TABLE = "mdm_golden_record";

    private final Connection   conn;
    private final String       schema;
    private final ObjectMapper mapper = new ObjectMapper();

    // Sample values captured during seeding — used by Q1 / Q2 / Q3 / Q6
    private String sampleId;
    private String sampleGlobalPid;

    // Captured from first seeded person with non-empty prior_values — used by Q5
    private String sampleLastName;
    private String sampleDob;

    public PostgresRepository(Connection conn, String schema) throws SQLException {
        this.conn   = conn;
        this.schema = schema;
        this.conn.setAutoCommit(false);
    }

    // ------------------------------------------------------------------
    // Schema management
    // ------------------------------------------------------------------

    public void dropAndCreateTables() throws SQLException, IOException {
        // Ensure schema exists (idempotent)
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            conn.commit();
        }
        // Drop the table (schema-qualified so it resolves regardless of search_path state)
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + schema + "." + TABLE);
        }
        String ddl;
        try (InputStream is = getClass().getResourceAsStream("/schema.sql")) {
            ddl = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Statement st = conn.createStatement()) {
            for (String sql : ddl.split(";")) {
                String trimmed = sql.lines()
                        .filter(line -> !line.stripLeading().startsWith("--"))
                        .reduce("", (a, b) -> a + "\n" + b)
                        .strip();
                if (!trimmed.isEmpty()) st.execute(trimmed);
            }
        }
        conn.commit();
        System.out.printf("  [PG] Schema '%s' ready. Table mdm_golden_record created.%n", schema);
    }

    /**
     * Indexes created for the "indexed" benchmark pass:
     * <ul>
     *   <li>PRIMARY KEY on {@code id} — implicit, always present (Q1, Q2, Q3)</li>
     *   <li>B-tree on {@code global_pid} — Q6</li>
     *   <li>B-tree on {@code mastered_date_ts} — Q8</li>
     *   <li>GIN (jsonb_path_ops) on {@code golden_record} — Q3 path extraction, Q9 JSONPath</li>
     *   <li>Expression B-tree on {@code demographic.ssn} — Q4</li>
     *   <li>Compound expression B-tree on {@code (last_name, date_of_birth)} — Q5</li>
     *   <li>Expression B-tree on {@code demographic.rule_id} — Q7</li>
     * </ul>
     */
    public void createIndexes() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE INDEX IF NOT EXISTS idx_mgr_global_pid  ON " + TABLE + " (global_pid)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_mgr_mastered_ts ON " + TABLE + " (mastered_date_ts)");

            // GIN: accelerates @> containment and jsonb_path_exists — Q3, Q9
            st.execute("CREATE INDEX IF NOT EXISTS idx_mgr_gin ON " + TABLE
                    + " USING GIN (golden_record jsonb_path_ops)");

            // Expression indexes on scalar JSONB fields
            st.execute("CREATE INDEX IF NOT EXISTS idx_mgr_ssn ON " + TABLE
                    + " ((golden_record->'demographic'->>'ssn'))");

            // Compound expression index — last_name + date_of_birth together for Q5
            st.execute("CREATE INDEX IF NOT EXISTS idx_mgr_name_dob ON " + TABLE
                    + " ((golden_record->'demographic'->>'last_name'),"
                    + "  (golden_record->'demographic'->>'date_of_birth'))");

            st.execute("CREATE INDEX IF NOT EXISTS idx_mgr_rule_id ON " + TABLE
                    + " ((golden_record->'demographic'->>'rule_id'))");
        }
        conn.commit();
        System.out.println("  [PG] Indexes created.");
    }

    // ------------------------------------------------------------------
    // Seeding
    // ------------------------------------------------------------------

    public void seedPersons(List<GoldenPerson> persons) throws SQLException {
        String sql = "INSERT INTO " + TABLE
                + " (id, global_pid, global_cid, mastered_date_ts, golden_record)"
                + " VALUES (?, ?, ?, ?::timestamptz, ?)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int batch = 0;
            for (GoldenPerson p : persons) {
                ps.setString(1, p.getId());
                ps.setString(2, p.getGlobalPid());
                ps.setString(3, p.getGlobalCid());
                ps.setString(4, p.getMasteredDateTs());
                ps.setObject(5, toJsonb(p));
                ps.addBatch();
                if (++batch % 1_000 == 0) ps.executeBatch();

                // Capture a sample record that has prior_values (needed for Q2)
                if (sampleId == null
                        && p.getDemographic().getPriorValues() != null
                        && !p.getDemographic().getPriorValues().isEmpty()) {
                    sampleId        = p.getId();
                    sampleGlobalPid = p.getGlobalPid();
                    sampleLastName  = p.getDemographic().getLastName();
                    sampleDob       = p.getDemographic().getDateOfBirth();
                }
            }
            ps.executeBatch();
        }
        conn.commit();
        System.out.printf("  [PG] Inserted %,d golden records.%n", persons.size());
    }

    // ------------------------------------------------------------------
    // Benchmark queries
    // ------------------------------------------------------------------

    /**
     * Q1 — Composite PK fetch.
     * Fetches the full golden record by its composite primary key {@code id = "global_pid|global_cid"}.
     * This is the most frequent MDM operation — the caller already knows both halves of the key.
     */
    public long queryByPrimaryKey() throws SQLException {
        String sql = "SELECT golden_record FROM " + TABLE + " WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sampleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? 1 : 0;
            }
        }
    }

    /**
     * Q2 — Latest prior_values entry (previous winner).
     * For a known record (by PK), retrieves the most recent entry in
     * {@code demographic.prior_values[]} — the demographic snapshot that was
     * superseded by the current golden record.
     * Uses JSONPath {@code [last]} subscript to select the last array element.
     */
    public long queryLatestPriorValue() throws SQLException {
        String sql = "SELECT jsonb_path_query_first("
                + "  golden_record, '$.demographic.prior_values[last]')"
                + " FROM " + TABLE + " WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sampleId);
            try (ResultSet rs = ps.executeQuery()) {
                // Returns 1 if we got a row (prior_values exists), 0 if empty array
                if (rs.next()) {
                    return rs.getString(1) != null ? 1 : 0;
                }
                return 0;
            }
        }
    }

    /**
     * Q3 — Address by type for a known person.
     * Given the composite key, returns the full golden record AND extracts
     * the address element that matches the requested {@code address_type}.
     * Models "fetch full record + locate specific address" in a single round-trip.
     */
    public long queryAddressByType(String addressType) throws SQLException {
        String sql = "SELECT golden_record,"
                + " jsonb_path_query_first("
                + "   golden_record,"
                + "   '$.address[*] ? (@.address_type == $t)',"
                + "   jsonb_build_object('t', ?::text)) AS matched_address"
                + " FROM " + TABLE + " WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, addressType);
            ps.setString(2, sampleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? 1 : 0;
            }
        }
    }

    /**
     * Q4 — SSN exact match.
     * Count records whose current {@code demographic.ssn} equals the given value.
     * Core MDM identity-resolution query — used before creating or merging records.
     * Expression B-tree index on the SSN path gives sub-millisecond lookup.
     */
    public long queryBySsn(String ssn) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + TABLE
                + " WHERE golden_record->'demographic'->>'ssn' = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ssn);
            return fetchCount(ps);
        }
    }

    /**
     * Q5 — Compound last_name + date_of_birth lookup.
     * Count records matching both demographic fields simultaneously.
     * Used when SSN is unavailable — classic MDM probabilistic matching pattern.
     * Compound expression index on {@code (last_name, date_of_birth)}.
     */
    public long queryByLastNameAndDob(String lastName, String dob) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + TABLE
                + " WHERE golden_record->'demographic'->>'last_name' = ?"
                + "   AND golden_record->'demographic'->>'date_of_birth' = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, lastName);
            ps.setString(2, dob);
            return fetchCount(ps);
        }
    }

    /**
     * Q6 — Partial key: {@code global_pid} only.
     * Finds all golden records for a given person ID regardless of customer context.
     * In multi-CID scenarios this returns multiple rows; in our dataset each PID is unique.
     * B-tree index on the relational {@code global_pid} column.
     */
    public long queryByGlobalPid() throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + TABLE + " WHERE global_pid = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sampleGlobalPid);
            return fetchCount(ps);
        }
    }

    /**
     * Q7 — Rule ID lookup.
     * Count records whose current demographic was mastered by a specific rule.
     * Operational query — used to audit which records a rule version affected.
     * Expression B-tree index on {@code demographic.rule_id}.
     */
    public long queryByRuleId(String ruleId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + TABLE
                + " WHERE golden_record->'demographic'->>'rule_id' = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ruleId);
            return fetchCount(ps);
        }
    }

    /**
     * Q8 — Records mastered in the last 30 days.
     * Count recently mastered records — used for downstream sync pipelines and
     * change-detection jobs. B-tree index on native {@code TIMESTAMPTZ} column.
     * Threshold passed as a parameter so all 500 iterations use the same value.
     */
    public long queryMasteredLast30Days(String cutoffIso) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + TABLE
                + " WHERE mastered_date_ts > ?::timestamptz";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cutoffIso);
            return fetchCount(ps);
        }
    }

    /**
     * Q9 — History: prior demographic SSN search.
     * Count records where any entry in {@code demographic.prior_values[*]} has
     * a matching {@code ssn}. Used pre-merge to detect SSN collisions in history.
     * Uses {@code jsonb_path_exists()} with JSONPath variable binding;
     * GIN (jsonb_path_ops) index applies.
     */
    public long queryPriorSsn(String ssn) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + TABLE
                + " WHERE jsonb_path_exists(golden_record,"
                + "   '$.demographic.prior_values[*] ? (@.ssn == $v)',"
                + "   jsonb_build_object('v', ?::text))";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ssn);
            return fetchCount(ps);
        }
    }

    /**
     * Q10 — Range query: records mastered between two timestamps.
     * Count records whose {@code mastered_date_ts} falls within a start–end window.
     * Models downstream sync jobs and audit windows that process a specific time slice.
     * B-tree index on the native {@code TIMESTAMPTZ} column covers both bounds.
     */
    public long queryMasteredBetween(String startIso, String endIso) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + TABLE
                + " WHERE mastered_date_ts >= ?::timestamptz"
                + "   AND mastered_date_ts <= ?::timestamptz";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, startIso);
            ps.setString(2, endIso);
            return fetchCount(ps);
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

    public String getSampleId()        { return sampleId; }
    public String getSampleGlobalPid() { return sampleGlobalPid; }
    public String getSampleLastName()  { return sampleLastName; }
    public String getSampleDob()       { return sampleDob; }

    @Override
    public void close() throws SQLException {
        if (conn != null && !conn.isClosed()) conn.close();
    }
}
