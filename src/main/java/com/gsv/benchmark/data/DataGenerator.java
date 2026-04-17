package com.gsv.benchmark.data;

import com.gsv.benchmark.model.GoldenPerson;
import com.gsv.benchmark.model.GoldenPerson.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Consumer;

/**
 * Generates synthetic MDM golden person records for benchmarking.
 *
 * <p>Each instance owns its own {@link Random} seeded at construction — safe to use
 * from multiple threads concurrently when each thread creates its own instance.
 *
 * <h3>Design choices</h3>
 * <ul>
 *   <li><b>SSN pool</b> — 1,000 values shared across all records, giving
 *       ~100 matches per SSN at 100 k scale, ~1,000 at 1 M scale.</li>
 *   <li><b>Rule IDs</b> — RULE_001 … RULE_010 (uniform), ~10 % of records each.</li>
 *   <li><b>City weights</b> — population-weighted; "New York" ≈ 12 %.</li>
 *   <li><b>mastered_date_ts</b> — uniform over the past two years so the
 *       last-30-day filter returns ~4 % and the 6 mo→3 mo range ~12.5 %.</li>
 *   <li><b>prior_values</b> — 0–3 demographic and 0–2 per address.</li>
 * </ul>
 */
public class DataGenerator {

    // ------------------------------------------------------------------
    // Instance state — each instance has its own Random (thread-safe usage)
    // ------------------------------------------------------------------

    private final Random rng;

    public DataGenerator(long seed) {
        this.rng = new Random(seed);
    }

    /** Convenience constructor using the canonical seed 42. */
    public DataGenerator() {
        this(42L);
    }

    // ------------------------------------------------------------------
    // Reference data — names
    // ------------------------------------------------------------------

    private static final String[] FIRST_NAMES = {
        "James", "Mary", "John", "Patricia", "Robert", "Jennifer", "Michael", "Linda",
        "William", "Barbara", "David", "Elizabeth", "Richard", "Susan", "Joseph", "Jessica",
        "Thomas", "Sarah", "Charles", "Karen", "Christopher", "Lisa", "Daniel", "Nancy",
        "Matthew", "Betty", "Anthony", "Margaret", "Mark", "Sandra", "Donald", "Ashley",
        "Steven", "Dorothy", "Paul", "Kimberly", "Andrew", "Emily", "Kenneth", "Donna"
    };

    private static final String[] LAST_NAMES = {
        "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis",
        "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez", "Wilson", "Anderson",
        "Thomas", "Taylor", "Moore", "Jackson", "Martin", "Lee", "Perez", "Thompson",
        "White", "Harris", "Sanchez", "Clark", "Ramirez", "Lewis", "Robinson",
        "Walker", "Young", "Allen", "King", "Wright", "Scott", "Torres", "Nguyen",
        "Hill", "Flores", "Green", "Adams", "Nelson", "Baker", "Hall", "Rivera",
        "Campbell", "Mitchell", "Carter", "Roberts"
    };

    // ------------------------------------------------------------------
    // Reference data — cities (city, state, zipcode, weight)
    // ------------------------------------------------------------------

    private static final Object[][] CITIES = {
        {"New York",      "NY", "10001", 12},
        {"Los Angeles",   "CA", "90001", 10},
        {"Chicago",       "IL", "60601",  8},
        {"Houston",       "TX", "77001",  7},
        {"Phoenix",       "AZ", "85001",  6},
        {"Philadelphia",  "PA", "19101",  5},
        {"San Antonio",   "TX", "78201",  5},
        {"San Diego",     "CA", "92101",  4},
        {"Dallas",        "TX", "75201",  5},
        {"San Jose",      "CA", "95101",  4},
        {"Austin",        "TX", "78701",  4},
        {"Jacksonville",  "FL", "32099",  4},
        {"Fort Worth",    "TX", "76101",  4},
        {"Columbus",      "OH", "43085",  3},
        {"Charlotte",     "NC", "28201",  3},
        {"Indianapolis",  "IN", "46201",  3},
        {"San Francisco", "CA", "94101",  3},
        {"Seattle",       "WA", "98101",  3},
        {"Denver",        "CO", "80201",  3},
        {"Boston",        "MA", "02101",  4}
    };

