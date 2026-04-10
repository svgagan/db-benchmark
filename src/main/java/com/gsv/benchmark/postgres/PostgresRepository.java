package com.gsv.benchmark.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsv.benchmark.model.AuditLog;
import com.gsv.benchmark.model.UserProfile;
import org.postgresql.util.PGobject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.List;

/**
 * Handles all PostgreSQL operations: schema management, seeding, index creation,
 * and the nine benchmark queries.
 *
 * <p>Accepts a pre-built {@link Connection} so that the caller (via
 * {@link com.gsv.benchmark.config.AppConfig}) can handle profile-specific
 * connection setup (plain password vs. IAM auth token + SSL).
 */
public class PostgresRepository implements AutoCloseable {

    private final Connection conn;
    private final ObjectMapper mapper = new ObjectMapper();

    /** A sample user_id used for the single-user audit lookup benchmark. */
    private String sampleUserId;

    /**
     * Primary constructor — accepts a pre-built connection.
     * Use {@link com.gsv.benchmark.config.AppConfig#buildPostgresConnection()}
     * to obtain the connection for the active profile.
     */
    public PostgresRepository(Connection conn) throws SQLException {
        this.conn = conn;
        this.conn.setAutoCommit(false);
    }

    // ------------------------------------------------------------------
    // Schema management
    // ------------------------------------------------------------------

