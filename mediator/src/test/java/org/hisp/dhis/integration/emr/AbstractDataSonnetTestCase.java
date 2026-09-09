package org.hisp.dhis.integration.emr;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.apache.camel.CamelContext;
import org.apache.camel.builder.ValueBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.model.language.DatasonnetExpression;
import org.apache.camel.support.DefaultExchange;

/**
 * Base class for the DataSonnet unit tests (validation rules and mappings) — the same pattern as
 * DiagnosticReportDataSonnetTestCase in dhis2/reference-dhis2-tracker-lis-integration.
 *
 * <p>The tests load the EXACT artifacts that ship: the escaped expressions from config/datastore/
 * (what the seed container writes into the DHIS2 datastore) and the sample payloads from
 * emr-mocks/ (what the mock EMRs push). No copies, so the tests cannot drift from what runs.
 *
 * <p>These run in seconds with no Docker — the guard rail for every rule or mapping change.
 */
public abstract class AbstractDataSonnetTestCase {

  protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /** Reads an escaped datastore expression (a JSON string document) into raw DataSonnet source. */
  protected static String readDatastoreExpression(String datastoreFileName) throws IOException {
    return OBJECT_MAPPER.readValue(
        new File("../config/datastore/" + datastoreFileName), String.class);
  }

  /** Reads a sample EMR payload from emr-mocks/ into a Map. */
  @SuppressWarnings("unchecked")
  protected static Map<String, Object> readEmrSample(String relativePath) throws IOException {
    return OBJECT_MAPPER.readValue(new File("../emr-mocks/" + relativePath), Map.class);
  }

  /** Reads a sample EMR payload that is a JSON array (e.g. Bahmni's batch export) into a List. */
  @SuppressWarnings("unchecked")
  protected static java.util.List<Object> readEmrSampleList(String relativePath)
      throws IOException {
    return OBJECT_MAPPER.readValue(new File("../emr-mocks/" + relativePath), java.util.List.class);
  }

  /** Reads an expected tracker payload from the test classpath into a Map. */
  @SuppressWarnings("unchecked")
  protected static Map<String, Object> readExpected(String classpathResource) throws IOException {
    try (InputStream in =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(classpathResource)) {
      return OBJECT_MAPPER.readValue(in, Map.class);
    }
  }

  /**
   * Evaluates a DataSonnet expression the same way the shared apply-datasonnet-route does: the
   * record as a Java object body, the expression a pure function of it (no variables).
   */
  protected static Map<String, Object> evaluate(String dataSonnetSource, Map<String, Object> body) {
    return evaluate(dataSonnetSource, (Object) body, Map.class);
  }

  /** Evaluates an expression whose result is a LIST (e.g. a batch envelope expression). */
  @SuppressWarnings("unchecked")
  protected static java.util.List<Map<String, Object>> evaluateToList(
      String dataSonnetSource, Object body) {
    return evaluate(dataSonnetSource, body, java.util.List.class);
  }

  private static <T> T evaluate(String dataSonnetSource, Object body, Class<T> resultType) {
    DatasonnetExpression dsExpression = new DatasonnetExpression(dataSonnetSource);
    dsExpression.setResultType(resultType);
    dsExpression.setBodyMediaType("application/x-java-object");
    dsExpression.setOutputMediaType("application/x-java-object");

    CamelContext camelContext = new DefaultCamelContext();
    DefaultExchange exchange = new DefaultExchange(camelContext);
    exchange.getMessage().setBody(body);

    return new ValueBuilder(dsExpression).evaluate(exchange, resultType);
  }

  // ---- shared tracker-payload accessors ---------------------------------------------

  @SuppressWarnings("unchecked")
  protected static Map<String, Object> trackedEntity(Map<String, Object> payload) {
    return ((java.util.List<Map<String, Object>>) payload.get("trackedEntities")).get(0);
  }

  @SuppressWarnings("unchecked")
  protected static Map<String, Object> enrollment(Map<String, Object> payload) {
    return ((java.util.List<Map<String, Object>>) trackedEntity(payload).get("enrollments")).get(0);
  }

  @SuppressWarnings("unchecked")
  protected static java.util.List<Map<String, Object>> events(Map<String, Object> payload) {
    return (java.util.List<Map<String, Object>>) enrollment(payload).get("events");
  }

  /**
   * The mediator never generates identifiers — DHIS2 owns UID generation, and idempotency comes
   * from the lookup-and-merge step in the route. Every mapping's output must therefore be free of
   * client-supplied trackedEntity/enrollment/event UIDs.
   */
  protected static void assertCarriesNoClientGeneratedUids(Map<String, Object> payload) {
    org.junit.jupiter.api.Assertions.assertFalse(
        trackedEntity(payload).containsKey("trackedEntity"), "trackedEntity UID must not be set");
    org.junit.jupiter.api.Assertions.assertFalse(
        enrollment(payload).containsKey("enrollment"), "enrollment UID must not be set");
    events(payload)
        .forEach(
            e ->
                org.junit.jupiter.api.Assertions.assertFalse(
                    e.containsKey("event"), "event UID must not be set"));
  }
}
