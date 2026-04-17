package com.gsv.benchmark.mongodb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsv.benchmark.data.SampleRecord;
import com.gsv.benchmark.model.GoldenPerson;
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/**
 * All MongoDB / DocumentDB operations for the MDM benchmark.
 *
 * <p>Thread-safe: the MongoDB Java driver manages an internal connection pool;
 * all {@link MongoClient} and {@link MongoCollection} methods are thread-safe
 * and can be called concurrently without additional synchronisation.
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
 * <h3>Benchmark queries</h3>
 * <ol>
 *   <li>Q1  — Composite PK fetch — {@code _id = "global_pid|global_cid"}</li>
 *   <li>Q2  — Latest prior_values entry — {@code $slice: -1} on prior_values array</li>
 *   <li>Q3  — Address by type for known person — {@code $elemMatch} projection</li>
 *   <li>Q4  — SSN exact match — {@code demographic.ssn}</li>
 *   <li>Q5  — Compound match — {@code demographic.last_name + date_of_birth}</li>
 *   <li>Q6  — Partial key — {@code global_pid} only</li>
 *   <li>Q7  — Rule lookup — {@code demographic.rule_id}</li>
 *   <li>Q8  — Recent records — {@code mastered_date_ts} in last 30 days</li>
 *   <li>Q9  — History search — {@code demographic.prior_values[*].ssn}</li>
 *   <li>Q10 — Range query: records mastered between two timestamps</li>
 *   <li>Q11 — Single INSERT latency</li>
 *   <li>Q12 — Batch INSERT (100 records)</li>
 *   <li>Q13 — SSN point UPDATE via {@code $set}</li>
 *   <li>Q14 — prior_values array APPEND via {@code $push}</li>
 * </ol>
 */
public class MongoRepository implements AutoCloseable {

    private static final String COLLECTION = "mdm_golden_person";

    private final MongoClient   client;
    private final MongoDatabase db;
    private final ObjectMapper  mapper = new ObjectMapper();

    // Sample values populated by loadSampleValues() after seeding — used by Q1–Q7
    private volatile String sampleId;
    private volatile String sampleGlobalPid;
    private volatile String sampleLastName;
    private volatile String sampleDob;

    public MongoRepository(MongoClient client, String dbName) {
        this.client = client;
        this.db     = client.getDatabase(dbName);
    }

    // ------------------------------------------------------------------
    // Collection management
    // ------------------------------------------------------------------

    public void dropAndCreateCollections() {
        db.getCollection(COLLECTION).drop();
        // Explicitly create the collection — this also creates the database if it does not exist
        db.createCollection(COLLECTION);
        System.out.printf("  [Mongo] Database '%s', collection '%s' created.%n",
                db.getName(), COLLECTION);
    }

