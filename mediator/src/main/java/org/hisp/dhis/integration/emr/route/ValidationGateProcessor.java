package org.hisp.dhis.integration.emr.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
@Component("validationGate")
public class ValidationGateProcessor implements Processor {

  static final String VALIDATION_RESULT_VARIABLE = "validationResult";

  private static final Logger LOG = LoggerFactory.getLogger(ValidationGateProcessor.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Override
  public void process(Exchange exchange) throws Exception {
    String resultJson = exchange.getVariable(VALIDATION_RESULT_VARIABLE, String.class);
    JsonNode result = OBJECT_MAPPER.readTree(resultJson);
    if (!result.path("valid").asBoolean(false)) {
      List<String> missingFields = new ArrayList<>();
      result.path("missingFields").forEach(field -> missingFields.add(field.asText()));
      String mrn = result.hasNonNull("mrn") ? result.path("mrn").asText() : null;
      LOG.warn(
          "Rejected record from {}-{}: missing mandatory fields ({})",
          exchange.getVariable("source", String.class),
          result.path("facility").asText("unknown"),
          String.join(", ", missingFields));
      throw new InvalidEmrPayloadException(missingFields, mrn);
    }
  }
}
