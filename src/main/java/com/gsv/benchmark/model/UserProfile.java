package com.gsv.benchmark.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Golden record for a user profile.
 * <p>
 * In PostgreSQL: {@code id} and {@code updatedAt} are native columns;
 * everything else is serialised into the {@code data} JSONB column.
 * <p>
 * In MongoDB: all fields are stored as top-level document fields.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserProfile {

    private String id;          // UUID string
    private String name;
    private String email;
    private int age;

    @JsonProperty("subscription_tier")
    private String subscriptionTier;

    private Address address;
    private List<String> tags;
    private Preferences preferences;
    private Billing billing;
    private Social social;

    // ------------------------------------------------------------------
    // Nested types
    // ------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Address {
        private String city;
        private String state;
        private String zip;

        public Address() {}
        public Address(String city, String state, String zip) {
            this.city = city; this.state = state; this.zip = zip;
        }

        public String getCity()  { return city; }
        public String getState() { return state; }
        public String getZip()   { return zip; }
        public void setCity(String city)   { this.city = city; }
        public void setState(String state) { this.state = state; }
        public void setZip(String zip)     { this.zip = zip; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Preferences {
        private String theme;
        private String language;
        private boolean notifications;

        public Preferences() {}
        public Preferences(String theme, String language, boolean notifications) {
            this.theme = theme; this.language = language; this.notifications = notifications;
        }

        public String getTheme()         { return theme; }
        public String getLanguage()      { return language; }
        public boolean isNotifications() { return notifications; }
        public void setTheme(String theme)             { this.theme = theme; }
        public void setLanguage(String language)       { this.language = language; }
        public void setNotifications(boolean n)        { this.notifications = n; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Billing {
        @JsonProperty("account_balance")
        private double accountBalance;

        private String currency;

        @JsonProperty("credit_score")
        private int creditScore;

        @JsonProperty("payment_method")
        private String paymentMethod;

        @JsonProperty("billing_address")
        private Address billingAddress;

        public Billing() {}

        public double getAccountBalance()    { return accountBalance; }
        public String getCurrency()          { return currency; }
        public int getCreditScore()          { return creditScore; }
        public String getPaymentMethod()     { return paymentMethod; }
        public Address getBillingAddress()   { return billingAddress; }

        public void setAccountBalance(double v)    { this.accountBalance = v; }
        public void setCurrency(String v)          { this.currency = v; }
        public void setCreditScore(int v)          { this.creditScore = v; }
        public void setPaymentMethod(String v)     { this.paymentMethod = v; }
        public void setBillingAddress(Address v)   { this.billingAddress = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Social {
        private List<String> interests;

        @JsonProperty("languages_spoken")
        private List<String> languagesSpoken;

        @JsonProperty("followers_count")
        private int followersCount;

        @JsonProperty("following_count")
        private int followingCount;

        @JsonProperty("referral_source")
        private String referralSource;

        public Social() {}

        public List<String> getInterests()        { return interests; }
        public List<String> getLanguagesSpoken()  { return languagesSpoken; }
        public int getFollowersCount()            { return followersCount; }
        public int getFollowingCount()            { return followingCount; }
        public String getReferralSource()         { return referralSource; }

        public void setInterests(List<String> v)       { this.interests = v; }
        public void setLanguagesSpoken(List<String> v) { this.languagesSpoken = v; }
        public void setFollowersCount(int v)           { this.followersCount = v; }
        public void setFollowingCount(int v)           { this.followingCount = v; }
        public void setReferralSource(String v)        { this.referralSource = v; }
    }

    // ------------------------------------------------------------------
    // Accessors for the top-level fields
    // ------------------------------------------------------------------

    public String getId()                  { return id; }
    public String getName()               { return name; }
    public String getEmail()              { return email; }
    public int getAge()                   { return age; }
    public String getSubscriptionTier()   { return subscriptionTier; }
    public Address getAddress()           { return address; }
    public List<String> getTags()         { return tags; }
    public Preferences getPreferences()   { return preferences; }
    public Billing getBilling()           { return billing; }
    public Social getSocial()             { return social; }

    public void setId(String id)                          { this.id = id; }
    public void setName(String name)                      { this.name = name; }
    public void setEmail(String email)                    { this.email = email; }
    public void setAge(int age)                           { this.age = age; }
    public void setSubscriptionTier(String tier)          { this.subscriptionTier = tier; }
    public void setAddress(Address address)               { this.address = address; }
    public void setTags(List<String> tags)                { this.tags = tags; }
    public void setPreferences(Preferences preferences)   { this.preferences = preferences; }
    public void setBilling(Billing billing)               { this.billing = billing; }
    public void setSocial(Social social)                  { this.social = social; }
}
