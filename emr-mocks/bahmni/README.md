# Bahmni sample payloads

`anc-records-batch.json` follows the Bahmni export format.
An array with one object per woman, carrying her identity fields and an
`encounters` array. It is pushed as-is to `POST /api/bahmni/anc-records`.
The envelope expression in `config/datastore/bahmni-envelope.ds` only
unwraps the request body. The entries pass through unchanged.

`anc-record.json` is a single entry from that export (woman 668466). It is
what `POST /api/bahmni/anc-record` accepts, and what the validation and
mapping expressions operate on.

The registration fields (LNMP, EDD, gestational age, the date of the first
visit) travel on the encounter that captured them and are null on the
others. The Bahmni contract itself (required fields, the phone number rule,
how the next appointment is handled) is documented in `docs/REFERENCE.md`.
Note that the shipped samples are a happy-case version of the raw export, which includes all mandatory data as agreed in the contract.
