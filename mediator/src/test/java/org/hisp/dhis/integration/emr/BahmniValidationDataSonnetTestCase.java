package org.hisp.dhis.integration.emr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Bahmni validation rules (config/datastore/bahmni-validation.ds), against the
 * per-person record shape Bahmni exports. The rules are STRUCTURAL only ("can the mapping run?");
 * the data model itself — mandatory attributes, types, ranges — is DHIS2's to enforce.
 */
public class BahmniValidationDataSonnetTestCase extends AbstractDataSonnetTestCase {

  private String rules;

  @BeforeEach
  public void beforeEach() throws IOException {
    rules = readDatastoreExpression("bahmni-validation.json");
  }

  @SuppressWarnings("unchecked")
  private List<String> missingFields(Map<String, Object> result) {
    return (List<String>) result.get("missingFields");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> encounters(Map<String, Object> sample) {
    return (List<Map<String, Object>>) sample.get("encounters");
  }

  @Test
  public void testReferenceSampleIsValid() throws IOException {
    // The verdict also names WHO it is about — the 400 carries it so a vendor
    // pushing a whole batch knows which patient to fix.
    assertEquals(
        Map.of("valid", true, "missingFields", List.of(), "mrn", "668466"),
        evaluate(rules, readEmrSample("bahmni/anc-record.json")));
  }

  @Test
  public void testPhonelessWomanIsRejectedWithAReadableMessage() throws IOException {
    Map<String, Object> sample = readEmrSample("bahmni/anc-record.json");
    sample.put("phoneNumber", null);
    Map<String, Object> result = evaluate(rules, sample);
    assertEquals(false, result.get("valid"));
    assertTrue(missingFields(result).contains("phoneNumber"));
  }

  @Test
  public void testRegistrationDataArrivesAsASet() throws IOException {
    // An encounter that carries LNMP or EDD is the registration encounter and
    // feeds the profile — LNMP, EDD and gestationalAge must all be present on it.
    Map<String, Object> sample = readEmrSample("bahmni/anc-record.json");
    encounters(sample).get(0).put("gestationalAge", null);
    Map<String, Object> result = evaluate(rules, sample);
    assertEquals(false, result.get("valid"));
    assertTrue(missingFields(result).stream().anyMatch(f -> f.contains("gestationalAge")));

    sample = readEmrSample("bahmni/anc-record.json");
    encounters(sample).get(0).put("EDD", null);
    result = evaluate(rules, sample);
    assertEquals(false, result.get("valid"));
    assertTrue(missingFields(result).stream().anyMatch(f -> f.contains("EDD")));
  }

  @Test
  public void testFollowUpOnlyRecordIsValidWithoutRegistrationData() throws IOException {
    // No LNMP/EDD on any encounter: no profile is built, and nothing is required.
    Map<String, Object> sample = readEmrSample("bahmni/anc-record.json");
    for (Map<String, Object> encounter : encounters(sample)) {
      encounter.put("LNMP", null);
      encounter.put("EDD", null);
      encounter.put("gestationalAge", null);
    }
    assertEquals(true, evaluate(rules, sample).get("valid"));
  }

  @Test
  public void testPerEncounterFieldsAreNamed() throws IOException {
    Map<String, Object> sample = readEmrSample("bahmni/anc-record.json");
    encounters(sample).get(0).remove("encounterDate");
    encounters(sample).get(0).put("visitNumber", "one"); // must be numeric
    Map<String, Object> result = evaluate(rules, sample);
    assertEquals(false, result.get("valid"));
    assertTrue(missingFields(result).contains("encounters[0].encounterDate"));
    assertTrue(missingFields(result).contains("encounters[0].visitNumber"));
  }

  @Test
  public void testMissingIdentityFieldsAreListedPrecisely() throws IOException {
    Map<String, Object> result = evaluate(rules, Map.of("encounters", List.of()));
    assertEquals(false, result.get("valid"));
    assertTrue(missingFields(result).contains("patientId"));
    assertTrue(missingFields(result).contains("facilityCode"));
    assertTrue(missingFields(result).contains("encounters (at least one entry)"));
  }

  @Test
  public void testVerdictCarriesNullMrnWhenTheIdItselfIsMissing() throws IOException {
    Map<String, Object> sample = readEmrSample("bahmni/anc-record.json");
    sample.put("patientId", null);
    Map<String, Object> result = evaluate(rules, sample);
    assertEquals(false, result.get("valid"));
    assertEquals(null, result.get("mrn"));
  }

  @Test
  public void testArbitrarilyMalformedInputDoesNotCrashTheRules() {
    // Every access is guarded: junk input degrades to a field list, never an error.
    assertEquals(false, evaluate(rules, Map.of()).get("valid"));
    assertEquals(false, evaluate(rules, Map.of("encounters", "not-an-array")).get("valid"));
  }
}
