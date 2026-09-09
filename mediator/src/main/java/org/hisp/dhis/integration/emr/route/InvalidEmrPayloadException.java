package org.hisp.dhis.integration.emr.route;

import java.util.List;

/**
 * Thrown by a pipeline's structural validation step when a pushed EMR record is missing required
 * fields. Mapped to HTTP 400 with the precise list of what is missing — a vendor who is told
 * "anc_followup[0].lmp_date is missing" fixes it themselves; one who gets a DataSonnet stack trace
 * e-mails us.
 */
public class InvalidEmrPayloadException extends RuntimeException {
  private final List<String> missingFields;
  private final String mrn;

  public InvalidEmrPayloadException(List<String> missingFields, String mrn) {
    super(
        "Missing required fields"
            + (mrn == null ? "" : " for patient " + mrn)
            + ": "
            + String.join(", ", missingFields));
    this.missingFields = List.copyOf(missingFields);
    this.mrn = mrn;
  }

  public List<String> getMissingFields() {
    return missingFields;
  }

  /** The record's own patient identifier, when it carried one — null otherwise. */
  public String getMrn() {
    return mrn;
  }
}