    public void dropAndCreateTables() throws SQLException, IOException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS user_audit_logs");
            st.execute("DROP TABLE IF EXISTS users");
        }
        String schema;
        try (InputStream is = getClass().getResourceAsStream("/schema.sql")) {
            schema = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Statement st = conn.createStatement()) {
            // Split on semicolons; skip blank segments and comment-only segments
            for (String sql : schema.split(";")) {
                // Strip comment lines before checking emptiness
                String trimmed = sql.lines()
                        .filter(line -> !line.stripLeading().startsWith("--"))
                        .reduce("", (a, b) -> a + "\n" + b)
                        .strip();
                if (!trimmed.isEmpty()) st.execute(trimmed);
            }
        }
        conn.commit();
    }

    public void createIndexes() throws SQLException {
        try (Statement st = conn.createStatement()) {
            // Golden record indexes
            st.execute("CREATE INDEX IF NOT EXISTS idx_users_gin     ON users USING GIN (data jsonb_path_ops)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_users_age     ON users (((data->>'age')::int))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_users_city    ON users ((data->'address'->>'city'))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_users_subtier ON users ((data->>'subscription_tier'))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_users_credit  ON users (((data->'billing'->>'credit_score')::int))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_users_balance ON users (((data->'billing'->>'account_balance')::numeric))");
            // Audit log index
            st.execute("CREATE INDEX IF NOT EXISTS idx_audit_gin ON user_audit_logs USING GIN (audit_data jsonb_path_ops)");
        }
        conn.commit();
        System.out.println("  [PG] Indexes created.");
    }

    // ------------------------------------------------------------------
    // Seeding
    // ------------------------------------------------------------------

    public void seedUsers(List<UserProfile> users) throws SQLException {
        String sql = "INSERT INTO users (id, data) VALUES (?::uuid, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int batch = 0;
            for (UserProfile u : users) {
                ps.setString(1, u.getId());
                ps.setObject(2, jsonbObject(u));
                ps.addBatch();
                if (++batch % 1000 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
        conn.commit();
        if (!users.isEmpty()) sampleUserId = users.get(0).getId();
        System.out.printf("  [PG] Inserted %,d users.%n", users.size());
    }

    public void seedAuditLogs(List<AuditLog> logs) throws SQLException {
        String sql = "INSERT INTO user_audit_logs (user_id, audit_data) VALUES (?::uuid, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int batch = 0;
            for (AuditLog log : logs) {
                ps.setString(1, log.getUserId());
                ps.setObject(2, jsonbObject(log.getAuditData()));
                ps.addBatch();
                if (++batch % 1000 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
        conn.commit();
        System.out.printf("  [PG] Inserted %,d audit logs.%n", logs.size());
    }

    // ------------------------------------------------------------------
    // Benchmark queries — users (golden record)
    // ------------------------------------------------------------------

    /** Q1 — Equality filter on subscription_tier. */
    public long queryEqualitySubscriptionTier(String tier) throws SQLException {
        String sql = "SELECT COUNT(*) FROM users WHERE data @> ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, jsonbOf("{\"subscription_tier\":\"" + tier + "\"}"));
            return fetchCount(ps);
        }
    }

    /** Q2 — Range filter on age. */
    public long queryRangeAge(int min, int max) throws SQLException {
        String sql = "SELECT COUNT(*) FROM users WHERE (data->>'age')::int BETWEEN ? AND ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, min);
            ps.setInt(2, max);
            return fetchCount(ps);
        }
    }

    /** Q3 — Range filter on billing.account_balance. */
    public long queryRangeBalance(double threshold) throws SQLException {
        String sql = "SELECT COUNT(*) FROM users WHERE (data->'billing'->>'account_balance')::numeric > ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, threshold);
            return fetchCount(ps);
        }
    }

    /** Q4 — Nested field lookup: address.city. */
    public long queryNestedCity(String city) throws SQLException {
        String sql = "SELECT COUNT(*) FROM users WHERE data->'address'->>'city' = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, city);
            return fetchCount(ps);
        }
    }

    /** Q5 — Array containment: tags. */
    public long queryArrayTags(String tag) throws SQLException {
        String sql = "SELECT COUNT(*) FROM users WHERE data->'tags' @> ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, jsonbOf("\"" + tag + "\""));
            return fetchCount(ps);
        }
    }

    /** Q6 — Array containment: social.interests. */
    public long queryArrayInterests(String interest) throws SQLException {
        String sql = "SELECT COUNT(*) FROM users WHERE data->'social'->'interests' @> ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, jsonbOf("\"" + interest + "\""));
            return fetchCount(ps);
        }
    }

    // ------------------------------------------------------------------
    // Benchmark queries — user_audit_logs
    // ------------------------------------------------------------------

    /** Q7 — Fetch full audit history for a single user. */
    public long queryAuditByUser() throws SQLException {
        String sql = "SELECT audit_data FROM user_audit_logs WHERE user_id = ?::uuid";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sampleUserId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? 1 : 0;
            }
        }
    }

    /** Q8 — Users who were ever on the given subscription tier. */
    public long queryAuditEverOnTier(String tier) throws SQLException {
        // Use JSONPath to check if any element in the subscription_tier array has value = tier
        String sql = "SELECT COUNT(*) FROM user_audit_logs " +
                     "WHERE jsonb_path_exists(audit_data, '$.subscription_tier[*] ? (@.value == $v)', " +
                     "jsonb_build_object('v', ?::text))";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tier);
            return fetchCount(ps);
        }
    }

    /** Q9 — Users whose credit score ever exceeded the threshold. */
    public long queryAuditCreditScoreExceeds(int threshold) throws SQLException {
        // JSONPath: find any entry in billing_credit_score array where value > threshold
        String sql = "SELECT COUNT(*) FROM user_audit_logs " +
                     "WHERE jsonb_path_exists(audit_data, '$.billing_credit_score[*] ? (@.value > $t)', " +
                     "jsonb_build_object('t', ?::int))";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, threshold);
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

    private PGobject jsonbObject(Object obj) {
        try {
            PGobject pg = new PGobject();
            pg.setType("jsonb");
            pg.setValue(mapper.writeValueAsString(obj));
            return pg;
        } catch (Exception e) {
            throw new RuntimeException("JSON serialisation failed", e);
        }
    }

    private PGobject jsonbOf(String raw) {
        try {
            PGobject pg = new PGobject();
            pg.setType("jsonb");
            pg.setValue(raw);
            return pg;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String getSampleUserId() { return sampleUserId; }

    @Override
    public void close() throws SQLException {
        if (conn != null && !conn.isClosed()) conn.close();
    }
}