    private static final int[] CITY_CUMULATIVE;
    static {
        CITY_CUMULATIVE = new int[CITIES.length];
        int sum = 0;
        for (int i = 0; i < CITIES.length; i++) {
            sum += (int) CITIES[i][3];
            CITY_CUMULATIVE[i] = sum;
        }
    }

    // ------------------------------------------------------------------
    // Reference data — SSN pool (1,000 values)
    // ------------------------------------------------------------------

    private static final String[] SSN_POOL;
    static {
        SSN_POOL = new String[1_000];
        Random ssnRng = new Random(7);
        for (int i = 0; i < SSN_POOL.length; i++) {
            SSN_POOL[i] = String.format("%03d-%02d-%04d",
                    100 + ssnRng.nextInt(800),
                    10  + ssnRng.nextInt(89),
                    1000 + ssnRng.nextInt(8999));
        }
    }

    // ------------------------------------------------------------------
    // Reference data — rules, address types, street names
    // ------------------------------------------------------------------

    private static final String[] RULE_IDS = {
        "RULE_001", "RULE_002", "RULE_003", "RULE_004", "RULE_005",
        "RULE_006", "RULE_007", "RULE_008", "RULE_009", "RULE_010"
    };
    private static final String[] RULE_VERSIONS  = {"v1.0", "v1.1", "v2.0", "v2.1", "v3.0"};
    private static final String[] ADDRESS_TYPES  = {"PRIMARY", "SECONDARY", "BILLING"};
    private static final String[] STREET_NAMES   = {
        "Main St", "Oak Ave", "Maple Dr", "Cedar Ln", "Pine Rd", "Elm St",
        "Washington Blvd", "Park Ave", "Lake Dr", "River Rd", "Hill St",
        "Forest Way", "Sunset Blvd", "Highland Ave", "Meadow Ln"
    };

    // ------------------------------------------------------------------
    // Public API — generation
    // ------------------------------------------------------------------

