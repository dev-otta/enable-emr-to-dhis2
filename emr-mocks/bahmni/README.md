# Bahmni sample payloads

`anc-records-batch.json` is the reference export shared by the Bahmni team:
a flat array of encounters, several rows per woman, keyed by `patientId`. It
is pushed as-is to `POST /api/bahmni/anc-records`. The envelope expression
in `config/datastore/bahmni-envelope.ds` folds it into one record per woman.

`anc-record.json` is the per-woman shape that the envelope produces, shown
here for one woman from that export. It is what
`POST /api/bahmni/anc-record` accepts, and what the validation and mapping
expressions operate on.

The Bahmni contract itself (required fields, the phone number rule, how the
next appointment is handled) is documented in `docs/REFERENCE.md`. Note that
the shipped samples deviate from the raw export where the contract has since
been tightened: phone numbers and the first visit's gestational age were
filled in, because both are now mandatory.
