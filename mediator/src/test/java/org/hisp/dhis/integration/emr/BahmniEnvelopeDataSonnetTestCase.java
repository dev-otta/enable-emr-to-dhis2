package org.hisp.dhis.integration.emr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Bahmni batch envelope expression (config/datastore/bahmni-envelope.ds).
 * Bahmni exports one object per woman with her encounters nested inside, so the envelope only
 * unwraps the request body; the entries pass through unchanged.
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
  public void testPersonEntriesPassThroughUnchanged() {
    assertEquals(export, evaluateToList(envelope, export));
  }

  @Test
  public void testWrappedRecordsEnvelopeIsAcceptedToo() {
    assertEquals(export, evaluateToList(envelope, Map.of("records", export)));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testBatchEntryEqualsTheSingleEndpointSample() throws IOException {
    Map<String, Object> woman668466 =
        ((List<Map<String, Object>>) (List<?>) evaluateToList(envelope, export))
            .stream().filter(r -> r.get("patientId").equals(668466)).findFirst().orElseThrow();
    assertEquals(readEmrSample("bahmni/anc-record.json"), woman668466);
  }
}
