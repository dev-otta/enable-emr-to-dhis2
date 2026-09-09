# PulseTech mock payloads

These files stand in for the JSON that PulseTech pushes to the mediator's
`POST /api/pulsetech/anc-record` endpoint. The shape follows the reference
export PulseTech shared (`internal_docs/pulsetech-enable-intero-sample.json`),
with two deliberate deltas — both worth raising with PulseTech:

1. **Name fields added** (`pnt_fname`, `pnt_mname`, `pnt_lname`). The shared
   sample omitted them, but the DHIS2 ANC program marks First Name, Father's
   Name, Date of birth, SMS phone and MRN as **mandatory** program attributes —
   an import without them was rejected. (The Bahmni contract later forced
   those flags to be relaxed — `scripts/relax-mandatory-attributes.sh` — but
   PulseTech sends names, so this pipeline keeps sending them.) The keys used here are the ones from the
   full PulseTech schema in the crosswalk workbook (`EMR_PulseTech` sheet).
   Convention (to ratify with the EMR teams, same open point as the previous
   iteration): `pnt_fname` → First Name, `pnt_mname` → Father's Name,
   `pnt_lname` → Grandfather's Name.
2. **`facility_dhis2_org_unit_code` filled in** with a real code from the
   ENABLE dump (`1057888` = Felege Mels Health center, UID `KwDiiYMUija`) —
   the shared sample carried the placeholder `<SITE_DHIS2_CODE>`.

Unmapped source fields, on purpose: `pnt_gender` (the Person tracked-entity
type has no gender attribute in the ENABLE package), `facility_name`
(informational; the org-unit **code** is authoritative), and `anc_visit[]`
(visit registrations — the clinical detail the tracker needs lives in
`anc_followup[]`; the field-by-field mapping is in `docs/REFERENCE.md`).
