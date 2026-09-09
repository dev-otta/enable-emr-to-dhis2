# Changes

## 2026-09-02 (GA is mandatory with visit 1 — no metadata un-flag needed)

Follow-up ruling to the COMPLETED change: GA, LNMP and EDD are ALL given with
visit 1 (mapping agreement). So:

- Bahmni validation gains the rule row: `visits[].gestationalAge` required on
  every visit-1 entry (vendor-readable 400; PulseTech already required `ga`).
  The mapping keeps its defensive omit-when-null.
- GA + GA source therefore STAY compulsory in the DHIS2 metadata — the only
  un-flag remains "Regular ANC?" (done 2026-09-02, in the dump). The
  previously flagged GA un-flag step is CANCELLED.
- Reference batch: real-export woman 668462 had `gestationalAge: null` on her
  visit 1 — filled (6, from her LNMP-to-visit span), same precedent as the
  phone policy. Harness + unit tests cover required-with-visit-1,
  not-required-without, and the defensive omit.

## 2026-09-02 (captured contacts are COMPLETED; the 400 names the patient)

Ruling landed: a visit captured in the source IS a completed contact.

- Both mappings: profile + exam events now import with `status: COMPLETED`
  and `completedAt` = the visit date (clinically truthful, not import time).
  The SCHEDULE placeholder is deliberately untouched — it is what overdue
  tracking keys off — and the enrollment stays ACTIVE.
- Why it matters in Capture: the package's UI logic (visit auto-numbering
  etc.) keys off completed contacts — imported women now behave like
  hand-entered ones.
- **Metadata prerequisites** (ON_COMPLETE arms compulsory fields): "Regular
  ANC / Scheduled visit?" un-flagged (done, in the refreshed dump). GA + GA
  source on the profile must be un-flagged the SAME way — per the mapping
  agreement GA is optional, and the mapping omits it when absent, so a
  GA-less profile would otherwise fail to complete. LNMP/EDD stay mandatory
  in OUR validation (vendor-readable 400), per the agreement.
- The 400 now names the patient: validation verdicts gained `mrn`, the error
  body gained `patient` and the message reads "Invalid EMR payload for
  patient <MRN>" — a vendor pushing a whole batch knows which record to fix.
  Batch INVALID rows fall back to the verdict's mrn too (the mapping never
  ran for them).
- Tests: DataSonnet suites assert COMPLETED/completedAt per source and the
  placeholder's SCHEDULE-with-no-completedAt; TrackerRoundTripIT asserts
  statuses against real DHIS2 AND (via the unchanged re-push tests) that the
  tracker API updates COMPLETED events in place; e2e asserts exam statuses;
  functional 400 tests cover patient-named and id-itself-missing cases.
  Fixtures regenerated. Deploy: re-seed BOTH sources + rebuild the mediator.

## 2026-08-31 (mappings map; they do not practice medicine)

Ruling: the GA-from-LNMP derivation (civil-date arithmetic in
`bahmni-mapping.ds`) is gone. A mapping table's job is field mapping — if the
source doesn't send gestational age, the profile simply has none. GA and its
source label (`LMP`) are now emitted only when Bahmni sends a value; the
`epochDays`/`weeksBetween` helpers are deleted. Unit test + harness updated
(the real export's 668462 has GA null on visit 1 → her profile now carries
LNMP + EDD only).

Discovered while verifying: GA + GA source are the profile stage's only two
COMPULSORY data elements, and the exam stage has one we never send
("Regular ANC / Scheduled visit?", `xb4Z245Bnej`). Both stages use
`validationStrategy=ON_COMPLETE`, so nothing is enforced while imported
events are ACTIVE — but if the pending "import visits as COMPLETED" ruling
lands, DHIS2 will start rejecting profiles without GA and every exam event
without `xb4Z245Bnej`. The two decisions must be made together (flagged in
REFERENCE.md §2/§7).

## 2026-08-28 (lookup fields: event status was missing — duplicate placeholders)

