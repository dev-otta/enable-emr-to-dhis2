# Technical reference

This document describes the technical details of this reference implementation. It gives an outline on what the EMRs must send, how their fields map onto the DHIS2 ANC programme, how the
mediator keeps imports idempotent, what can be configured, and what to think
about when deploying or onboarding a new source. Read the
[README](../README.md) first for the overall picture.

1. [Input contracts](#1-input-contracts)
2. [Field mapping](#2-field-mapping)
3. [Identity and idempotency](#3-identity-and-idempotency)
4. [Configuration](#4-configuration)
5. [Onboarding a new EMR](#5-onboarding-a-new-emr)
6. [Deployment](#6-deployment)
7. [Known gaps and open decisions](#7-known-gaps-and-open-decisions)

## 1. Input contracts

Each EMR pushes its own native JSON. The shipped samples in
[`emr-mocks/`](../emr-mocks) are the authoritative examples of both shapes,
and the validation expressions in
[`config/datastore/`](../config/datastore) are the authoritative rules. A
record that breaks a rule is rejected with a 400 that names the missing
fields and the patient concerned, before DHIS2 is contacted.

**PulseTech** sends one record per woman: `pnt_mrn`, optional name fields,
`pnt_date_birth`, `pnt_phone`, `facility_dhis2_org_unit_code`, and an
`anc_followup` array where each entry carries `followup_date`, `lmp_date`,
`edd`, `ga` and `visit_number` (as text, for example `"Visit 3"`).

**Bahmni** exports one object per woman: `patientId`, `birthDate`,
`phoneNumber`, `facilityName`, `facilityCode`, and an `encounters` array
where each entry carries `encounterId`, `encounterDate`, `dateOfFirstVisit`,
`visitNumber`, `gestationalAge`, `nextDate`, `EDD` and `LNMP`. The fields
that describe the pregnancy rather than the visit (LNMP, EDD, the date of
the first visit) travel on the encounter that captured them and are null on
the others. Bahmni does not export names.

Two rules apply to both sources. The phone number is mandatory on every
record, because the SMS programme depends on it; a record without one is a
vendor error, not an admission case. The fields that feed the woman's
profile, meaning LNMP, EDD and the gestational age, arrive as a set. In the
Bahmni export, an encounter that carries LNMP or EDD is the registration
encounter and must carry all three. A record whose encounters carry none of
them is a valid follow-up, because the profile was created when the
registration data was pushed. PulseTech sends the set with every follow-up
entry. Name fields are optional in both contracts: they are mapped when the
export includes them and dropped when it does not.

## 2. Field mapping

The target is programme `WSGAb5XwJ3Y` (ANC - RMNCAH - Antenatal care
registry) with tracked entity type `MCPQUTHX1Ze` (Person). All identifiers
below are verified against the database dump in
[`db-dump/`](../db-dump). Org units are passed through as the facility's
DHIS2 org unit code and resolved by DHIS2 at import
(`orgUnitIdScheme=CODE`); the mediator never looks up an org unit itself.

### Tracked entity attributes

| Attribute | UID | PulseTech | Bahmni |
|---|---|---|---|
| Client MRN | `OYuDdqr2MvX` | `pnt_mrn` | `patientId` as a string |
| First Name | `sB1IHYu2xQT` | `pnt_fname` (optional) | not sent |
| Father's Name | `ZtQqOYot5ut` | `pnt_mname` (optional) | not sent |
| Grandfather's Name | `ENRjVGxVL6l` | `pnt_lname` (optional) | not sent |
| Date of birth | `Fgf497oYvSC` | `pnt_date_birth` | `birthDate` |
| SMS phone number | `RJxLa3nITB3` | `pnt_phone` | `phoneNumber` |

The Age attribute (`B6TnnFMgmCk`) is never sent. A Capture UI program rule
calculates it from the date of birth when a clinician opens the record in DHIS2.

### Enrollment

One enrollment per woman, status ACTIVE for as long as the pregnancy is
ongoing. `enrolledAt` and `occurredAt` are the date of the first visit: for
Bahmni the first `dateOfFirstVisit` found on her encounters (falling back
to the earliest encounter date), for PulseTech the earliest follow-up date.

### Woman's Profile and History

Stage `iF5roNU7QWm`, non-repeatable, so one event per pregnancy. The
mediator builds it only when the record carries registration data, taking
it from the registration encounter whatever its visit number; follow-up-only
records leave the existing profile untouched. It carries LNMP
(`w4ky6EkVahL`), EDD (`Ru01omP2WCQ`), the gestational age in weeks
(`w9p8MQDRyMr`) and the gestational age source (`RPSgZF1i0hk`, always
`LMP`). The gestational age is mapped verbatim from the source. the mapping
performs no computations. Because the set is mandatory on the
registration encounter (see section 1), the gestational age and its source
can remain compulsory data elements in the programme metadata.

### ANC Examination

Stage `JqW7c9HYjVr`, repeatable. One event per visit, with `occurredAt` set
to the visit date and the data value `bXVD2EMF7UW` set to the visit number.
The visit number is what identifies the same visit across pushes.

### Event statuses

A visit that was captured in the EMR is a contact that has already
happened, so profile and examination events import with status COMPLETED
and `completedAt` set to the visit date. This matters in the Capture app,
where the programme's form logic, such as the automatic visit numbering,
keys off completed contacts; imported women behave like hand-entered ones.

Completing an event arms the stage's compulsory data elements
(`validationStrategy=ON_COMPLETE`). The examination stage's "Regular ANC /
Scheduled visit?" element (`xb4Z245Bnej`) is therefore un-flagged as
compulsory in the metadata, since the EMRs do not send it. A dump refresh
must preserve that change.

Re-pushes update completed events in place. The tracker API permits this
under the programme's default settings, and the integration test suite pins
the behaviour.

### The next appointment

The latest visit's `nextDate` becomes one event with status SCHEDULE.
DHIS2 does not allow data values on scheduled events, so the placeholder
carries only a stage, an org unit and a date; in particular it has no visit
number. In the Capture app it looks exactly like an appointment a health
worker scheduled by hand, and overdue logic can key off it.

When the woman attends, the next push contains her new visit, and the merge
turns the open placeholder into that visit: same event, now completed and
carrying data. If the appointment is rescheduled instead, meaning a push
arrives with a changed `nextDate` and no new visit, the placeholder's date
is updated in place. There is at most one open placeholder per woman.

## 3. Identity and idempotency

The mappings emit no identifiers. Before every import, the mediator asks
DHIS2 whether the woman already exists in the programme, by filtering
tracked entities on the identity attribute:

```
GET /api/tracker/trackedEntities?program=<uid>&ouMode=ACCESSIBLE&filter=<identity attribute>:eq:<value>
```

On a hit, the woman's existing identifiers are merged into the mapped
payload, so the import becomes an update. The tracked entity and her active
enrollment are reused directly. Events are matched per stage: examination
events by an equal visit number, the profile by stage and date, and a new
visit with no match claims the open scheduled placeholder, which is how the
appointment becomes the visit. Anything unmatched has no identifier and is
created by DHIS2. Each existing event is claimed at most once.

The identity attribute is configuration
(`DHIS2_TRACKER_IDENTITY_ATTRIBUTE`). The project decision is to identify
women by their phone number (`RJxLa3nITB3`), which both EMRs send and the
SMS programme already depends on. The default value in the reference implementaiton is the
Client MRN attribute (`OYuDdqr2MvX`).

Whatever the attribute, the same properties hold. The attribute is unique
in DHIS2, so two concurrent first pushes for the same woman end in one
create and one rejected import, and the rejected side converges on retry.
Within a batch, each woman is one record (Bahmni exports one object per
woman; the envelope only unwraps the request body), and records are
imported sequentially and synchronously, so a batch cannot race against
itself. The trade-off of
attribute-based identity is that a corrected identifier in the EMR creates
a new person in DHIS2.

## 4. Configuration

Every knob is an environment variable with a default in
`mediator/src/main/resources/application.yaml`. Deployments change `.env`,
not the file.

| Variable | Default | Meaning |
|---|---|---|
| `MEDIATOR_API_KEY_<SOURCE>` | unset | setting a key enables the source; minimum 16 characters, unique per site |
| `DHIS2_API_URL`, `DHIS2_USERNAME`, `DHIS2_PASSWORD` | the compose-internal DHIS2 | the mediator's DHIS2 account |
| `MEDIATOR_MAX_REQUEST_BYTES` | 1 MiB | single-record requests above this get 413 |
| `MEDIATOR_BATCH_MAX_REQUEST_BYTES` | 20 MiB | batch requests above this get 413 |
| `MEDIATOR_BATCH_MAX_RECORDS` | 1000 | batches with more raw entries get 400 |
| `MEDIATOR_THROTTLE_MAX_REQUESTS` / `_PERIOD_MILLIS` | 10 / 1000 | requests beyond this rate get 429; each endpoint has its own budget |
| `DHIS2_TRACKER_IDENTITY_ATTRIBUTE` | `OYuDdqr2MvX` | the attribute the idempotency lookup filters on (see section 3) |
| `DHIS2_TRACKER_EVENT_MATCH_DATA_ELEMENT` | `bXVD2EMF7UW` | the data element that identifies the same visit across pushes |
| `DATASTORE_*_NAMESPACE` | `enable-emr-*` | the datastore namespaces holding the expressions |
| `MEDIATOR_TLS_ENABLED`, `MEDIATOR_KEYSTORE_*` | self-signed demo certificate | TLS settings; alternatively terminate TLS in a proxy |

The tracker imports themselves run with `async=false` (so the caller gets
the real import report), `reportMode=FULL`, `orgUnitIdScheme=CODE`, and
`skipRuleEngine=true`. Skipping the rule engine deserves an explanation:
program rules are form logic for the Capture app, where they continue to
run. Executed against a machine import, the package's "Calculate Age from
DOB" rule assigns the Age attribute to the enrollment, and DHIS2's own
import validation then rejects the payload with error E1019, even though
the mediator never sent that attribute. The package contains no
message-sending rule actions, so skipping rules on import cannot affect the
SMS programme. Set `DHIS2_IMPORT_SKIP_RULE_ENGINE=false` if a future
package version adds a rule that must run at import time.

## 5. Onboarding a new EMR

Adding a source requires no mediator code:

1. Generate an API key (`openssl rand -hex 32`) and set
   `MEDIATOR_API_KEY_<SOURCE>` in `.env`. The endpoint, the security rule
   and the datastore lookups are all derived from the source name.
2. Write the three expressions in `config/datastore/`:
   `<source>-envelope.ds`, `<source>-validation.ds` and
   `<source>-mapping.ds`. Run `yarn mappings:pack` and re-seed the
   datastore.
3. Add a sample export in `emr-mocks/<source>/` and golden-file tests.

On the DHIS2 side, the facility's org unit must exist with the code the EMR
will send, and it must be assigned to the ANC programme. An org unit that
exists but is not assigned to the programme fails the import with error
E1029.

When an existing contract changes, for example a field is added or a
mandatory-field decision flips, the whole edit is the rule row or mapping
line in the `.ds` file, the sample in `emr-mocks/`, and
`yarn mappings:pack && yarn fixtures:update`. The golden test fixtures are
derived from the shipped mappings and samples, never maintained by hand, so
a contract change does not cascade through the test suite.

## 6. Deployment

`docker-compose.poc.yml` runs the mediator alone in front of an existing
DHIS2 instance, which is the shape of a real deployment. The checklist:

* Use a least-privilege DHIS2 service account, not a superuser. Prefer a
  personal access token over a password; the DHIS2 Camel component supports
  `personalAccessToken`.
* The account must be able to search across all participating org units.
  The idempotency lookup uses `ouMode=ACCESSIBLE`, and an account with too
  narrow a search scope misses existing women and creates duplicates.
* Seed the datastore with `scripts/seed-datastore.sh` and verify the
  instance with `scripts/verify-metadata.sh`, which checks every identifier
  the mappings use plus the facility codes the EMRs will send.
* If the instance was not loaded from the shipped dump, apply the metadata
  changes the contracts assume: `scripts/relax-mandatory-attributes.sh`
  makes the name attributes non-mandatory, and the "Regular ANC / Scheduled
  visit?" data element must be un-flagged as compulsory on the examination
  stage. These are programme metadata changes and should be signed off by
  the metadata owner. The phone number stays mandatory on purpose.
* Use real TLS: mount a keystore or terminate TLS in a reverse proxy.
  Generate per-site API keys and exchange them out of band.
* Payloads and import reports contain personal data and are logged at DEBUG
  level only.

## 7. Known gaps and open decisions

There is no queue or retry in the mediator. A 502 means the EMR pushes
again, which is safe because imports are idempotent. Batches are capped
rather than streamed; an unbounded historical backfill would need a
streaming format later. Monitoring is the health endpoint plus logs.

The identity switch from MRN to phone number (section 3) is decided but not
yet implemented.

No pregnancy-outcome stop condition is imported, because neither EMR sends
an outcome yet. Until one does, nothing in the tracker marks a pregnancy as
ended, which the SMS programme needs to stop its campaigns; its current
stop rule is a fixed number of weeks after enrollment.

Imported women have no value for the Age attribute. The Capture app's
program rule fills it the first time a human edits the record. This is
cosmetic; nothing reads the attribute.

The uniqueness scope of Bahmni's `patientId` across separate Bahmni
instances is an open question with the Bahmni team. If it turns out to be
unique per instance only, the mapping should prefix it with a facility or
instance code. The question loses most of its weight once the identity
switch to phone numbers is made.
