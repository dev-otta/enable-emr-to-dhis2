package org.hisp.dhis.integration.emr.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

/**
 * Renders an {@link InvalidEmrPayloadException} as the HTTP 400 error response of the mediator's
 * error contract: a JSON body listing exactly which required fields are missing, so an EMR
 * developer can fix their export without reading our logs.
 */
@Component("invalidPayloadResponse")
public class InvalidPayloadResponseProcessor implements Processor {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Override
  public void process(Exchange exchange) throws Exception {
    // Unwrap: the exception may arrive wrapped (CamelExecutionException etc.).
    InvalidEmrPayloadException cause =
        Causes.find(
            exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class),
            InvalidEmrPayloadException.class);
    exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
    exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
    // LinkedHashMap: "patient" is optional (the id itself can be the missing field)
    // and Map.of rejects nulls.
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("status", "ERROR");
    body.put(
        "error",
        "Invalid EMR payload"
            + (cause.getMrn() == null ? "" : " for patient " + cause.getMrn()));
    if (cause.getMrn() != null) {
      body.put("patient", cause.getMrn());
    }
    body.put("missingFields", cause.getMissingFields());
    exchange.getMessage().setBody(OBJECT_MAPPER.writeValueAsString(body));
  }
}
