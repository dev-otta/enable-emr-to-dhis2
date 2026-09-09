package org.hisp.dhis.integration.emr.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

/**
 * Enforces the outcome of a datastore-held validation expression.
 *
 * <p>The rules themselves live in the DHIS2 datastore (enable-emr-validation/&lt;source&gt;) as a
 * DataSonnet expression returning {@code {valid: boolean, missingFields: [string]}} — the same
 * engine and lifecycle as the mappings, so the contract can evolve without touching this code.
 * This processor only reads that verdict (from the {@code validationResult} exchange variable) and
 * turns a rejection into an {@link InvalidEmrPayloadException} (HTTP 400 with the field list).
 */
@Component("validationGate")
public class ValidationGateProcessor implements Processor {

  static final String VALIDATION_RESULT_VARIABLE = "validationResult";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Override
  public void process(Exchange exchange) throws Exception {
    String resultJson = exchange.getVariable(VALIDATION_RESULT_VARIABLE, String.class);
    JsonNode result = OBJECT_MAPPER.readTree(resultJson);
    if (!result.path("valid").asBoolean(false)) {
      List<String> missingFields = new ArrayList<>();
      result.path("missingFields").forEach(field -> missingFields.add(field.asText()));
      // The rules also report WHO the verdict is about (null when the id itself
      // is missing) — so a vendor pushing a whole batch knows which patient to fix.
      String mrn = result.hasNonNull("mrn") ? result.path("mrn").asText() : null;
      throw new InvalidEmrPayloadException(missingFields, mrn);
    }
  }
}
