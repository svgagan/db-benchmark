package com.gsv.benchmark.data;

import com.gsv.benchmark.model.GoldenPerson;
import com.gsv.benchmark.model.GoldenPerson.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Generates synthetic MDM golden person records for benchmarking.
 *
 * <p>Design choices for meaningful benchmark results:
 * <ul>
 *   <li><b>SSN pool</b> — 1,000 values shared across 100 k records, giving
 *       ~100 matches per SSN value so Q3 / Q8 return real result sets.</li>
 *   <li><b>Rule IDs</b> — RULE_001 … RULE_010 (uniform), ~10 k records each.</li>
 *   <li><b>City weights</b> — population-weighted; "New York" ≈ 12 % for Q4.</li>
 *   <li><b>mastered_date_ts</b> — uniform over the past two years so the
 *       Q6 "last 12 months" filter returns ~50 % of records.</li>
 *   <li><b>prior_values</b> — 0–3 historical demographic snapshots and 0–2
 *       per address, each referencing SSNs / cities from the shared pools
 *       so Q8 / Q9 history queries hit real data.</li>
 * </ul>
 */
public class DataGenerator {

    private static final Random RNG = new Random(42);

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
    // Reference data — cities (city, state, zipcode, weight / 100)
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
    // Reference data — SSN pool (1 000 values → ~100 matches per SSN in 100 k records)
    // ------------------------------------------------------------------

    private static final String[] SSN_POOL;

    static {
        SSN_POOL = new String[1_000];
        // Use a separate seeded random so the pool is always the same regardless
        // of how many records have been generated before.
        Random ssnRng = new Random(7);
        for (int i = 0; i < SSN_POOL.length; i++) {
            SSN_POOL[i] = String.format("%03d-%02d-%04d",
                    100 + ssnRng.nextInt(800),   // area 100–899 (avoids reserved ranges)
                    10  + ssnRng.nextInt(89),    // group 10–98
                    1000 + ssnRng.nextInt(8999)  // serial 1000–9998
            );
        }
    }

    // ------------------------------------------------------------------
    // Reference data — rules and address types
    // ------------------------------------------------------------------

    // 10 rule IDs — uniform distribution gives ~10 k records each
    private static final String[] RULE_IDS = {
        "RULE_001", "RULE_002", "RULE_003", "RULE_004", "RULE_005",
        "RULE_006", "RULE_007", "RULE_008", "RULE_009", "RULE_010"
    };

    private static final String[] RULE_VERSIONS = {"v1.0", "v1.1", "v2.0", "v2.1", "v3.0"};

    private static final String[] ADDRESS_TYPES  = {"PRIMARY", "SECONDARY", "BILLING"};

