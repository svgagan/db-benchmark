package com.gsv.benchmark.mongodb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsv.benchmark.model.GoldenPerson;
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.mongodb.client.model.Filters.*;

/**
 * All MongoDB / DocumentDB operations for the MDM benchmark.
 *
 * <h3>Collection: mdm_golden_person</h3>
 * <pre>
 *   _id              "global_pid|global_cid"   (String — composite PK)
 *   global_pid       String
 *   global_cid       String
 *   mastered_date_ts String  (ISO-8601)
 *   demographic      { first_name, last_name, date_of_birth, ssn,
 *                      mastered_date_ts, rule_id, rule_version,
 *                      prior_values: [...] }
 *   address          [{ address_type, address1, address2, city, state,
 *                       zipcode, country, mastered_date_ts,
 *                       rule_id, rule_version, prior_values: [...] }]
 * </pre>
 *
 * <h3>9 Benchmark queries</h3>
 * <ol>
 *   <li>Q1 — Composite PK fetch — {@code _id = "global_pid|global_cid"}</li>
 *   <li>Q2 — Latest prior_values entry — {@code $slice: -1} on prior_values array</li>
 *   <li>Q3 — Address by type for known person — {@code $elemMatch} projection</li>
 *   <li>Q4 — SSN exact match — {@code demographic.ssn}</li>
 *   <li>Q5 — Compound match — {@code demographic.last_name + date_of_birth}</li>
 *   <li>Q6 — Partial key — {@code global_pid} only</li>
 *   <li>Q7 — Rule lookup — {@code demographic.rule_id}</li>
 *   <li>Q8 — Recent records — {@code mastered_date_ts} in last 30 days</li>
 *   <li>Q9 — History search — {@code demographic.prior_values[*].ssn}</li>
 * </ol>
 */
public class MongoRepository implements AutoCloseable {

    private static final String COLLECTION = "mdm_golden_person";
    private static final int    BATCH_SIZE = 1_000;

    private final MongoClient   client;
    private final MongoDatabase db;
    private final ObjectMapper  mapper = new ObjectMapper();

    // Sample values captured during seeding — mirrors PostgresRepository
    private String sampleId;
    private String sampleGlobalPid;
    private String sampleLastName;
    private String sampleDob;

    public MongoRepository(MongoClient client, String dbName) {
        this.client = client;
        this.db     = client.getDatabase(dbName);
    }

    // ------------------------------------------------------------------
    // Collection management
    // ------------------------------------------------------------------

    public void dropAndCreateCollections() {
        db.getCollection(COLLECTION).drop();
        System.out.println("  [Mongo] Collection mdm_golden_person dropped and ready.");
    }

    /**
     * Indexes created for the "indexed" benchmark pass:
     * <ul>
     *   <li>{@code _id} — implicit (always present) — Q1, Q2, Q3</li>
     *   <li>{@code global_pid} — B-tree — Q6</li>
     *   <li>{@code mastered_date_ts} — B-tree — Q8</li>
     *   <li>{@code demographic.ssn} — B-tree — Q4</li>
     *   <li>{@code demographic.last_name + date_of_birth} — compound — Q5</li>
     *   <li>{@code demographic.rule_id} — B-tree — Q7</li>
     *   <li>{@code demographic.prior_values.ssn} — multikey — Q9</li>
     * </ul>
     */
    public void createIndexes() {
        MongoCollection<Document> coll = db.getCollection(COLLECTION);

        coll.createIndex(Indexes.ascending("global_pid"));
        coll.createIndex(Indexes.ascending("mastered_date_ts"));
        coll.createIndex(Indexes.ascending("demographic.ssn"));
        coll.createIndex(Indexes.compoundIndex(
                Indexes.ascending("demographic.last_name"),
                Indexes.ascending("demographic.date_of_birth")));
        coll.createIndex(Indexes.ascending("demographic.rule_id"));
        coll.createIndex(Indexes.ascending("demographic.prior_values.ssn"));

        System.out.println("  [Mongo] Indexes created.");
    }

    // ------------------------------------------------------------------
    // Seeding
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public void seedPersons(List<GoldenPerson> persons) {
        MongoCollection<Document> coll = db.getCollection(COLLECTION);
        List<Document> batch = new ArrayList<>(BATCH_SIZE);

        for (GoldenPerson p : persons) {
            Map<String, Object> map = mapper.convertValue(p, Map.class);
            Document doc = new Document(map);
            doc.put("_id", p.getId());
            doc.remove("id");
            batch.add(doc);

            if (batch.size() == BATCH_SIZE) {
                coll.insertMany(batch, new InsertManyOptions().ordered(false));
                batch.clear();
            }

            // Capture a sample record that has prior_values — same criterion as PG repo
            if (sampleId == null
                    && p.getDemographic().getPriorValues() != null
                    && !p.getDemographic().getPriorValues().isEmpty()) {
                sampleId        = p.getId();
                sampleGlobalPid = p.getGlobalPid();
                sampleLastName  = p.getDemographic().getLastName();
                sampleDob       = p.getDemographic().getDateOfBirth();
            }
        }
        if (!batch.isEmpty()) {
            coll.insertMany(batch, new InsertManyOptions().ordered(false));
        }
        System.out.printf("  [Mongo] Inserted %,d golden person records.%n", persons.size());
    }

