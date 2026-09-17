// End-to-end tests: mock EMR payloads -> mediator -> REAL DHIS2 (compose stack).
//
// These cover what the Java route tests (WireMock) deliberately cannot: DHIS2's
// actual tracker import semantics against the ENABLE dump — mandatory program
// attributes, program rules, SCHEDULE events, org-unit code resolution, and true
// idempotent re-push (lookup-and-merge against a real instance).
//
// Requires the stack from docker-compose.yml to be up, with the same .env
// (the tests read the API keys and DHIS2 credentials from the environment).
//
// The mediator never generates UIDs — DHIS2 does — so the tests find women the
// same way the mediator does: by MRN attribute + program.

const { expect, test } = require("@playwright/test");
const fs = require("fs");
const path = require("path");

const MEDIATOR =
  process.env.MEDIATOR_URL || `https://localhost:${process.env.MEDIATOR_PORT || 9070}`;
const DHIS2 =
  process.env.DHIS2_URL || `http://localhost:${process.env.DHIS2_PORT || 8080}`;
const PULSETECH_KEY = process.env.MEDIATOR_API_KEY_PULSETECH;
const BAHMNI_KEY = process.env.MEDIATOR_API_KEY_BAHMNI;
const DHIS2_AUTH = {
  username: process.env.SEED_DHIS2_USER || "admin",
  password: process.env.SEED_DHIS2_PASSWORD || "district",
};

const PROGRAM = "WSGAb5XwJ3Y";
const MRN_ATTRIBUTE = "OYuDdqr2MvX";
const PROFILE_STAGE = "iF5roNU7QWm";
const EXAM_STAGE = "JqW7c9HYjVr";
const VISIT_NUMBER_DE = "bXVD2EMF7UW";

const PULSETECH = { record: readSample("pulsetech/anc-record.json"), mrn: "719685" };
const BAHMNI = { record: readSample("bahmni/anc-record.json"), mrn: "668466" };

function readSample(rel) {
  return JSON.parse(
    fs.readFileSync(path.join(__dirname, "..", "emr-mocks", rel), "utf8")
  );
}

async function pushRecord(request, endpoint, apiKey, record) {
  return request.post(`${MEDIATOR}/api/${endpoint}/anc-record`, {
    headers: { "X-API-KEY": apiKey, "Content-Type": "application/json" },
    data: record,
  });
}

function basicAuth() {
  return (
    "Basic " +
    Buffer.from(`${DHIS2_AUTH.username}:${DHIS2_AUTH.password}`).toString("base64")
  );
}

// Finds women exactly the way the mediator's idempotency lookup does.
async function findByMrn(request, mrn) {
  const response = await request.get(`${DHIS2}/api/tracker/trackedEntities`, {
    params: {
      program: PROGRAM,
      ouMode: "ACCESSIBLE",
      filter: `${MRN_ATTRIBUTE}:eq:${mrn}`,
      fields:
        "trackedEntity,attributes[attribute,value],enrollments[enrollment,status,events[event,programStage,status,occurredAt,scheduledAt,dataValues[dataElement,value]]]",
    },
    headers: { Authorization: basicAuth() },
  });
  expect(response.status()).toBe(200);
  const body = await response.json();
  return body.instances || body.trackedEntities || [];
}

function attributeValue(trackedEntity, attributeUid) {
  return trackedEntity.attributes.find((a) => a.attribute === attributeUid)?.value;
}

function dataValue(event, dataElementUid) {
  return event.dataValues.find((dv) => dv.dataElement === dataElementUid)?.value;
}

test.beforeAll(async ({ request }) => {
  expect(PULSETECH_KEY, "MEDIATOR_API_KEY_PULSETECH must be set").toBeTruthy();
  // BAHMNI_KEY is optional: the source is disabled without it, and its test skips.
  const health = await request.get(`${MEDIATOR}/api/health`);
  expect(health.status()).toBe(200);
});