    private static final String[] STREET_NAMES = {
        "Main St", "Oak Ave", "Maple Dr", "Cedar Ln", "Pine Rd", "Elm St",
        "Washington Blvd", "Park Ave", "Lake Dr", "River Rd", "Hill St",
        "Forest Way", "Sunset Blvd", "Highland Ave", "Meadow Ln"
    };

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Generates {@code count} synthetic MDM golden person records. */
    public static List<GoldenPerson> generatePersons(int count) {
        List<GoldenPerson> persons = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            persons.add(buildPerson());
        }
        return persons;
    }

    // ------------------------------------------------------------------
    // Exposed constants for benchmark query parameter binding
    // ------------------------------------------------------------------

    /**
     * Returns an SSN that is guaranteed to exist in generated data.
     * Used as the exact-match parameter for Q3 and the history search Q8.
     */
    public static String sampleSsn() {
        return SSN_POOL[0];   // always "the first SSN in the pool"
    }

    /**
     * Returns a city guaranteed to appear in address prior_values.
     * Used as the history search parameter for Q9.
     */
    public static String samplePriorCity() {
        return (String) CITIES[2][0];  // "Chicago" — 8 % weight
    }

    // ------------------------------------------------------------------
    // Builder
    // ------------------------------------------------------------------

    private static GoldenPerson buildPerson() {
        GoldenPerson p = new GoldenPerson();

        String pid = UUID.randomUUID().toString();
        String cid = UUID.randomUUID().toString();
        p.setGlobalPid(pid);
        p.setGlobalCid(cid);
        p.setId(pid + "|" + cid);

        // mastered_date_ts — uniform over last 2 years (Q6 uses last-12-months filter → ~50 %)
        Instant masteredInstant = randomInstantWithinDays(730);
        p.setMasteredDateTs(isoTs(masteredInstant));

        p.setDemographic(buildDemographic(masteredInstant));
        p.setAddress(buildAddresses(masteredInstant));

        return p;
    }

    // ------------------------------------------------------------------
    // Demographic builder
    // ------------------------------------------------------------------

    private static Demographic buildDemographic(Instant masteredInstant) {
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

    /**
     * Generates 0–3 prior demographic snapshots (oldest first).
     * Each prior snapshot uses an SSN from the shared pool so Q8 returns results.
     */
    private static List<DemographicHistory> buildDemographicHistory(Instant latestInstant) {
        int count = RNG.nextInt(4);   // 0, 1, 2, or 3 prior entries
        List<DemographicHistory> history = new ArrayList<>(count);
        Instant cursor = latestInstant;
        for (int i = count; i > 0; i--) {
            // Each step goes further into the past
            cursor = cursor.minus(30 + RNG.nextInt(180), ChronoUnit.DAYS);
            DemographicHistory h = new DemographicHistory();
            h.setFirstName(pick(FIRST_NAMES));
            h.setLastName(pick(LAST_NAMES));
            h.setDateOfBirth(randomDob());
            h.setSsn(pick(SSN_POOL));           // from shared pool → Q8 hits
            h.setMasteredDateTs(isoTs(cursor));
            h.setRuleId(pick(RULE_IDS));
            h.setRuleVersion(pick(RULE_VERSIONS));
            h.setLostAgainstRule(pick(RULE_IDS));
            history.add(0, h);   // prepend so list is oldest-first
        }
        return history;
    }

    // ------------------------------------------------------------------
    // Address builders
    // ------------------------------------------------------------------

    /**
     * Generates 1–3 addresses. PRIMARY is always included first;
     * SECONDARY and BILLING are added with 40 % and 25 % probability.
     */
    private static List<GoldenPerson.Address> buildAddresses(Instant masteredInstant) {
        List<GoldenPerson.Address> addresses = new ArrayList<>();
        addresses.add(buildAddress("PRIMARY", masteredInstant));
        if (RNG.nextInt(100) < 40) addresses.add(buildAddress("SECONDARY", masteredInstant));
        if (RNG.nextInt(100) < 25) addresses.add(buildAddress("BILLING",   masteredInstant));
        return addresses;
    }

    private static GoldenPerson.Address buildAddress(String addressType, Instant masteredInstant) {
        Object[] cityRow = pickCity();

        GoldenPerson.Address a = new GoldenPerson.Address();
        a.setAddressType(addressType);
        a.setAddress1(randomStreetAddress());
        a.setAddress2(RNG.nextInt(100) < 20 ? "Apt " + (100 + RNG.nextInt(900)) : "");
        a.setCity((String)  cityRow[0]);
        a.setState((String) cityRow[1]);
        a.setZipcode((String) cityRow[2]);
        a.setCountry("US");
        a.setMasteredDateTs(isoTs(masteredInstant));
        a.setRuleId(pick(RULE_IDS));
        a.setRuleVersion(pick(RULE_VERSIONS));
        a.setPriorValues(buildAddressHistory(addressType, masteredInstant));
        return a;
    }

    /**
     * Generates 0–2 prior address snapshots (oldest first).
     * Cities are drawn from the shared city pool so Q9 returns results.
     */
    private static List<AddressHistory> buildAddressHistory(String addressType, Instant latestInstant) {
        int count = RNG.nextInt(3);   // 0, 1, or 2 prior entries
        List<AddressHistory> history = new ArrayList<>(count);
        Instant cursor = latestInstant;
        for (int i = count; i > 0; i--) {
            cursor = cursor.minus(30 + RNG.nextInt(180), ChronoUnit.DAYS);
            Object[] cityRow = pickCity();
            AddressHistory h = new AddressHistory();
            h.setAddressType(addressType);
            h.setAddress1(randomStreetAddress());
            h.setAddress2("");
            h.setCity((String)  cityRow[0]);
            h.setState((String) cityRow[1]);
            h.setZipcode((String) cityRow[2]);
            h.setCountry("US");
            h.setMasteredDateTs(isoTs(cursor));
            h.setRuleId(pick(RULE_IDS));
            h.setRuleVersion(pick(RULE_VERSIONS));
            h.setLostAgainstRule(pick(RULE_IDS));
            history.add(0, h);   // oldest first
        }
        return history;
    }

    // ------------------------------------------------------------------
    // Utility helpers
    // ------------------------------------------------------------------

    private static String pick(String[] arr) {
        return arr[RNG.nextInt(arr.length)];
    }

    private static Object[] pickCity() {
        int roll = RNG.nextInt(CITY_CUMULATIVE[CITY_CUMULATIVE.length - 1]);
        for (int i = 0; i < CITY_CUMULATIVE.length; i++) {
            if (roll < CITY_CUMULATIVE[i]) return CITIES[i];
        }
        return CITIES[CITIES.length - 1];
    }

    private static String randomStreetAddress() {
        return (100 + RNG.nextInt(9900)) + " " + pick(STREET_NAMES);
    }

    /** Random date of birth between 18 and 80 years ago. */
    private static String randomDob() {
        LocalDate today = LocalDate.now();
        int yearsAgo = 18 + RNG.nextInt(63);
        int dayOffset = RNG.nextInt(365);
        return today.minusYears(yearsAgo).minusDays(dayOffset)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * Returns an Instant chosen uniformly at random within the last
     * {@code days} calendar days.
     */
    private static Instant randomInstantWithinDays(int days) {
        long offsetSeconds = (long) (RNG.nextDouble() * days * 86_400);
        return Instant.now().minus(offsetSeconds, ChronoUnit.SECONDS);
    }

    private static String isoTs(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