    // ------------------------------------------------------------------
    // Benchmark queries
    // ------------------------------------------------------------------

    /**
     * Q1 — Composite PK fetch.
     * Retrieves the full golden person document by its {@code _id}
     * ({@code "global_pid|global_cid"}).  Most frequent MDM operation.
     */
    public long queryByPrimaryKey() {
        Document doc = db.getCollection(COLLECTION)
                         .find(eq("_id", sampleId))
                         .first();
        return doc != null ? 1 : 0;
    }

    /**
     * Q2 — Latest prior_values entry (previous winner).
     * Retrieves only the last element of {@code demographic.prior_values[]}
     * for a known record using {@code $slice: -1} projection.
     * Returns the full document with prior_values truncated to its last entry.
     */
    public long queryLatestPriorValue() {
        Document doc = db.getCollection(COLLECTION)
                         .find(eq("_id", sampleId))
                         .projection(Projections.slice("demographic.prior_values", -1))
                         .first();
        if (doc == null) return 0;
        // Check that the prior_values field is present and non-empty
        Document demographic = doc.get("demographic", Document.class);
        if (demographic == null) return 0;
        List<?> priorValues = demographic.getList("prior_values", Object.class);
        return (priorValues != null && !priorValues.isEmpty()) ? 1 : 0;
    }

    /**
     * Q3 — Address by type for a known person.
     * Fetches the full document and projects the {@code address} array using
     * {@code $elemMatch} so only the matching {@code address_type} entry is returned.
     * Models "full record + targeted address sub-document" in one round-trip.
     */
    public long queryAddressByType(String addressType) {
        Document doc = db.getCollection(COLLECTION)
                         .find(eq("_id", sampleId))
                         .projection(Projections.elemMatch("address", eq("address_type", addressType)))
                         .first();
        if (doc == null) return 0;
        List<?> address = doc.getList("address", Object.class);
        return (address != null && !address.isEmpty()) ? 1 : 0;
    }

    /**
     * Q4 — SSN exact match.
     * Count records whose current {@code demographic.ssn} equals the given value.
     * Core MDM identity-resolution query.
     */
    public long queryBySsn(String ssn) {
        return db.getCollection(COLLECTION)
                 .countDocuments(eq("demographic.ssn", ssn));
    }

    /**
     * Q5 — Compound last_name + date_of_birth lookup.
     * Count records matching both fields simultaneously.
     * Classic MDM probabilistic matching when SSN is not available.
     */
    public long queryByLastNameAndDob(String lastName, String dob) {
        return db.getCollection(COLLECTION)
                 .countDocuments(and(
                         eq("demographic.last_name", lastName),
                         eq("demographic.date_of_birth", dob)));
    }

    /**
     * Q6 — Partial key: {@code global_pid} only.
     * Find all golden records for a given person ID across all customer contexts.
     */
    public long queryByGlobalPid() {
        return db.getCollection(COLLECTION)
                 .countDocuments(eq("global_pid", sampleGlobalPid));
    }

    /**
     * Q7 — Rule ID lookup.
     * Count records whose current demographic was mastered by a specific rule.
     */
    public long queryByRuleId(String ruleId) {
        return db.getCollection(COLLECTION)
                 .countDocuments(eq("demographic.rule_id", ruleId));
    }

    /**
     * Q8 — Records mastered in the last 30 days.
     * Count recently mastered records for downstream sync and change-detection.
     * String comparison on ISO-8601 works lexicographically; B-tree index applies.
     */
    public long queryMasteredLast30Days(String cutoffIso) {
        return db.getCollection(COLLECTION)
                 .countDocuments(gt("mastered_date_ts", cutoffIso));
    }

    /**
     * Q9 — History: prior demographic SSN search.
     * Count records where any entry in {@code demographic.prior_values[*]} has
     * a matching {@code ssn}.  Multikey index on {@code demographic.prior_values.ssn}.
     */
    public long queryPriorSsn(String ssn) {
        return db.getCollection(COLLECTION)
                 .countDocuments(eq("demographic.prior_values.ssn", ssn));
    }

    /**
     * Q10 — Range query: records mastered between two timestamps.
     * Count records whose {@code mastered_date_ts} falls within a start–end window.
     * ISO-8601 strings sort lexicographically so the B-tree index on
     * {@code mastered_date_ts} covers both bounds.
     */
    public long queryMasteredBetween(String startIso, String endIso) {
        return db.getCollection(COLLECTION)
                 .countDocuments(and(
                         gte("mastered_date_ts", startIso),
                         lte("mastered_date_ts", endIso)));
    }

    // ------------------------------------------------------------------

    public String getSampleId()        { return sampleId; }
    public String getSampleGlobalPid() { return sampleGlobalPid; }
    public String getSampleLastName()  { return sampleLastName; }
    public String getSampleDob()       { return sampleDob; }

    @Override
    public void close() {
        if (client != null) client.close();
    }
}