    /**
     * Creates all indexes needed for the "indexed" benchmark pass.
     * <ul>
     *   <li>{@code _id} — implicit (always present) — Q1, Q2, Q3</li>
     *   <li>{@code global_pid} — B-tree — Q6</li>
     *   <li>{@code mastered_date_ts} — B-tree — Q8, Q10</li>
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

    /**
     * Inserts {@code chunk} records using MongoDB {@code insertMany} with batches
     * sized to {@code batchSize}.
     * Thread-safe — MongoDB driver manages connection pooling internally.
     */
    @SuppressWarnings("unchecked")
    public void seedChunk(List<GoldenPerson> chunk, int batchSize) {
        MongoCollection<Document> coll = db.getCollection(COLLECTION);
        List<Document> batch = new ArrayList<>(batchSize);

        for (GoldenPerson p : chunk) {
            Map<String, Object> map = mapper.convertValue(p, Map.class);
            Document doc = new Document(map);
            doc.put("_id", p.getId());
            doc.remove("id");
            batch.add(doc);

            if (batch.size() == batchSize) {
                coll.insertMany(batch, new InsertManyOptions().ordered(false));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            coll.insertMany(batch, new InsertManyOptions().ordered(false));
        }
    }

    /**
     * Loads sample values from the database for use in benchmark query parameters.
     * Must be called after seeding completes, before running benchmarks.
     */
    public void loadSampleValues() {
        // Find a document that has at least one prior_values entry
        Document filter = new Document("demographic.prior_values.0", new Document("$exists", true));
        Document doc = db.getCollection(COLLECTION)
                         .find(filter)
                         .projection(Projections.include("_id", "global_pid",
                                 "demographic.last_name", "demographic.date_of_birth"))
                         .first();

        if (doc == null) {
            throw new IllegalStateException("[Mongo] Could not load sample values — is the collection seeded?");
        }

        sampleId        = doc.getString("_id");
        sampleGlobalPid = doc.getString("global_pid");
        Document demo   = doc.get("demographic", Document.class);
        sampleLastName  = demo != null ? demo.getString("last_name")    : null;
        sampleDob       = demo != null ? demo.getString("date_of_birth") : null;

        System.out.printf("  [Mongo] Sample values loaded (id=%.40s…)%n", sampleId);
    }

    // ------------------------------------------------------------------
    // Q1–Q10: SELECT benchmarks
    // ------------------------------------------------------------------

    /** Q1 — Composite PK fetch (sequential benchmark — uses stored sampleId). */
    public long queryByPrimaryKey() { return queryByPrimaryKey(sampleId); }

    /** Q1 — Composite PK fetch with explicit id (stress test). */
    public long queryByPrimaryKey(String id) {
        Document doc = db.getCollection(COLLECTION).find(eq("_id", id)).first();
        return doc != null ? 1 : 0;
    }

    /** Q2 — Latest prior_values entry (sequential benchmark — uses stored sampleId). */
    public long queryLatestPriorValue() { return queryLatestPriorValue(sampleId); }

    /** Q2 — Latest prior_values entry with explicit id (stress test). */
    public long queryLatestPriorValue(String id) {
        Document doc = db.getCollection(COLLECTION)
                         .find(eq("_id", id))
                         .projection(Projections.slice("demographic.prior_values", -1))
                         .first();
        if (doc == null) return 0;
        Document demographic = doc.get("demographic", Document.class);
        if (demographic == null) return 0;
        List<?> priorValues = demographic.getList("prior_values", Object.class);
        return (priorValues != null && !priorValues.isEmpty()) ? 1 : 0;
    }

    /** Q3 — Address by type (sequential benchmark — uses stored sampleId). */
    public long queryAddressByType(String addressType) { return queryAddressByType(sampleId, addressType); }

    /** Q3 — Address by type with explicit id (stress test). */
    public long queryAddressByType(String id, String addressType) {
        Document doc = db.getCollection(COLLECTION)
                         .find(eq("_id", id))
                         .projection(Projections.elemMatch("address", eq("address_type", addressType)))
                         .first();
        if (doc == null) return 0;
        List<?> address = doc.getList("address", Object.class);
        return (address != null && !address.isEmpty()) ? 1 : 0;
    }

    /**
     * Q4 — SSN exact match.
     * Count records whose current {@code demographic.ssn} equals the given value.
     */
    public long queryBySsn(String ssn) {
        return db.getCollection(COLLECTION)
                 .countDocuments(eq("demographic.ssn", ssn));
    }

    /**
     * Q5 — Compound last_name + date_of_birth lookup.
     */
    public long queryByLastNameAndDob(String lastName, String dob) {
        return db.getCollection(COLLECTION)
                 .countDocuments(and(
                         eq("demographic.last_name", lastName),
                         eq("demographic.date_of_birth", dob)));
    }

    /** Q6 — Partial key (sequential benchmark — uses stored sampleGlobalPid). */
    public long queryByGlobalPid() { return queryByGlobalPid(sampleGlobalPid); }

    /** Q6 — Partial key with explicit pid (stress test). */
    public long queryByGlobalPid(String globalPid) {
        return db.getCollection(COLLECTION).countDocuments(eq("global_pid", globalPid));
    }

    /**
     * Q7 — Rule ID lookup.
     */
    public long queryByRuleId(String ruleId) {
        return db.getCollection(COLLECTION)
                 .countDocuments(eq("demographic.rule_id", ruleId));
    }

    /**
     * Q8 — Records mastered in the last 30 days.
     * ISO-8601 strings sort lexicographically; B-tree index on {@code mastered_date_ts} applies.
     */
    public long queryMasteredLast30Days(String cutoffIso) {
        return db.getCollection(COLLECTION)
                 .countDocuments(gt("mastered_date_ts", cutoffIso));
    }

    /**
     * Q9 — History: prior demographic SSN search.
     * Count records where any entry in {@code demographic.prior_values[*]} has
     * a matching {@code ssn}. Multikey index on {@code demographic.prior_values.ssn}.
     */
    public long queryPriorSsn(String ssn) {
        return db.getCollection(COLLECTION)
                 .countDocuments(eq("demographic.prior_values.ssn", ssn));
    }

    /**
     * Q10 — Range query: records mastered between two timestamps.
     */
    public long queryMasteredBetween(String startIso, String endIso) {
        return db.getCollection(COLLECTION)
                 .countDocuments(and(
                         gte("mastered_date_ts", startIso),
                         lte("mastered_date_ts", endIso)));
    }

    // ------------------------------------------------------------------
    // Q11–Q12: INSERT benchmarks
    // ------------------------------------------------------------------

    /**
     * Q11 — Single INSERT latency.
     * Inserts one record and returns elapsed nanoseconds.
     * Caller is responsible for cleanup via {@link #deleteByIds}.
     */
    @SuppressWarnings("unchecked")
    public long insertSingle(GoldenPerson person) {
        Map<String, Object> map = mapper.convertValue(person, Map.class);
        Document doc = new Document(map);
        doc.put("_id", person.getId());
        doc.remove("id");

        MongoCollection<Document> coll = db.getCollection(COLLECTION);
        long t0 = System.nanoTime();
        coll.insertOne(doc);
        return System.nanoTime() - t0;
    }

    /**
     * Q12 — Batch INSERT.
     * Inserts {@code batch.size()} records in a single {@code insertMany} call
     * and returns elapsed nanoseconds.
     * Caller is responsible for cleanup via {@link #deleteByIds}.
     */
    @SuppressWarnings("unchecked")
    public long insertBatch(List<GoldenPerson> batch) {
        List<Document> docs = new ArrayList<>(batch.size());
        for (GoldenPerson p : batch) {
            Map<String, Object> map = mapper.convertValue(p, Map.class);
            Document doc = new Document(map);
            doc.put("_id", p.getId());
            doc.remove("id");
            docs.add(doc);
        }

        MongoCollection<Document> coll = db.getCollection(COLLECTION);
        long t0 = System.nanoTime();
        coll.insertMany(docs, new InsertManyOptions().ordered(false));
        return System.nanoTime() - t0;
    }

    /**
     * Deletes records by their {@code _id} values.
     * Called after INSERT benchmark iterations to keep document count stable.
     */
    public void deleteByIds(List<String> ids) {
        if (ids.isEmpty()) return;
        db.getCollection(COLLECTION).deleteMany(in("_id", ids));
    }

    // ------------------------------------------------------------------
    // Q13–Q14: UPDATE benchmarks
    // ------------------------------------------------------------------

    /**
     * Q13 — SSN point UPDATE.
     * Updates {@code demographic.ssn} for a single document using {@code $set}.
     * Models a scalar field correction — the most common MDM point update.
     * Returns elapsed nanoseconds.
     */
    public long updateSsnById(String id, String newSsn) {
        MongoCollection<Document> coll = db.getCollection(COLLECTION);
        long t0 = System.nanoTime();
        coll.updateOne(eq("_id", id), set("demographic.ssn", newSsn));
        return System.nanoTime() - t0;
    }

    /**
     * Q14 — prior_values array APPEND.
     * Appends a new {@link GoldenPerson.DemographicHistory} entry to the
     * {@code demographic.prior_values} array using {@code $push}.
     * Models the most common MDM write: pushing the previous winner onto the history stack.
     * Returns elapsed nanoseconds.
     */
    @SuppressWarnings("unchecked")
    public long appendDemographicHistory(String id, GoldenPerson.DemographicHistory entry) {
        Map<String, Object> entryMap = mapper.convertValue(entry, Map.class);
        Document entryDoc = new Document(entryMap);

        MongoCollection<Document> coll = db.getCollection(COLLECTION);
        long t0 = System.nanoTime();
        coll.updateOne(eq("_id", id), push("demographic.prior_values", entryDoc));
        return System.nanoTime() - t0;
    }

    /**
     * Loads a random pool of {@link SampleRecord}s for use as randomised query
     * parameters in the concurrent stress test.
     *
     * <p>Only documents that have at least one {@code prior_values} entry are
     * included so Q2 always returns a result. Using a diverse pool prevents any
     * single document from staying hot in the WiredTiger cache.
     */
    public List<SampleRecord> loadSamplePool(int count) {
        List<SampleRecord> pool = new ArrayList<>(count);
        db.getCollection(COLLECTION)
          .aggregate(List.of(
              new Document("$match",
                  new Document("demographic.prior_values.0", new Document("$exists", true))),
              new Document("$sample", new Document("size", count)),
              new Document("$project", new Document("_id", 1)
                  .append("global_pid", 1)
                  .append("demographic.last_name", 1)
                  .append("demographic.date_of_birth", 1))))
          .forEach(doc -> {
              Document demo = doc.get("demographic", Document.class);
              pool.add(new SampleRecord(
                      doc.getString("_id"),
                      doc.getString("global_pid"),
                      demo != null ? demo.getString("last_name")     : null,
                      demo != null ? demo.getString("date_of_birth") : null));
          });
        System.out.printf("  [Mongo] Sample pool loaded (%,d records for stress-test randomisation).%n", pool.size());
        return pool;
    }

    /**
     * Q15 — Scalar projection: fetch only four demographic scalar fields.
     *
     * <p>MongoDB's field projection does NOT have a TOAST equivalent — WiredTiger
     * reads the entire document from its B-tree page regardless of the projection,
     * then filters fields in the server before sending to the client. For small
     * documents the difference vs. Q1 is network bandwidth only.  For large
     * documents, MongoDB 5+ column-store (time-series) or Atlas Search can project
     * without reading the full document, but that is not available in DocumentDB.
     * The Q1-vs-Q15 delta here therefore measures network payload reduction, not
     * storage I/O savings.
     */
    public long queryScalarProjection() {
        Document doc = db.getCollection(COLLECTION)
                         .find(eq("_id", sampleId))
                         .projection(Projections.fields(
                                 Projections.include(
                                         "demographic.first_name",
                                         "demographic.last_name",
                                         "demographic.ssn",
                                         "demographic.date_of_birth"),
                                 Projections.excludeId()))
                         .first();
        return doc != null ? 1 : 0;
    }

    /**
     * Loads a random sample of document IDs from the seeded collection using
     * the {@code $sample} aggregation stage.
     * Used to build the UPDATE benchmark target pool — IDs are cycled across Q13/Q14.
     */
    public List<String> loadUpdateSampleIds(int count) {
        List<String> ids = new ArrayList<>(count);
        // $sample returns full documents; we only read _id from each
        db.getCollection(COLLECTION)
          .aggregate(List.of(
              new Document("$sample",  new Document("size", count)),
              new Document("$project", new Document("_id", 1))))
          .forEach(doc -> ids.add(doc.getString("_id")));
        return ids;
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
        if (client != null) client.close();
    }
}
