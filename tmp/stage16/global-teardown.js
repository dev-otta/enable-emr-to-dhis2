// Optional post-suite cleanup: deletes the tracked entities the e2e tests
// created (the PulseTech + Bahmni test women) and hard-purges the soft-deleted
// rows, leaving the instance's tracker as the suite found it.
//
// Off by default — the suite is designed to be re-runnable against its own
// leftovers, and inspecting the imported women in Capture after a run is
// useful. Enable with:  yarn test:e2e:wipe   (sets E2E_WIPE_AFTER=1)
//
// Runs after ALL tests, pass or fail (Playwright globalTeardown).

const DHIS2 =
  process.env.DHIS2_URL || `http://localhost:${process.env.DHIS2_PORT || 8080}`;
const AUTH =
  "Basic " +
  Buffer.from(
    `${process.env.SEED_DHIS2_USER || "admin"}:${process.env.SEED_DHIS2_PASSWORD || "district"}`
  ).toString("base64");

const PROGRAM = "WSGAb5XwJ3Y";
const MRN_ATTRIBUTE = "OYuDdqr2MvX";
// Every MRN the suite can create (the PulseTech sample + the real Bahmni export).
const TEST_MRNS = ["719685", "668462", "668463", "668464", "668465", "668466"];

module.exports = async function globalTeardown() {
  if (process.env.E2E_WIPE_AFTER !== "1") {
    return; // default: leave the imported women in place for inspection
  }
  console.log("\n[wipeAfter] removing the test women the suite created...");

  // The fixed sample MRNs, plus the per-run women the appointment-lifecycle
  // test creates under the reserved 9900* prefix (matched with :like:).
  const filters = [
    ...TEST_MRNS.map((mrn) => `${MRN_ATTRIBUTE}:eq:${mrn}`),
    `${MRN_ATTRIBUTE}:like:9900`,
  ];
  const uids = [];
  for (const filter of filters) {
    const response = await fetch(
      `${DHIS2}/api/tracker/trackedEntities?program=${PROGRAM}&ouMode=ACCESSIBLE` +
        `&filter=${filter}&fields=trackedEntity&paging=false`,
      { headers: { Authorization: AUTH } }
    );
    if (!response.ok) {
      console.warn(`[wipeAfter] lookup for ${filter} failed (${response.status}) — skipping`);
      continue;
    }
    const body = await response.json();
    for (const instance of body.instances || []) {
      if (!uids.includes(instance.trackedEntity)) {
        uids.push(instance.trackedEntity);
      }
    }
  }

  if (uids.length > 0) {
    const del = await fetch(`${DHIS2}/api/tracker?importStrategy=DELETE&async=false`, {
      method: "POST",
      headers: { Authorization: AUTH, "Content-Type": "application/json" },
      body: JSON.stringify({ trackedEntities: uids.map((u) => ({ trackedEntity: u })) }),
    });
    console.log(`[wipeAfter] tracker delete of ${uids.length} tracked entities: HTTP ${del.status}`);
  } else {
    console.log("[wipeAfter] nothing to delete");
  }

  // Hard-purge, so the unique MRN values are truly freed (a soft-deleted row
  // still blocks re-creating the same MRN — and would ride along into pg_dump).
  const purge = await fetch(
    `${DHIS2}/api/maintenance?softDeletedRelationshipRemoval=true` +
      `&softDeletedEventRemoval=true&softDeletedEnrollmentRemoval=true&softDeletedTrackedEntityRemoval=true`,
    { method: "POST", headers: { Authorization: AUTH } }
  );
  console.log(`[wipeAfter] maintenance purge: HTTP ${purge.status}`);
};