test("PulseTech record lands as a tracked entity with enrollment and events", async ({
  request,
}) => {
  const response = await pushRecord(request, "pulsetech", PULSETECH_KEY, PULSETECH.record);
  expect(response.status()).toBe(200);
  expect((await response.json()).status).toBe("OK");

  const instances = await findByMrn(request, PULSETECH.mrn);
  expect(instances).toHaveLength(1);
  const te = instances[0];

  // DHIS2 generated the identifiers — they just have to exist.
  expect(te.trackedEntity).toMatch(/^[A-Za-z][A-Za-z0-9]{10}$/);

  // Identity attributes (what the SMS programme needs).
  expect(attributeValue(te, MRN_ATTRIBUTE)).toBe("719685");
  expect(attributeValue(te, "sB1IHYu2xQT")).toBe("Abeba"); // First name
  expect(attributeValue(te, "RJxLa3nITB3")).toBe("0912345678"); // SMS phone

  // One enrollment with the Woman's Profile event: LNMP, EDD, GA, GA source.
  expect(te.enrollments).toHaveLength(1);
  const enrollment = te.enrollments[0];
  const profile = enrollment.events.find((e) => e.programStage === PROFILE_STAGE);
  expect(profile).toBeTruthy();
  expect(dataValue(profile, "w4ky6EkVahL")).toBe("2025-10-20"); // LNMP
  expect(dataValue(profile, "Ru01omP2WCQ")).toBe("2026-07-27"); // EDD
  expect(dataValue(profile, "w9p8MQDRyMr")).toBe("12"); // GA (weeks)
  expect(dataValue(profile, "RPSgZF1i0hk")).toBe("LMP"); // GA source

  // One ANC Examination event per follow-up, carrying the visit number.
  const examEvents = enrollment.events.filter((e) => e.programStage === EXAM_STAGE);
  expect(examEvents.map((e) => dataValue(e, VISIT_NUMBER_DE)).sort()).toEqual(["1", "2", "3"]);
});

test("re-pushing the same PulseTech record updates instead of duplicating", async ({
  request,
}) => {
  const first = await pushRecord(request, "pulsetech", PULSETECH_KEY, PULSETECH.record);
  expect(first.status()).toBe(200);
  const afterFirst = (await findByMrn(request, PULSETECH.mrn))[0];
  const eventsAfterFirst = afterFirst.enrollments[0].events.length;

  const second = await pushRecord(request, "pulsetech", PULSETECH_KEY, PULSETECH.record);
  expect(second.status()).toBe(200);

  // Still exactly one woman for the MRN, same tracked entity, same enrollment,
  // unchanged event count — the lookup matched and everything became an update.
  const instances = await findByMrn(request, PULSETECH.mrn);
  expect(instances).toHaveLength(1);
  expect(instances[0].trackedEntity).toBe(afterFirst.trackedEntity);
  expect(instances[0].enrollments).toHaveLength(1);
  expect(instances[0].enrollments[0].events).toHaveLength(eventsAfterFirst);
});

test("a new follow-up visit in a re-push appends exactly one event", async ({ request }) => {
  const extended = JSON.parse(JSON.stringify(PULSETECH.record));
  extended.anc_followup.push({
    followup_date: "2026-05-10",
    lmp_date: "2025-10-20",
    edd: "2026-07-27",
    ga: "24",
    visit_number: "Visit 4",
  });

  const response = await pushRecord(request, "pulsetech", PULSETECH_KEY, extended);
  expect(response.status()).toBe(200);

  const instances = await findByMrn(request, PULSETECH.mrn);
  expect(instances).toHaveLength(1);
  const examEvents = instances[0].enrollments[0].events.filter(
    (e) => e.programStage === EXAM_STAGE
  );
  // Visit 4 exists exactly once, alongside the original three — never duplicated,
  // even if this suite has run against this stack before.
  const visitNumbers = examEvents.map((e) => dataValue(e, VISIT_NUMBER_DE)).sort();
  expect(visitNumbers).toEqual(["1", "2", "3", "4"]);
});

test("Bahmni record lands: nameless woman with her phone, exam events, SCHEDULE placeholder", async ({
  request,
}) => {
  test.skip(!BAHMNI_KEY, "Bahmni source disabled (MEDIATOR_API_KEY_BAHMNI not set)");
  const response = await pushRecord(request, "bahmni", BAHMNI_KEY, BAHMNI.record);
  expect(response.status()).toBe(200);
  expect((await response.json()).status).toBe("OK");

  const instances = await findByMrn(request, BAHMNI.mrn);
  expect(instances).toHaveLength(1);
  const te = instances[0];
  expect(attributeValue(te, MRN_ATTRIBUTE)).toBe("668466");
  // Policy end to end: Bahmni sends NO NAMES (the dump ships the name attributes
  // non-mandatory), but the phone is required — the SMS programme runs off it.
  expect(attributeValue(te, "RJxLa3nITB3")).toBe("0911556677");
  expect(attributeValue(te, "sB1IHYu2xQT")).toBeUndefined();
  expect(te.enrollments).toHaveLength(1);
  const events = te.enrollments[0].events;

  // carrying its visit number.
  const exams = events.filter((e) => e.programStage === EXAM_STAGE && e.status !== "SCHEDULE");
  expect(exams.map((e) => dataValue(e, VISIT_NUMBER_DE)).sort()).toEqual(["1"]);
  for (const exam of exams) expect(exam.status).toBe("COMPLETED");

  // The nextDate became ONE SCHEDULE placeholder. It carries NO data values —
  // DHIS2 forbids them on SCHEDULE events (E1315) — so no visit number; when the
  // real visit arrives, the merge claims the stage's open placeholder instead.
  const scheduled = events.filter((e) => e.status === "SCHEDULE");
  expect(scheduled).toHaveLength(1);
  expect(scheduled[0].scheduledAt).toContain("2026-09-02");
  expect(scheduled[0].dataValues ?? []).toHaveLength(0);
});

