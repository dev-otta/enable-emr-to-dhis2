package org.hisp.dhis.integration.emr.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.hisp.dhis.integration.sdk.api.RemoteDhis2ClientException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Turns one batch entry's fate into one result object — the per-entry rows of the batch
 * response. Runs in the split's doFinally, so it sees both outcomes:
 *
 * <ul>
 *   <li>no exception, import report OK → {@code IMPORTED} (+ the report's stats)
 *   <li>no exception, report carries errors → {@code CONFLICT} (+ the report)
 *   <li>{@link InvalidEmrPayloadException} → {@code INVALID} (+ the exact missing fields)
 *   <li>{@link RemoteDhis2ClientException} → {@code REJECTED} (+ DHIS2's own response)
 *   <li>anything else → {@code ERROR} (+ detail)
 * </ul>
 *
 * <p>Every row carries the entry's {@code index}; when the entry got far enough to be mapped,
 * the row also carries its {@code mrn} (read from the mapped payload via the configured identity
 * attribute) so operators can correlate without counting.
 */
@Component("batchEntryOutcome")
public class BatchEntryOutcomeProcessor implements Processor {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final String identityAttribute;

  public BatchEntryOutcomeProcessor(
      @Value("${dhis2.tracker.identity-attribute}") String identityAttribute) {
    this.identityAttribute = identityAttribute;
  }

  @Override
  @SuppressWarnings("unchecked")
  public void process(Exchange exchange) throws Exception {
    Map<String, Object> result = new LinkedHashMap<>();
    // CamelSplitIndex is an exchange PROPERTY (the split EIP does not set headers).
    result.put("index", exchange.getProperty(Exchange.SPLIT_INDEX, Integer.class));
    String mrn = mrnFromMappedPayload(exchange);
    if (mrn != null) {
      result.put("mrn", mrn);
    }

    // Unwrap: exceptions from the core route may arrive wrapped.
    Exception caught = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
    InvalidEmrPayloadException invalid = Causes.find(caught, InvalidEmrPayloadException.class);
    RemoteDhis2ClientException remote = Causes.find(caught, RemoteDhis2ClientException.class);
    if (invalid != null) {
      result.put("status", "INVALID");
      // For an INVALID entry the mapping never ran, so the payload-derived mrn
      // above is absent — fall back to the one the validation rules reported.
      if (mrn == null && invalid.getMrn() != null) {
        result.put("mrn", invalid.getMrn());
      }
      result.put("missingFields", invalid.getMissingFields());
    } else if (remote != null) {
      result.put("status", "REJECTED");
      result.put("httpStatusCode", remote.getHttpStatusCode());
      Object dhis2Response = tryParse(remote.getBody());
      if (dhis2Response != null) {
        result.put("dhis2Response", dhis2Response);
      } else {
        result.put("detail", remote.getMessage());
      }
    } else if (caught != null) {
      result.put("status", "ERROR");
      result.put("detail", caught.getMessage());
    } else {
      Map<String, Object> report = exchange.getMessage().getBody(Map.class);
      if (report != null && "OK".equals(report.get("status"))) {
        result.put("status", "IMPORTED");
        if (report.get("stats") != null) {
          result.put("stats", report.get("stats"));
        }
      } else {
        result.put("status", "CONFLICT");
        result.put("report", report);
      }
    }

    exchange.getMessage().setBody(result);
  }

  private String mrnFromMappedPayload(Exchange exchange) {
    String payloadJson = exchange.getVariable("trackerPayload", String.class);
    if (payloadJson == null) {
      return null; // the entry failed before mapping
    }
    try {
      JsonNode attributes =
          OBJECT_MAPPER.readTree(payloadJson).path("trackedEntities").path(0).path("attributes");
      for (JsonNode attribute : attributes) {
        if (identityAttribute.equals(attribute.path("attribute").asText())) {
          return attribute.path("value").asText();
        }
      }
    } catch (Exception ignored) {
      // correlation nicety only — never fail an entry over it
    }
    return null;
  }

  private static Object tryParse(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      return OBJECT_MAPPER.readValue(body, Object.class);
    } catch (Exception notJson) {
      return body;
    }
  }
}
