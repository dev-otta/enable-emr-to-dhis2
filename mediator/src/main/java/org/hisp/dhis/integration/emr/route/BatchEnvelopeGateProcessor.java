package org.hisp.dhis.integration.emr.route;

import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cheap pre-gate for the batch endpoint, run before the source's envelope expression: the body
 * must be a non-empty JSON array (bare, or wrapped as {@code {"records": [...]}}) within the
 * configured entry cap ({@code mediator.batch.max-records}). A bad envelope is a 400 for the WHOLE
 * request; entry-level problems are never the envelope's business — they surface per entry in the
 * result report.
 *
 * <p>The cap counts RAW input entries (e.g. Bahmni encounters), bounding the work a single request
 * can demand before anything is fetched or transformed. On success the body becomes the raw entry
 * list, ready for the envelope expression.
 */
@Component("batchEnvelopeGate")
public class BatchEnvelopeGateProcessor implements Processor {

  private final int maxRecords;

  public BatchEnvelopeGateProcessor(@Value("${mediator.batch.max-records}") int maxRecords) {
    this.maxRecords = maxRecords;
  }

  @Override
  @SuppressWarnings("unchecked")
  public void process(Exchange exchange) {
    Object body = exchange.getMessage().getBody();
    Object records =
        body instanceof Map ? ((Map<String, Object>) body).get("records") : body;

    if (!(records instanceof List) || ((List<Object>) records).isEmpty()) {
      throw new InvalidEmrPayloadException(
          List.of("records (a non-empty JSON array — bare, or as {\"records\":[...]})"), null);
    }
    List<Object> recordList = (List<Object>) records;
    if (recordList.size() > maxRecords) {
      throw new InvalidEmrPayloadException(
          List.of(
              "records (exceeds the maximum of "
                  + maxRecords
                  + " entries per request — split the batch)"),
          null);
    }

    exchange.getMessage().setBody(recordList);
  }
}