Manual testing caught what the suites couldn't: re-pushing with a MOVED
nextDate created a second SCHEDULE event instead of updating the open one.
Cause: the idempotency lookup's `fields=` never requested the event `status`,
and the new pass-2 merge finds the open placeholder BY status — so it was
invisible. (The e2e re-push test uses an identical payload, which still
matched via the same-date fallback; the moved-date flow lives in the
Testcontainers IT, currently blocked on local Docker.)

- `TrackedEntityLookupRequestProcessor.LOOKUP_FIELDS` now includes `status`,
  with a comment marking the whole list as load-bearing for the merge.
- `RouteFunctionalTestCase` pins the lookup's `fields=` parameter so a field
  can never silently drop again.

## 2026-08-28 (the fifth 409: SCHEDULE events cannot carry data values)

E1315: DHIS2 forbids data values on `SCHEDULE`-status events — and our
placeholder carried exactly one, the visit number, which was its merge key.
WireMock could never catch this; the real instance did, the moment the
org-unit error stopped masking it.

- `bahmni-mapping.ds`: the placeholder now carries NO data values (stage,
  orgUnit, scheduledAt, status only).
- `ExistingTrackedEntityMergeProcessor`: two-pass matching. Pass 1 unchanged
  (visit-number equality; stage+date for key-less events). Pass 2: the
  stage's open SCHEDULE event is claimed by a real visit with no exact match
  (attendance fulfils the appointment — the placeholder BECOMES the visit,
  whichever visit number she returns with) or by an incoming placeholder
  whose date moved (a reschedule updates in place, never duplicates). At most
  one open placeholder exists per stage by construction.
- Semantics change, deliberate: previously a skipped visit left the
  placeholder open even after later visits arrived. Now ANY attendance
  claims it — truer to "the appointment was kept" and it stops stale overdue
  flags once she is back in care.
- Golden fixture, DataSonnet/merge/IT unit tests, e2e assertions and docs
  updated; new `TrackerQueries.scheduledEvent()` helper (status is now the
  only way to find the placeholder).
- Deploy note: this change lives in the DATASTORE mapping + the mediator jar
  — re-seed (`scripts/seed-datastore.sh`) AND rebuild.

## 2026-08-28 (demo data: one facility for everything)

The fourth 409 (Bahmni only): E1029/E1041 — Ferensay Around Health Center
(`1060570`) exists but is NOT assigned to the ANC program, so enrollments and
events there are rejected. Instead of touching DHIS2 metadata, the Bahmni
mocks (samples, golden fixture, IT payload, README) now use PulseTech's
facility — Felege Meles Health center, `1057888` — so every demo woman lands
in one place in the Capture app. Real onboarding lesson kept for later: a new
facility's org unit must be ASSIGNED to the program, not just exist with the
right code.

## 2026-08-28 (the third 409: DHIS2's rule engine vs its own validator)

After the phone policy fix and the ghost purge, e2e STILL failed with 409 —
the FULL report finally showed why: **E1019**
`Only Program attributes is allowed for enrollment: B6TnnFMgmCk=27`. The
package's "Calculate Age from DOB" program rule (`wFJ2MLKSCNB`) fires during
the tracker import, ASSIGNs the Age attribute into the enrollment, and DHIS2's
own validation then rejects the payload the rule engine just produced. Not
our payload (the mediator never sends Age), not the dump edits (old vs new
dump have byte-identical rule tables) — a stock-package rule colliding with
the import validator.

- Fix: all mediator imports now run with **`skipRuleEngine=true`**
  (`DHIS2_IMPORT_SKIP_RULE_ENGINE`, default true). Rules are a Capture-UI
  concern; they still run for humans. Safe for SMS: the dump has ZERO
  SENDMESSAGE/SCHEDULEMESSAGE rule actions — the SMS programme runs off
  program notifications, which are unaffected.
- Side effect: this also resolves the GA experiment — no rule can derive GA
  on import, so the mediator's trust-or-derive logic IS the GA source (docs
  updated). Imported women simply have no Age value until a human edit
  triggers the rule; cosmetic.