    /**
     * Generates {@code count} records in memory (suitable for small counts ≤ 200 k).
     * For large counts use {@link #generatePersonsChunked}.
     */
    public List<GoldenPerson> generatePersons(int count) {
        List<GoldenPerson> persons = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            persons.add(buildPerson());
        }
        return persons;
    }

    /**
     * Generates {@code totalCount} records in chunks of {@code chunkSize}, passing
     * each chunk to {@code chunkConsumer} before generating the next one.
     * At most {@code chunkSize} records are live in heap at any time.
     *
     * <p>Designed for parallel seeding: each thread creates its own
     * {@code DataGenerator(seed)} and calls this method on its own slice.
     */
    public void generatePersonsChunked(int totalCount, int chunkSize,
                                       Consumer<List<GoldenPerson>> chunkConsumer) {
        List<GoldenPerson> chunk = new ArrayList<>(chunkSize);
        for (int i = 0; i < totalCount; i++) {
            chunk.add(buildPerson());
            if (chunk.size() == chunkSize) {
                chunkConsumer.accept(chunk);
                chunk = new ArrayList<>(chunkSize);
            }
        }
        if (!chunk.isEmpty()) {
            chunkConsumer.accept(chunk);
        }
    }

    /**
     * Generates a small pool of records kept entirely in memory for INSERT benchmarking.
     * {@code poolSize} should be ≤ 100,000 (≈ 800 MB at 100 k).
     */
    public static List<GoldenPerson> generateInsertPool(int poolSize) {
        return new DataGenerator(99L).generatePersons(poolSize);
    }

    /**
     * Clones a {@link GoldenPerson} with fresh {@code id}, {@code globalPid},
     * and {@code globalCid} UUIDs. All other fields are preserved.
     * Used during INSERT benchmark iterations to avoid PK collisions when
     * cycling through the insert pool.
     */
    public static GoldenPerson withNewId(GoldenPerson source) {
        GoldenPerson p = new GoldenPerson();
        String pid = UUID.randomUUID().toString();
        String cid = UUID.randomUUID().toString();
        p.setGlobalPid(pid);
        p.setGlobalCid(cid);
        p.setId(pid + "|" + cid);
        p.setMasteredDateTs(source.getMasteredDateTs());
        p.setDemographic(source.getDemographic());
        p.setAddress(source.getAddress());
        return p;
    }

    /**
     * Builds a single realistic {@link DemographicHistory} entry for UPDATE benchmark Q14.
     * Represents a prior mastering snapshot being pushed onto the prior_values stack.
     */
    public static DemographicHistory buildSampleHistory() {
        Random r = new Random(77L);
        DemographicHistory h = new DemographicHistory();
        h.setFirstName(FIRST_NAMES[r.nextInt(FIRST_NAMES.length)]);
        h.setLastName(LAST_NAMES[r.nextInt(LAST_NAMES.length)]);
        h.setDateOfBirth("1985-06-15");
        h.setSsn(SSN_POOL[1]);   // known pool value
        h.setMasteredDateTs(Instant.now().minus(400, ChronoUnit.DAYS)
                                    .truncatedTo(ChronoUnit.SECONDS).toString());
        h.setRuleId("RULE_002");
        h.setRuleVersion("v1.0");
        h.setLostAgainstRule("RULE_003");
        return h;
    }

    // ------------------------------------------------------------------
    // Exposed constants for benchmark query parameter binding
    // ------------------------------------------------------------------

    /** SSN guaranteed to exist in all generated datasets. */
    public static String sampleSsn() { return SSN_POOL[0]; }

    /**
     * Returns a random SSN from the pool using the caller's {@link Random} instance.
     * Use this in stress-test workers so each operation hits a different SSN,
     * preventing a single hot page from dominating the buffer cache.
     */
    public static String randomSsn(Random rng) { return SSN_POOL[rng.nextInt(SSN_POOL.length)]; }

    /**
     * Returns a random rule ID (RULE_001 … RULE_010) using the caller's RNG.
     * Distributes Q7 lookups evenly across all ten rules during stress testing.
     */
    public static String randomRuleId(Random rng) { return RULE_IDS[rng.nextInt(RULE_IDS.length)]; }

    /** City guaranteed to appear in address prior_values. */
    public static String samplePriorCity() { return (String) CITIES[2][0]; }

    // ------------------------------------------------------------------
    // Person builder
    // ------------------------------------------------------------------

    private GoldenPerson buildPerson() {
        GoldenPerson p = new GoldenPerson();
        String pid = UUID.randomUUID().toString();
        String cid = UUID.randomUUID().toString();
        p.setGlobalPid(pid);
        p.setGlobalCid(cid);
        p.setId(pid + "|" + cid);

        Instant masteredInstant = randomInstantWithinDays(730);
        p.setMasteredDateTs(isoTs(masteredInstant));
        p.setDemographic(buildDemographic(masteredInstant));
        p.setAddress(buildAddresses(masteredInstant));
        return p;
    }

    // ------------------------------------------------------------------
    // Demographic builder
    // ------------------------------------------------------------------

    private Demographic buildDemographic(Instant masteredInstant) {
        Demographic d = new Demographic();
        d.setFirstName(pick(FIRST_NAMES));
        d.setLastName(pick(LAST_NAMES));
        d.setDateOfBirth(randomDob());
        d.setSsn(pick(SSN_POOL));
        d.setMasteredDateTs(isoTs(masteredInstant));
        d.setRuleId(pick(RULE_IDS));
        d.setRuleVersion(pick(RULE_VERSIONS));
        d.setPriorValues(buildDemographicHistory(masteredInstant));
        return d;
    }

    private List<DemographicHistory> buildDemographicHistory(Instant latestInstant) {
        int count = rng.nextInt(4);
        List<DemographicHistory> history = new ArrayList<>(count);
        Instant cursor = latestInstant;
        for (int i = count; i > 0; i--) {
            cursor = cursor.minus(30 + rng.nextInt(180), ChronoUnit.DAYS);
            DemographicHistory h = new DemographicHistory();
            h.setFirstName(pick(FIRST_NAMES));
            h.setLastName(pick(LAST_NAMES));
            h.setDateOfBirth(randomDob());
            h.setSsn(pick(SSN_POOL));
            h.setMasteredDateTs(isoTs(cursor));
            h.setRuleId(pick(RULE_IDS));
            h.setRuleVersion(pick(RULE_VERSIONS));
            h.setLostAgainstRule(pick(RULE_IDS));
            history.add(0, h);
        }
        return history;
    }

    // ------------------------------------------------------------------
    // Address builders
    // ------------------------------------------------------------------

    private List<GoldenPerson.Address> buildAddresses(Instant masteredInstant) {
        List<GoldenPerson.Address> addresses = new ArrayList<>();
        addresses.add(buildAddress("PRIMARY", masteredInstant));
        if (rng.nextInt(100) < 40) addresses.add(buildAddress("SECONDARY", masteredInstant));
        if (rng.nextInt(100) < 25) addresses.add(buildAddress("BILLING",   masteredInstant));
        return addresses;
    }

    private GoldenPerson.Address buildAddress(String addressType, Instant masteredInstant) {
        Object[] cityRow = pickCity();
        GoldenPerson.Address a = new GoldenPerson.Address();
        a.setAddressType(addressType);
        a.setAddress1(randomStreetAddress());
        a.setAddress2(rng.nextInt(100) < 20 ? "Apt " + (100 + rng.nextInt(900)) : "");
        a.setCity((String)   cityRow[0]);
        a.setState((String)  cityRow[1]);
        a.setZipcode((String)cityRow[2]);
        a.setCountry("US");
        a.setMasteredDateTs(isoTs(masteredInstant));
        a.setRuleId(pick(RULE_IDS));
        a.setRuleVersion(pick(RULE_VERSIONS));
        a.setPriorValues(buildAddressHistory(addressType, masteredInstant));
        return a;
    }

    private List<AddressHistory> buildAddressHistory(String addressType, Instant latestInstant) {
        int count = rng.nextInt(3);
        List<AddressHistory> history = new ArrayList<>(count);
        Instant cursor = latestInstant;
        for (int i = count; i > 0; i--) {
            cursor = cursor.minus(30 + rng.nextInt(180), ChronoUnit.DAYS);
            Object[] cityRow = pickCity();
            AddressHistory h = new AddressHistory();
            h.setAddressType(addressType);
            h.setAddress1(randomStreetAddress());
            h.setAddress2("");
            h.setCity((String)   cityRow[0]);
            h.setState((String)  cityRow[1]);
            h.setZipcode((String)cityRow[2]);
            h.setCountry("US");
            h.setMasteredDateTs(isoTs(cursor));
            h.setRuleId(pick(RULE_IDS));
            h.setRuleVersion(pick(RULE_VERSIONS));
            h.setLostAgainstRule(pick(RULE_IDS));
            history.add(0, h);
        }
        return history;
    }

    // ------------------------------------------------------------------
    // Utility helpers
    // ------------------------------------------------------------------

    private String pick(String[] arr)  { return arr[rng.nextInt(arr.length)]; }

    private Object[] pickCity() {
        int roll = rng.nextInt(CITY_CUMULATIVE[CITY_CUMULATIVE.length - 1]);
        for (int i = 0; i < CITY_CUMULATIVE.length; i++) {
            if (roll < CITY_CUMULATIVE[i]) return CITIES[i];
        }
        return CITIES[CITIES.length - 1];
    }

    private String randomStreetAddress() {
        return (100 + rng.nextInt(9900)) + " " + pick(STREET_NAMES);
    }

    private String randomDob() {
        LocalDate today = LocalDate.now();
        int yearsAgo  = 18 + rng.nextInt(63);
        int dayOffset = rng.nextInt(365);
        return today.minusYears(yearsAgo).minusDays(dayOffset)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private Instant randomInstantWithinDays(int days) {
        long offsetSeconds = (long) (rng.nextDouble() * days * 86_400);
        return Instant.now().minus(offsetSeconds, ChronoUnit.SECONDS);
    }

    public static String isoTs(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
