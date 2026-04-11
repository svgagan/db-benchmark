package com.gsv.benchmark.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * MDM Golden Person record.
 *
 * <p><b>DocumentDB</b> — stored as a single document in the
 * {@code mdm_golden_person} collection.  {@code _id} = {@code global_pid|global_cid}.
 *
 * <p><b>PostgreSQL</b> — top-level scalar fields ({@code id}, {@code global_pid},
 * {@code global_cid}, {@code mastered_date_ts}) are stored as native relational columns
 * for efficient SQL filtering and range queries.  The full document (including
 * {@code demographic} and {@code address}) is also serialised into the
 * {@code golden_record} JSONB column for flexible nested querying.
 *
 * <h3>Table: mdm_golden_record</h3>
 * <pre>
 *   id               TEXT PRIMARY KEY          -- "global_pid|global_cid"
 *   global_pid       TEXT NOT NULL
 *   global_cid       TEXT NOT NULL
 *   mastered_date_ts TIMESTAMPTZ NOT NULL
 *   created_date     TIMESTAMPTZ DEFAULT now()
 *   updated_date     TIMESTAMPTZ DEFAULT now()
 *   golden_record    JSONB NOT NULL
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GoldenPerson {

    /** Composite primary key — {@code "global_pid|global_cid"}. */
    private String id;

    @JsonProperty("global_pid")
    private String globalPid;

    @JsonProperty("global_cid")
    private String globalCid;

    /** ISO-8601 timestamp when this record was last mastered. */
    @JsonProperty("mastered_date_ts")
    private String masteredDateTs;

    private Demographic demographic;

    /** Ordered list of addresses — PRIMARY is always first. */
    private List<Address> address;

    // ------------------------------------------------------------------
    // Nested: Demographic
    // ------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Demographic {

        @JsonProperty("first_name")
        private String firstName;

        @JsonProperty("last_name")
        private String lastName;

        @JsonProperty("date_of_birth")
        private String dateOfBirth;

        /** SSN drawn from a shared pool so queries return measurable result sets. */
        private String ssn;

        @JsonProperty("mastered_date_ts")
        private String masteredDateTs;

        @JsonProperty("rule_id")
        private String ruleId;

        @JsonProperty("rule_version")
        private String ruleVersion;

        /** Ordered history of previous demographic values — newest last. */
        @JsonProperty("prior_values")
        private List<DemographicHistory> priorValues;

        public Demographic() {}

        public String getFirstName()                        { return firstName; }
        public String getLastName()                         { return lastName; }
        public String getDateOfBirth()                      { return dateOfBirth; }
        public String getSsn()                              { return ssn; }
        public String getMasteredDateTs()                   { return masteredDateTs; }
        public String getRuleId()                           { return ruleId; }
        public String getRuleVersion()                      { return ruleVersion; }
        public List<DemographicHistory> getPriorValues()    { return priorValues; }

        public void setFirstName(String v)                       { this.firstName = v; }
        public void setLastName(String v)                        { this.lastName = v; }
        public void setDateOfBirth(String v)                     { this.dateOfBirth = v; }
        public void setSsn(String v)                             { this.ssn = v; }
        public void setMasteredDateTs(String v)                  { this.masteredDateTs = v; }
        public void setRuleId(String v)                          { this.ruleId = v; }
        public void setRuleVersion(String v)                     { this.ruleVersion = v; }
        public void setPriorValues(List<DemographicHistory> v)   { this.priorValues = v; }
    }

    // ------------------------------------------------------------------
    // Nested: DemographicHistory (one entry in demographic.prior_values)
    // ------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DemographicHistory {

        @JsonProperty("first_name")
        private String firstName;

        @JsonProperty("last_name")
        private String lastName;

        @JsonProperty("date_of_birth")
        private String dateOfBirth;

        private String ssn;

        @JsonProperty("mastered_date_ts")
        private String masteredDateTs;

        @JsonProperty("rule_id")
        private String ruleId;

        @JsonProperty("rule_version")
        private String ruleVersion;

        /** The rule that superseded (won against) this demographic record. */
        @JsonProperty("lost_against_rule")
        private String lostAgainstRule;

        public DemographicHistory() {}

        public String getFirstName()        { return firstName; }
        public String getLastName()         { return lastName; }
        public String getDateOfBirth()      { return dateOfBirth; }
        public String getSsn()              { return ssn; }
        public String getMasteredDateTs()   { return masteredDateTs; }
        public String getRuleId()           { return ruleId; }
        public String getRuleVersion()      { return ruleVersion; }
        public String getLostAgainstRule()  { return lostAgainstRule; }

        public void setFirstName(String v)       { this.firstName = v; }
        public void setLastName(String v)        { this.lastName = v; }
        public void setDateOfBirth(String v)     { this.dateOfBirth = v; }
        public void setSsn(String v)             { this.ssn = v; }
        public void setMasteredDateTs(String v)  { this.masteredDateTs = v; }
        public void setRuleId(String v)          { this.ruleId = v; }
        public void setRuleVersion(String v)     { this.ruleVersion = v; }
        public void setLostAgainstRule(String v) { this.lostAgainstRule = v; }
    }

    // ------------------------------------------------------------------
    // Nested: Address
    // ------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Address {

        @JsonProperty("address_type")
        private String addressType;   // PRIMARY | SECONDARY | BILLING

        private String address1;
        private String address2;
        private String city;
        private String state;
        private String zipcode;
        private String country;

        @JsonProperty("mastered_date_ts")
        private String masteredDateTs;

        @JsonProperty("rule_id")
        private String ruleId;

        @JsonProperty("rule_version")
        private String ruleVersion;

        /** Ordered history of previous address values — newest last. */
        @JsonProperty("prior_values")
        private List<AddressHistory> priorValues;

        public Address() {}

        public String getAddressType()              { return addressType; }
        public String getAddress1()                 { return address1; }
        public String getAddress2()                 { return address2; }
        public String getCity()                     { return city; }
        public String getState()                    { return state; }
        public String getZipcode()                  { return zipcode; }
        public String getCountry()                  { return country; }
        public String getMasteredDateTs()           { return masteredDateTs; }
        public String getRuleId()                   { return ruleId; }
        public String getRuleVersion()              { return ruleVersion; }
        public List<AddressHistory> getPriorValues(){ return priorValues; }

        public void setAddressType(String v)             { this.addressType = v; }
        public void setAddress1(String v)                { this.address1 = v; }
        public void setAddress2(String v)                { this.address2 = v; }
        public void setCity(String v)                    { this.city = v; }
        public void setState(String v)                   { this.state = v; }
        public void setZipcode(String v)                 { this.zipcode = v; }
        public void setCountry(String v)                 { this.country = v; }
        public void setMasteredDateTs(String v)          { this.masteredDateTs = v; }
        public void setRuleId(String v)                  { this.ruleId = v; }
        public void setRuleVersion(String v)             { this.ruleVersion = v; }
        public void setPriorValues(List<AddressHistory> v){ this.priorValues = v; }
    }

    // ------------------------------------------------------------------
    // Nested: AddressHistory (one entry in address[n].prior_values)
    // ------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AddressHistory {

        @JsonProperty("address_type")
        private String addressType;

        private String address1;
        private String address2;
        private String city;
        private String state;
        private String zipcode;
        private String country;

        @JsonProperty("mastered_date_ts")
        private String masteredDateTs;

        @JsonProperty("rule_id")
        private String ruleId;

        @JsonProperty("rule_version")
        private String ruleVersion;

        /** The rule that superseded this address record. */
        @JsonProperty("lost_against_rule")
        private String lostAgainstRule;

        public AddressHistory() {}

        public String getAddressType()      { return addressType; }
        public String getAddress1()         { return address1; }
        public String getAddress2()         { return address2; }
        public String getCity()             { return city; }
        public String getState()            { return state; }
        public String getZipcode()          { return zipcode; }
        public String getCountry()          { return country; }
        public String getMasteredDateTs()   { return masteredDateTs; }
        public String getRuleId()           { return ruleId; }
        public String getRuleVersion()      { return ruleVersion; }
        public String getLostAgainstRule()  { return lostAgainstRule; }

        public void setAddressType(String v)     { this.addressType = v; }
        public void setAddress1(String v)        { this.address1 = v; }
        public void setAddress2(String v)        { this.address2 = v; }
        public void setCity(String v)            { this.city = v; }
        public void setState(String v)           { this.state = v; }
        public void setZipcode(String v)         { this.zipcode = v; }
        public void setCountry(String v)         { this.country = v; }
        public void setMasteredDateTs(String v)  { this.masteredDateTs = v; }
        public void setRuleId(String v)          { this.ruleId = v; }
        public void setRuleVersion(String v)     { this.ruleVersion = v; }
        public void setLostAgainstRule(String v) { this.lostAgainstRule = v; }
    }

    // ------------------------------------------------------------------
    // Top-level accessors
    // ------------------------------------------------------------------

    public String getId()               { return id; }
    public String getGlobalPid()        { return globalPid; }
    public String getGlobalCid()        { return globalCid; }
    public String getMasteredDateTs()   { return masteredDateTs; }
    public Demographic getDemographic() { return demographic; }
    public List<Address> getAddress()   { return address; }

    public void setId(String v)                 { this.id = v; }
    public void setGlobalPid(String v)          { this.globalPid = v; }
    public void setGlobalCid(String v)          { this.globalCid = v; }
    public void setMasteredDateTs(String v)     { this.masteredDateTs = v; }
    public void setDemographic(Demographic v)   { this.demographic = v; }
    public void setAddress(List<Address> v)     { this.address = v; }
}