- `RouteFunctionalTestCase` asserts the parameter reaches
  `/api/tracker?...skipRuleEngine=true`. No dump change needed.

## 2026-08-28 (e2e self-cleanup + reset tooling)

- **`yarn test:e2e:wipe`**: a Playwright globalTeardown (off by default) that,
  after the suite, tracker-deletes the test women it created AND hard-purges
  the soft-deleted rows via the maintenance endpoint — the step the UI never
  does, and without which the unique MRNs stay reserved (the root cause of
  this week's mysterious 409s). Plain `yarn test:e2e` still leaves the women
  in place for inspection; the suite is re-runnable against its own leftovers
  either way.
- New ops scripts: `scripts/check-mrns-free.sh` (verifies MRNs are free via
  the API AND the database's deleted flag — the API alone hides soft-deleted
  ghosts) and `scripts/wipe-tracker-data.sh` (full tracker reset of the ANC
  program, confirmation-gated; for preparing the clean dump the repo ships).

## 2026-08-28 (policy reversal: the phone number is REQUIRED)

E2e against the refreshed dump revealed Phone Number still mandatory in the
ANC program — and the ruling is that it SHOULD be: the SMS programme is the
point, so a phoneless record is a vendor error, not an admission case.

- Bahmni validation gains the `phoneNumber` rule row (vendor-readable 400
  instead of DHIS2's error code); the mapping keeps a defensive
  omit-when-null.
- Test data: the two phoneless women in the reference export got phone
  numbers (668465 → 0911223344, 668466 → 0911556677); sample, golden fixture,
  unit/IT/e2e assertions and docs updated. `scripts/relax-mandatory-attributes.sh`
  now touches ONLY First/Father's Name — phone stays mandatory everywhere.
- Still needed on the dump: purge the six soft-deleted ghosts
  (maintenance endpoint) and re-dump — they block re-creating those MRNs.

**New: `yarn fixtures:update`** — regenerates every golden tracker-payload
fixture from the shipped mappings + samples (FixtureRegeneratorTestCase,
activated by -Dfixtures.write=true). Fixtures are now DERIVED, never
hand-edited: a contract change is mapping/rules + sample + regenerate, and
docs point at the rule tables instead of restating field lists — the
anti-cascade conventions are documented in docs/REFERENCE.md §5.

## 2026-08-28 (error handling made symmetric — second test-run fix)

Second local run: 57/61 green; the four failures were all single-record error
paths returning 500. Root cause: a route-scoped `onException` only catches
exceptions thrown by that route's OWN steps — an exception propagating out of
a route called via `direct:` (the shared core) never consults the caller's
handlers. The batch path already worked because `doTry/doCatch` DOES catch
exceptions from called routes. Fix: the single path now uses the same pattern —
`ingest-anc-record-route` wraps its flow in doTry with doCatch rendering the
400 (invalid payload) and 409/502 (DHIS2 errors); the batch route gained a
doCatch for pre-split failures (datastore read/envelope) → 502. The
routeConfiguration keeps only what receive routes throw themselves: the
envelope gate's 400 and the throttle's 429. The core stays handler-free on
purpose: it throws, each caller decides what a failure means.

## 2026-08-28 (Testcontainers layer + shared test support)

- **New integration layer against a REAL DHIS2** (the pattern from
  dhis2/integration-dhis-rapidpro): `Dhis2Environment` boots
  ghcr.io postgis + `dhis2/core:2.41.5.1` via Testcontainers, loads the
  shipped ENABLE dump, seeds the datastore with the shipped expressions —
  once per JVM, shared across `*IT` classes. `TrackerRoundTripIT` proves what
  WireMock structurally cannot: real create/update round trips by MRN,
  idempotent re-push (same DHIS2-generated UIDs, unchanged event counts), the
  real Bahmni export landing 5 women incl. nameless/phoneless admission, the
  SCHEDULE placeholder BECOMING the attended visit (same event UID) with the
  next placeholder rolling forward, phone-number enrichment on a later push,
  and the 409 for an unresolvable facility code.
- **Layer separation**: failsafe runs `*IT` in `mvn verify`
  (`yarn test-mediator:it`, new CI job); plain `mvn test` stays Docker-free
  and fast. Playwright e2e stays as the deployed-stack smoke suite.
- **Shared test support instead of per-class re-implementation**
  (`support/`): `TestPayloads` (keys + shipped-artifact readers),
  `Dhis2ApiStub` (ONE WireMock with happy-path defaults + request journals),
  `AbstractMediatorHttpTestCase` → `AbstractIngestFunctionalTestCase` (stub)
  / `AbstractDhis2IT` (real instance), `TrackerQueries` (find-by-MRN reads
  the way the mediator's lookup does). The three WireMock suites were
  refactored onto it — test bodies unchanged, all local infrastructure gone.

## 2026-08-28 (test-run fixes — first real `mvn test` of the suite)

Three failures from the first local run, three root causes, all fixed:

- **Batch result `index` was null**: `CamelSplitIndex` is an exchange
  PROPERTY, not a header — `BatchEntryOutcomeProcessor` now reads
  `exchange.getProperty(Exchange.SPLIT_INDEX)` (and the batch WARN log uses
  `${exchangeProperty.CamelSplitIndex}`).
- **DHIS2's 409 surfaced as 502**: the caught exception can arrive wrapped
  (CamelExecutionException), so `instanceof RemoteDhis2ClientException`
  missed it. New `Causes.find()` walks the cause chain — the same matching
  Camel itself does — in `Dhis2ErrorResponseProcessor`,
  `InvalidPayloadResponseProcessor` (which could NPE on a wrapped exception)
  and `BatchEntryOutcomeProcessor`.
- **Batch push answered 403 on DHIS2 rejections**: two compounding issues.
  The `routeConfiguration` onException was GLOBAL, so it fired inside the
  shared core route and stole exceptions from the batch split's per-entry
  doTry/doCatch; it is now scoped by id (`http-error-contract`) to the two
  receive routes only. And Spring Security denied the servlet ERROR dispatch
  (`anyRequest().denyAll()` applies to /error in Spring Security 6), turning
  any escaped exception into an opaque 403 — the ERROR dispatch is now
  permitted, so a genuine internal failure renders as a real 500.

## 2026-08-28 (cleanup pass before manual testing)

- **Fixed a real bug**: `scripts/verify-metadata.sh` extracted UIDs by matching
  `program: 'UID'`-style inline usage — the restructured mappings keep UIDs in
  named constant blocks, so it extracted NOTHING. Now extracts any quoted
  11-char DHIS2 id (verified: all 15 UIDs, no false positives).
- Removed the stale architecture deck from `docs/` (pre-real-Bahmni content;
  presentation material lives in `internal_docs/`), refreshed the stale
  "assumed contract" note in `.env.example`, and moved the GitHub workflow
  files into `.github/workflows/` on the working copy (they had been parked in
  a staging folder because the remote tools may not write that path).
- Reviewed, judged, and deliberately NOT changed (working > polished for the
  POC): mapping data-values as omit-if-null row tables (introduce with the
  first real batch of new SMS data elements — the pattern is documented in the
  validation rule tables already), per-source throttle budgets, a TTL cache
  for datastore expressions, RFC 9457 problem+json error bodies.

## 2026-08-28 (later) — mandatory flags move into the dump

The relaxed mandatory flags (First/Father's Name, Phone) are now expected to
be IN the DB dump rather than patched at boot: the seed container no longer
PATCHes program metadata, `db-dump/README.md` documents how to refresh the
dump (`docker compose exec -T db pg_dump ... | gzip`), and
`scripts/relax-mandatory-attributes.sh` remains only for instances not loaded
from the updated dump. Until the refreshed dump is committed, a fresh local
stack still has the old flags — run the relax script once (or refresh the
dump) before pushing Bahmni samples.

## 2026-08-28 — Bahmni on the real export + documentation overhaul

**Bahmni pipeline now runs on the real reference export** (the flat
8-encounter / 5-women file the Bahmni team shared) instead of the assumed
nested shape:

- **New: batch envelope expressions** (`enable-emr-envelope/<source>`, third
  datastore key per source). Bahmni's folds the flat encounter array into one
  record per woman (grouped by `patientId`, visits sorted, patient fields
  hoisted first-non-null) — which also makes batch entries race-free by
  construction. PulseTech's is a pass-through. The batch route fetches and
  applies it once per file; the batch endpoint now accepts a **bare JSON
  array** as well as `{"records":[...]}`.
- **Bahmni validation rewritten** for the real shape, structural-only:
  `patientId`, `birthDate`, `facilityCode`, per-visit `encounterDate` +
  `visitNumber`; `lnmp`/`edd` required **only when the record contains
  visit 1**; `phoneNumber` deliberately NOT required (policy: admit, enrich
  later).
- **Bahmni mapping rewritten**: `patientId` → Client MRN (as string); no name
  attributes; null phone omitted; profile event only with visit 1 (GA: trust
  Bahmni's value, derive from LNMP when null); one exam event per encounter;
  the LATEST visit's `nextDate` → ONE SCHEDULE event tagged visit `latest+1`
  (the merge key — an attended visit updates the placeholder in place).
- **Metadata prerequisite shipped**: `scripts/relax-mandatory-attributes.sh`
  un-mandatories First Name, Father's Name and Phone in the ANC program (MRN +
  DOB stay mandatory) — link UIDs resolved from the dump (`CPqaCwQRcKF`,
  `TK8w3cO2T8s`, `Iq3a5idf2bw`). The compose seed applies it automatically;
  on a shared instance run it deliberately with the metadata owner's sign-off.

**DataSonnet restructured for readability and growth** (all six expressions):
named constant blocks (`ATTRIBUTE`/`DATA_ELEMENT`/`STAGE`), small helpers
(`attribute()`, `dataValue()`, guarded `get()`/`present()`, civil-date
`weeksBetween` for GA derivation), and validation as **rule tables** — adding
a future SMS field is one row. PulseTech behaviour is regression-pinned:
identical output to before the restructure.

**Docs cut hard.** One concise README (what it does, architecture diagram,
getting started: setup → seeding → tests → manual pushes) plus one
`docs/REFERENCE.md` (contracts, mapping tables/UIDs, idempotency, config,
onboarding recipe, deployment checklist, known gaps). Removed:
ARCHITECTURE.md, WORKFLOW.md, TESTING.md, SETUP.md, DEPLOYMENT.md,
CODE-TOUR.md, SECURITY.md, VERSIONS.md — including all previous-iteration
history ("why no FHIR" etc.).

**Tests updated to the real data**: new `BahmniEnvelopeDataSonnetTestCase`;
Bahmni mapping/validation test classes rewritten (GA derivation, no-profile
follow-up records, phoneless women, per-visit rules);
`BatchRouteFunctionalTestCase` drives the real export end to end (5 per-woman
results keyed by MRN, one invalid woman doesn't sink the file); e2e suite
pushes the real export against real DHIS2 and proves the nameless/phoneless
policy + SCHEDULE placeholder semantics. Routes validated against the Camel
YAML DSL schema (camel-MCP) and cross-checked against the LIS/MOSIP reference
conventions (variableReceive, direct: sub-routes, dhis2 component idioms).

Verified in-sandbox (46-check harness): envelope grouping, all five real women
validate + map cleanly, GA derivation, schedule merge-key, PulseTech
regression, packed-file drift. **Not yet run: `yarn test-mediator` and
`yarn test:e2e`** (Maven/Docker unavailable in the build sandbox) — run
locally before pushing.

## Earlier

- Batch endpoint (`/api/{source}/anc-records`) with per-entry results.
- Idempotency refactored to lookup-and-merge — DHIS2 owns all UIDs.
- Clean-architecture pass: parameterized endpoint, config-driven sources,
  datastore-held validation, guardrails (keys, size, throttle, TLS).
- Initial mediator: PulseTech JSON → ANC tracker, FHIR transport dropped.