test("the Bahmni export (one object per woman) lands every woman once; a re-push duplicates nothing", async ({
  request,
}) => {
  test.skip(!BAHMNI_KEY, "Bahmni source disabled (MEDIATOR_API_KEY_BAHMNI not set)");
  const batch = readSample("bahmni/anc-records-batch.json"); // a bare array of person objects
  // 5 women, one object each, in export order.
  const mrns = batch.map((p) => String(p.patientId));
  expect(mrns).toEqual(["668462", "668465", "668464", "668463", "668466"]);

  const first = await request.post(`${MEDIATOR}/api/bahmni/anc-records`, {
    headers: { "X-API-KEY": BAHMNI_KEY, "Content-Type": "application/json" },
    data: batch,
  });
  expect(first.status()).toBe(200);
  const firstBody = await first.json();
  expect(firstBody.summary.received).toBe(mrns.length);
  expect(firstBody.summary.imported).toBe(mrns.length);
  expect(firstBody.results.map((r) => r.mrn)).toEqual(mrns);

  // Every woman exists exactly once, with her events.
  const countsAfterFirst = {};
  for (const mrn of mrns) {
    const instances = await findByMrn(request, mrn);
    expect(instances, `one woman for ${mrn}`).toHaveLength(1);
    countsAfterFirst[mrn] = instances[0].enrollments[0].events.length;
  }

  // Re-push the identical batch: per-record idempotency makes it a pure update.
  const second = await request.post(`${MEDIATOR}/api/bahmni/anc-records`, {
    headers: { "X-API-KEY": BAHMNI_KEY, "Content-Type": "application/json" },
    data: batch,
  });
  expect(second.status()).toBe(200);
  for (const mrn of mrns) {
    const instances = await findByMrn(request, mrn);
    expect(instances).toHaveLength(1);
    expect(instances[0].enrollments[0].events).toHaveLength(countsAfterFirst[mrn]);
  }
});

