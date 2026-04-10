package com.gsv.benchmark.data;

import com.gsv.benchmark.model.AuditLog;
import com.gsv.benchmark.model.AuditLog.AuditEntry;
import com.gsv.benchmark.model.UserProfile;
import com.gsv.benchmark.model.UserProfile.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Generates synthetic user profiles and audit logs for benchmarking.
 * Uses predefined value arrays to avoid external Faker dependencies and
 * ensure predictable, reproducible data distributions.
 */
public class DataGenerator {

    private static final Random RNG = new Random(42);

    // ------------------------------------------------------------------
    // Reference data
    // ------------------------------------------------------------------

    private static final String[] FIRST_NAMES = {
        "Alice", "Bob", "Charlie", "Diana", "Eve", "Frank", "Grace", "Henry",
        "Iris", "Jack", "Karen", "Liam", "Mia", "Noah", "Olivia", "Paul",
        "Quinn", "Rachel", "Sam", "Tara", "Uma", "Victor", "Wendy", "Xander",
        "Yara", "Zoe", "Aaron", "Beth", "Carl", "Dani"
    };

    private static final String[] LAST_NAMES = {
        "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller",
        "Davis", "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez",
        "Wilson", "Anderson", "Thomas", "Taylor", "Moore", "Jackson", "Martin",
        "Lee", "Perez", "Thompson", "White", "Harris", "Sanchez", "Clark",
        "Ramirez", "Lewis", "Robinson"
    };

    private static final String[] EMAIL_DOMAINS = {
        "gmail.com", "yahoo.com", "outlook.com", "hotmail.com",
        "icloud.com", "proton.me", "example.com"
    };

    // City weights: city | state | zip | weight (sum=100)
    private static final Object[][] CITIES = {
        {"New York",      "NY", "10001", 12},
        {"Los Angeles",   "CA", "90001", 10},
        {"Chicago",       "IL", "60601",  8},
        {"Houston",       "TX", "77001",  7},
        {"Phoenix",       "AZ", "85001",  6},
        {"Philadelphia",  "PA", "19101",  5},
        {"San Antonio",   "TX", "78201",  5},
        {"San Diego",     "CA", "92101",  5},
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
        {"Boston",        "MA", "02101",  3}
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

    private static final String[] TIERS = {"basic", "premium", "enterprise"};
    // cumulative weights: basic=40, premium=75, enterprise=100
    private static final int[] TIER_WEIGHTS = {40, 75, 100};

    private static final String[] ALL_TAGS = {
        "verified", "early_adopter", "beta_tester", "power_user", "referral"
    };

    private static final String[] ALL_INTERESTS = {
        "technology", "travel", "cooking", "sports", "music",
        "gaming", "fashion", "finance", "health", "education"
    };

    private static final String[] ALL_LANGUAGES = {"en", "es", "fr", "de", "pt", "zh", "ja"};

    private static final String[] CURRENCIES    = {"USD", "EUR", "GBP", "CAD"};
    private static final String[] PAY_METHODS   = {"credit_card", "debit_card", "paypal", "bank_transfer"};
    private static final String[] THEMES        = {"dark", "light", "system"};
    private static final String[] REFERRALS     = {"organic", "social", "referral", "ad", "email"};

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public static List<UserProfile> generateUsers(int count) {
        List<UserProfile> users = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            users.add(buildUser());
        }
        return users;
    }

    /**
     * Generates one {@link AuditLog} per user.
     * Each tracked field gets 2–4 history entries spanning ~180 days,
     * with the final entry matching the current golden-record value.
     */
    public static List<AuditLog> generateAuditLogs(List<UserProfile> users) {
        List<AuditLog> logs = new ArrayList<>(users.size());
        for (UserProfile u : users) {
            logs.add(buildAuditLog(u));
        }
        return logs;
    }

    // ------------------------------------------------------------------
    // Builders
    // ------------------------------------------------------------------

    private static UserProfile buildUser() {
        UserProfile p = new UserProfile();
        p.setId(UUID.randomUUID().toString());
        p.setName(pick(FIRST_NAMES) + " " + pick(LAST_NAMES));
        p.setEmail(p.getName().toLowerCase().replace(" ", ".") + RNG.nextInt(999)
                   + "@" + pick(EMAIL_DOMAINS));
        p.setAge(18 + RNG.nextInt(53));  // 18–70
        p.setSubscriptionTier(pickWeighted(TIERS, TIER_WEIGHTS));

        Object[] city = pickCity();
        p.setAddress(new Address((String) city[0], (String) city[1], (String) city[2]));

        p.setTags(randomSubset(ALL_TAGS, 0, 3));
        p.setPreferences(new Preferences(
            pick(THEMES),
            pick(ALL_LANGUAGES),
            RNG.nextBoolean()
        ));

        Billing billing = new Billing();
        billing.setAccountBalance(Math.round(RNG.nextDouble() * 10_000 * 100.0) / 100.0);
        billing.setCurrency(pick(CURRENCIES));
        billing.setCreditScore(580 + RNG.nextInt(271));  // 580–850
        billing.setPaymentMethod(pick(PAY_METHODS));
        Object[] billCity = pickCity();
        billing.setBillingAddress(
            new Address((String) billCity[0], (String) billCity[1], (String) billCity[2]));
        p.setBilling(billing);

        Social social = new Social();
        social.setInterests(randomSubset(ALL_INTERESTS, 2, 5));
        social.setLanguagesSpoken(randomSubset(ALL_LANGUAGES, 1, 3));
        social.setFollowersCount(RNG.nextInt(10_001));
        social.setFollowingCount(RNG.nextInt(1_001));
        social.setReferralSource(pick(REFERRALS));
        p.setSocial(social);

        return p;
    }

