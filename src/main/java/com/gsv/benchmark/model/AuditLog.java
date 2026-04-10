package com.gsv.benchmark.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Attribute-level audit history for a single user.
 * <p>
 * One document per user. {@code auditData} maps each tracked field name
 * to an ordered list of {@link AuditEntry} objects (ascending by timestamp),
 * so the last entry in the list always reflects the current golden-record value.
 * <p>
 * Tracked fields (keys in auditData):
 * <ul>
 *   <li>{@code subscription_tier}        — e.g. "basic" → "premium"</li>
 *   <li>{@code age}                      — increments over time</li>
 *   <li>{@code address_city}             — user may have moved</li>
 *   <li>{@code billing_credit_score}     — numeric changes</li>
 *   <li>{@code billing_account_balance}  — numeric changes</li>
 *   <li>{@code tags}                     — list additions/removals</li>
 * </ul>
 * <p>
 * In PostgreSQL: stored as a single {@code audit_data} JSONB column with
 * {@code user_id} UUID as the primary key (FK → users.id).
 * <p>
 * In MongoDB: stored as a document with {@code _id = userId} and a nested
 * {@code audit_data} object.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuditLog {

    @JsonProperty("user_id")
    private String userId;

    /** Field name → ordered list of historical values (ascending by timestamp). */
    @JsonProperty("audit_data")
    private Map<String, List<AuditEntry>> auditData;

    // ------------------------------------------------------------------
    // Nested type
    // ------------------------------------------------------------------

    /**
     * A single point-in-time snapshot of one tracked field's value.
     * {@code value} may be a String, Integer, Double, or List&lt;String&gt;.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuditEntry {
        private Object value;       // String | Integer | Double | List<String>
        private String timestamp;   // ISO-8601, e.g. "2024-01-15T10:30:00Z"

        public AuditEntry() {}
        public AuditEntry(Object value, String timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }

        public Object getValue()      { return value; }
        public String getTimestamp()  { return timestamp; }
        public void setValue(Object v)      { this.value = v; }
        public void setTimestamp(String t)  { this.timestamp = t; }
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public String getUserId()                               { return userId; }
    public Map<String, List<AuditEntry>> getAuditData()    { return auditData; }

    public void setUserId(String userId)                                    { this.userId = userId; }
    public void setAuditData(Map<String, List<AuditEntry>> auditData)      { this.auditData = auditData; }
}
