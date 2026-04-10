package com.gsv.benchmark.mongodb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsv.benchmark.model.AuditLog;
import com.gsv.benchmark.model.UserProfile;
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.mongodb.client.model.Filters.*;

/**
 * Handles all MongoDB operations: collection management, seeding, index creation,
 * and the nine benchmark queries.
 *
 * <p>Accepts a pre-built {@link MongoClient} so that the caller (via
 * {@link com.gsv.benchmark.config.AppConfig}) can handle profile-specific
 * client setup (plain URI vs. TLS + DocumentDB settings).
 *
 * <p>Collection {@code users}: documents mirror the UserProfile POJO, with
 * {@code _id} set to the user's UUID string so it matches {@code users.id} in PG.
 * <p>Collection {@code user_audit_logs}: documents have {@code _id = userId} and
 * a nested {@code audit_data} object containing per-field history arrays.
 */
public class MongoRepository implements AutoCloseable {

    private static final String USERS_COLL = "users";
    private static final String AUDIT_COLL = "user_audit_logs";
    private static final int    BATCH_SIZE = 1000;

    private final MongoClient   client;
    private final MongoDatabase db;
    private final ObjectMapper  mapper = new ObjectMapper();

    /** A sample user _id used for the single-user audit lookup benchmark. */
    private String sampleUserId;

    /**
     * Primary constructor — accepts a pre-built MongoClient and database name.
     * Use {@link com.gsv.benchmark.config.AppConfig#buildMongoClient()} and
     * {@link com.gsv.benchmark.config.AppConfig#getMongoDb()} to supply these.
     */
    public MongoRepository(MongoClient client, String dbName) {
        this.client = client;
        this.db     = client.getDatabase(dbName);
    }

    // ------------------------------------------------------------------
    // Collection management
    // ------------------------------------------------------------------

    public void dropAndCreateCollections() {
        db.getCollection(AUDIT_COLL).drop();
        db.getCollection(USERS_COLL).drop();
        // Collections are created implicitly on first insert
        System.out.println("  [Mongo] Collections dropped and ready.");
    }

    public void createIndexes() {
        MongoCollection<Document> users  = db.getCollection(USERS_COLL);
        MongoCollection<Document> audits = db.getCollection(AUDIT_COLL);

        // Golden record indexes
        users.createIndex(Indexes.ascending("subscription_tier"));
        users.createIndex(Indexes.ascending("age"));
        users.createIndex(Indexes.ascending("address.city"));
        users.createIndex(Indexes.ascending("tags"));
        users.createIndex(Indexes.ascending("billing.credit_score"));
        users.createIndex(Indexes.ascending("billing.account_balance"));

        // Audit log indexes
        audits.createIndex(Indexes.ascending("audit_data.subscription_tier.value"));
        audits.createIndex(Indexes.ascending("audit_data.subscription_tier.timestamp"));
        audits.createIndex(Indexes.ascending("audit_data.billing_credit_score.value"));

        System.out.println("  [Mongo] Indexes created.");
    }

    // ------------------------------------------------------------------
    // Seeding
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public void seedUsers(List<UserProfile> users) {
        MongoCollection<Document> coll = db.getCollection(USERS_COLL);
        List<Document> batch = new ArrayList<>(BATCH_SIZE);

        for (UserProfile u : users) {
            // Convert POJO → generic Map → Document
            Map<String, Object> map = mapper.convertValue(u, Map.class);
            Document doc = new Document(map);
            // Use UUID string as _id so it aligns with the PG uuid column
            doc.put("_id", u.getId());
            doc.remove("id");   // don't duplicate
            batch.add(doc);

            if (batch.size() == BATCH_SIZE) {
                coll.insertMany(batch, new InsertManyOptions().ordered(false));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            coll.insertMany(batch, new InsertManyOptions().ordered(false));
        }
        if (!users.isEmpty()) sampleUserId = users.get(0).getId();
        System.out.printf("  [Mongo] Inserted %,d users.%n", users.size());
    }

    @SuppressWarnings("unchecked")
    public void seedAuditLogs(List<AuditLog> logs) {
        MongoCollection<Document> coll = db.getCollection(AUDIT_COLL);
        List<Document> batch = new ArrayList<>(BATCH_SIZE);

        for (AuditLog log : logs) {
            // audit_data is a Map<String, List<AuditEntry>> — convert to Document tree
            Map<String, Object> auditMap = mapper.convertValue(log.getAuditData(), Map.class);
            Document doc = new Document();
            doc.put("_id", log.getUserId());          // _id = userId
            doc.put("audit_data", new Document(auditMap));
            batch.add(doc);

            if (batch.size() == BATCH_SIZE) {
                coll.insertMany(batch, new InsertManyOptions().ordered(false));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            coll.insertMany(batch, new InsertManyOptions().ordered(false));
        }
        System.out.printf("  [Mongo] Inserted %,d audit logs.%n", logs.size());
    }

    // ------------------------------------------------------------------
    // Benchmark queries — users (golden record)
    // ------------------------------------------------------------------

    /** Q1 — Equality filter on subscription_tier. */
    public long queryEqualitySubscriptionTier(String tier) {
        return db.getCollection(USERS_COLL).countDocuments(eq("subscription_tier", tier));
    }

    /** Q2 — Range filter on age. */
    public long queryRangeAge(int min, int max) {
        return db.getCollection(USERS_COLL)
                 .countDocuments(and(gte("age", min), lte("age", max)));
    }

    /** Q3 — Range filter on billing.account_balance. */
    public long queryRangeBalance(double threshold) {
        return db.getCollection(USERS_COLL)
                 .countDocuments(gt("billing.account_balance", threshold));
    }

    /** Q4 — Nested field lookup: address.city. */
    public long queryNestedCity(String city) {
        return db.getCollection(USERS_COLL).countDocuments(eq("address.city", city));
    }

    /** Q5 — Array containment: tags contains a specific tag. */
    public long queryArrayTags(String tag) {
        return db.getCollection(USERS_COLL).countDocuments(eq("tags", tag));
    }

    /** Q6 — Array containment: social.interests contains a specific interest. */
    public long queryArrayInterests(String interest) {
        return db.getCollection(USERS_COLL).countDocuments(eq("social.interests", interest));
    }

    // ------------------------------------------------------------------
    // Benchmark queries — user_audit_logs
    // ------------------------------------------------------------------

    /** Q7 — Fetch full audit history document for a single user. */
    public long queryAuditByUser() {
        Document doc = db.getCollection(AUDIT_COLL)
                         .find(eq("_id", sampleUserId))
                         .first();
        return doc != null ? 1 : 0;
    }

    /** Q8 — Users who were ever on the given subscription tier. */
    public long queryAuditEverOnTier(String tier) {
        return db.getCollection(AUDIT_COLL)
                 .countDocuments(eq("audit_data.subscription_tier.value", tier));
    }

    /** Q9 — Users whose credit score ever exceeded the given threshold. */
    public long queryAuditCreditScoreExceeds(int threshold) {
        return db.getCollection(AUDIT_COLL)
                 .countDocuments(gt("audit_data.billing_credit_score.value", threshold));
    }

    // ------------------------------------------------------------------

    public String getSampleUserId() { return sampleUserId; }

    @Override
    public void close() {
        if (client != null) client.close();
    }
}