    private static AuditLog buildAuditLog(UserProfile current) {
        // Anchor timestamps: up to 180 days ago → now
        Instant now = Instant.now();
        int historyEntries = 2 + RNG.nextInt(3); // 2–4 entries per field

        Map<String, List<AuditEntry>> auditData = new LinkedHashMap<>();

        // subscription_tier history
        auditData.put("subscription_tier",
            buildHistory(historyEntries, now, current.getSubscriptionTier(),
                () -> pickWeighted(TIERS, TIER_WEIGHTS)));

        // age history (birthday may fall in the window)
        auditData.put("age",
            buildHistory(historyEntries, now, current.getAge(),
                () -> 18 + RNG.nextInt(53)));

        // address_city history
        auditData.put("address_city",
            buildHistory(historyEntries, now, current.getAddress().getCity(),
                () -> (String) pickCity()[0]));

        // billing_credit_score history
        auditData.put("billing_credit_score",
            buildHistory(historyEntries, now, current.getBilling().getCreditScore(),
                () -> 580 + RNG.nextInt(271)));

        // billing_account_balance history
        auditData.put("billing_account_balance",
            buildHistory(historyEntries, now, current.getBilling().getAccountBalance(),
                () -> Math.round(RNG.nextDouble() * 10_000 * 100.0) / 100.0));

        // tags history (each entry is a List<String>)
        List<AuditEntry> tagHistory = new ArrayList<>();
        for (int i = 0; i < historyEntries - 1; i++) {
            tagHistory.add(new AuditEntry(
                randomSubset(ALL_TAGS, 0, 3),
                pastTimestamp(now, 180 - (i * (180 / historyEntries)))
            ));
        }
        tagHistory.add(new AuditEntry(current.getTags(), isoTimestamp(now)));
        auditData.put("tags", tagHistory);

        AuditLog log = new AuditLog();
        log.setUserId(current.getId());
        log.setAuditData(auditData);
        return log;
    }

    /**
     * Builds a time-ordered list of audit entries for a single scalar field.
     * The last entry always equals {@code currentValue}.
     */
    private static <T> List<AuditEntry> buildHistory(
            int count, Instant now, T currentValue, java.util.function.Supplier<T> randomValue) {

        List<AuditEntry> entries = new ArrayList<>(count);
        int daysSpan = 180;
        for (int i = 0; i < count - 1; i++) {
            int daysAgo = daysSpan - (i * (daysSpan / count));
            entries.add(new AuditEntry(randomValue.get(), pastTimestamp(now, daysAgo)));
        }
        entries.add(new AuditEntry(currentValue, isoTimestamp(now)));
        return entries;
    }

    // ------------------------------------------------------------------
    // Utility helpers
    // ------------------------------------------------------------------

    private static String pick(String[] arr) {
        return arr[RNG.nextInt(arr.length)];
    }

    private static Object[] pickCity() {
        int roll = RNG.nextInt(100);
        for (int i = 0; i < CITY_CUMULATIVE.length; i++) {
            if (roll < CITY_CUMULATIVE[i]) return CITIES[i];
        }
        return CITIES[CITIES.length - 1];
    }

    private static String pickWeighted(String[] options, int[] cumulative) {
        int roll = RNG.nextInt(cumulative[cumulative.length - 1]);
        for (int i = 0; i < cumulative.length; i++) {
            if (roll < cumulative[i]) return options[i];
        }
        return options[options.length - 1];
    }

    private static List<String> randomSubset(String[] source, int minCount, int maxCount) {
        int count = minCount + RNG.nextInt(maxCount - minCount + 1);
        List<String> shuffled = new ArrayList<>(Arrays.asList(source));
        Collections.shuffle(shuffled, RNG);
        return new ArrayList<>(shuffled.subList(0, Math.min(count, shuffled.size())));
    }

    private static String pastTimestamp(Instant now, int daysAgo) {
        return isoTimestamp(now.minus(daysAgo, ChronoUnit.DAYS)
                               .minus(RNG.nextInt(86_400), ChronoUnit.SECONDS));
    }

    private static String isoTimestamp(Instant instant) {
        return instant.toString();   // e.g. "2024-01-15T10:30:00Z"
    }
}
