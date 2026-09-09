package org.hisp.dhis.integration.emr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Bahmni batch envelope expression (config/datastore/bahmni-envelope.ds):
 * Bahmni exports a FLAT LIST OF ENCOUNTERS; the envelope folds it into one record per woman
 * before the batch split — which is what makes batch entries independent (no two entries can
 * race on the same woman).
 *
 * <p>The sample IS the real reference export Bahmni shared: 8 encounters, 5 women.
 */
public class BahmniEnvelopeDataSonnetTestCase extends AbstractDataSonnetTestCase {

  private String envelope;
  private List<Object> export;

  @BeforeEach
  public void beforeEach() throws IOException {
    envelope = readDatastoreExpression("bahmni-envelope.json");
    export = readEmrSampleList("bahmni/anc-records-batch.json");
  }

  @Test
  public void testEightEncountersFoldIntoFiveWomenInFirstAppearanceOrder() {
    List<Map<String, Object>> records = evaluateToList(envelope, export);
    assertEquals(5, records.size());
    assertEquals(
        List.of(668462, 668465, 668464, 668463, 668466),
        records.stream().map(r -> r.get("patientId")).toList());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testVisitsAreSortedByVisitNumberWithinEachWoman() {
    Map<String, Object> woman668466 = recordFor(668466);
    List<Map<String, Object>> visits =
        (List<Map<String, Object>>) woman668466.get("visits");
    assertEquals(List.of(1, 2), visits.stream().map(v -> v.get("visitNumber")).toList());
    assertEquals(List.of(32, 34), visits.stream().map(v -> v.get("encounterId")).toList());
  }

  @Test
  public void testPatientFieldsAreHoistedFirstNonNull() {
    Map<String, Object> woman668463 = recordFor(668463);
    assertEquals("0912221466", woman668463.get("phoneNumber"));
    assertEquals("2026-03-19", woman668463.get("lnmp"));
    assertEquals("2026-08-18", woman668463.get("dateOfFirstVisit"));
    // a field null on one row is hoisted from any later row that carries it
    assertEquals("0911556677", recordFor(668466).get("phoneNumber"));
  }

  @Test
  public void testFoldedRecordEqualsTheSingleEndpointSample() throws IOException {
    // The single-record sample ships as the grouped shape of woman 668466 — the
    // envelope output and the single endpoint's input are the SAME contract.
    assertEquals(readEmrSample("bahmni/anc-record.json"), recordFor(668466));
  }

  @Test
  public void testWrappedRecordsEnvelopeIsAcceptedToo() {
    assertEquals(
        evaluateToList(envelope, export),
        evaluateToList(envelope, Map.of("records", export)));
  }

  private Map<String, Object> recordFor(int patientId) {
    return evaluateToList(envelope, export).stream()
        .filter(r -> r.get("patientId").equals(patientId))
        .findFirst()
        .orElseThrow();
  }
}