test("the appointment lifecycle: scheduled -> attended (same event) -> rescheduled (same event)", async ({
  request,
}) => {
  test.skip(!BAHMNI_KEY, "Bahmni source disabled (MEDIATOR_API_KEY_BAHMNI not set)");
  // A per-run woman (reserved 9900* prefix, wiped by test:e2e:wipe) so every
  // run starts from a clean create and the assertions are deterministic.
  const patientId = Number(`9900${String(Date.now()).slice(-5)}`);
  const base = {
    patientId,
    birthDate: "1995-05-05",
    phoneNumber: "0911000111",
    facilityCode: "1057888",
    facilityName: "Felege Meles Health center",
  };

  // Week 1: visits 1+2 captured, next appointment on 7 Sep. The registration
  // data (LNMP/EDD/GA as a set) rides on the visit-1 encounter.
  const weekOne = {
    ...base,
    encounters: [
      { encounterId: 1, encounterDate: "2026-08-10", dateOfFirstVisit: "2026-08-10",
        visitNumber: 1, gestationalAge: "8", nextDate: null, LNMP: "2026-06-15", EDD: "2027-03-22" },
      { encounterId: 2, encounterDate: "2026-08-24", dateOfFirstVisit: "2026-08-10",
        visitNumber: 2, gestationalAge: "10", nextDate: "2026-09-07", LNMP: null, EDD: null },
    ],
  };
  expect((await pushRecord(request, "bahmni", BAHMNI_KEY, weekOne)).status()).toBe(200);

  let events = (await findByMrn(request, String(patientId)))[0].enrollments[0].events;
  expect(events).toHaveLength(4); // profile + 2 completed visits + the placeholder
  let scheduled = events.filter((e) => e.status === "SCHEDULE");
  expect(scheduled).toHaveLength(1);
  const placeholderUid = scheduled[0].event;

  // Week 2: she attends — visit 3 arrives as a follow-up-only record with a
  // new next appointment. The placeholder must BECOME visit 3 (same event),
  // not sit open next to a fourth exam event.
  const weekTwo = {
    ...base,
    encounters: [
      { encounterId: 3, encounterDate: "2026-09-07", dateOfFirstVisit: "2026-08-10",
        visitNumber: 3, gestationalAge: "12", nextDate: "2026-10-05", LNMP: null, EDD: null },
    ],
  };
  expect((await pushRecord(request, "bahmni", BAHMNI_KEY, weekTwo)).status()).toBe(200);

  events = (await findByMrn(request, String(patientId)))[0].enrollments[0].events;
  expect(events).toHaveLength(5); // exactly ONE new event: the rolled-forward placeholder
  const visitThree = events.find((e) => dataValue(e, VISIT_NUMBER_DE) === "3");
  expect(visitThree.event).toBe(placeholderUid); // the appointment became the visit
  expect(visitThree.status).toBe("COMPLETED");
  scheduled = events.filter((e) => e.status === "SCHEDULE");
  expect(scheduled).toHaveLength(1);
  expect(scheduled[0].scheduledAt).toContain("2026-10-05");
  const newPlaceholderUid = scheduled[0].event;

  // The clinic moves the appointment: same visits, only nextDate changes.
  // The open placeholder must be UPDATED in place, never duplicated.
  const rescheduled = { ...weekTwo, encounters: [{ ...weekTwo.encounters[0], nextDate: "2026-10-19" }] };
  expect((await pushRecord(request, "bahmni", BAHMNI_KEY, rescheduled)).status()).toBe(200);

  events = (await findByMrn(request, String(patientId)))[0].enrollments[0].events;
  expect(events).toHaveLength(5); // nothing added
  scheduled = events.filter((e) => e.status === "SCHEDULE");
  expect(scheduled).toHaveLength(1);
  expect(scheduled[0].event).toBe(newPlaceholderUid); // same event, new date
  expect(scheduled[0].scheduledAt).toContain("2026-10-19");
});

test("the mediator rejects a push without an API key", async ({ request }) => {
  const response = await request.post(`${MEDIATOR}/api/pulsetech/anc-record`, {
    headers: { "Content-Type": "application/json" },
    data: PULSETECH.record,
  });
  expect(response.status()).toBe(403);
});

test("one key per site: the PulseTech key cannot push to another source's endpoint", async ({
  request,
}) => {
  // 403 whether the Bahmni source is enabled (wrong role) or disabled (no rule at all).
  const response = await pushRecord(request, "bahmni", PULSETECH_KEY, BAHMNI.record);
  expect(response.status()).toBe(403);
});

test("a structurally invalid record gets a 400 listing the missing fields", async ({
  request,
}) => {
  const invalid = JSON.parse(JSON.stringify(PULSETECH.record));
  delete invalid.pnt_mrn;
  delete invalid.anc_followup[0].lmp_date;

  const response = await pushRecord(request, "pulsetech", PULSETECH_KEY, invalid);
  expect(response.status()).toBe(400);
  const error = await response.json();
  expect(error.missingFields).toContain("pnt_mrn");
  expect(error.missingFields).toContain("anc_followup[0].lmp_date");
  expect(error.patient).toBeUndefined(); // the id itself is missing here
});

test("the 400 names the patient when the record carries an MRN", async ({ request }) => {
  const invalid = JSON.parse(JSON.stringify(PULSETECH.record));
  delete invalid.anc_followup[0].lmp_date;

  const response = await pushRecord(request, "pulsetech", PULSETECH_KEY, invalid);
  expect(response.status()).toBe(400);
  const error = await response.json();
  expect(error.patient).toBe("719685");
  expect(error.error).toContain("719685");
});

test("an unknown facility org-unit code is rejected by DHIS2 and surfaces as a 409", async ({
  request,
}) => {
  // The mediator never resolves org units: the code flows through and DHIS2, importing
  // with orgUnitIdScheme=CODE, rejects a code that resolves to nothing. A fresh MRN is
  // used so the record is a pure create attempt.
  const wrongFacility = JSON.parse(JSON.stringify(PULSETECH.record));
  wrongFacility.pnt_mrn = "999404";
  wrongFacility.facility_dhis2_org_unit_code = "0000000";

  const response = await pushRecord(request, "pulsetech", PULSETECH_KEY, wrongFacility);
  expect(response.status()).toBe(409);
  expect((await response.json()).status).toBe("ERROR");
});
