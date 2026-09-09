#!/usr/bin/env bash
# Verify that a target DHIS2 instance carries every metadata UID the mappings
# reference — run this against the Addis/staging instance BEFORE seeding or
# pointing the mediator at it. The mappings were authored against the ENABLE
# dump; a target whose metadata differs will reject imports at runtime, and
# this catches that in seconds instead.
#
#   DHIS2_URL=https://host/dhis DHIS2_USER=... DHIS2_PASSWORD=... \
#     ./scripts/verify-metadata.sh [facility-code ...]
#
# The UID manifest is EXTRACTED from the mapping sources (config/datastore/
# *.ds) — nothing is duplicated here. Optional arguments are facility org-unit
# codes to check (the codes EMR sites will send, resolved via orgUnitIdScheme=CODE).
set -euo pipefail

: "${DHIS2_URL:?Set DHIS2_URL (e.g. https://host/dhis — no trailing /api)}"
: "${DHIS2_USER:?Set DHIS2_USER}"
: "${DHIS2_PASSWORD:?Set DHIS2_PASSWORD}"

cd "$(dirname "$0")/.."
auth="${DHIS2_USER}:${DHIS2_PASSWORD}"
failures=0

# Every metadata UID referenced by any mapping: quoted 11-char DHIS2 IDs.
# (The mappings keep them in named constant blocks — ATTRIBUTE/DATA_ELEMENT/
# STAGE/PROGRAM — so any quoted 11-char token is a UID; no other string
# literal in these files matches the pattern.)
uids=$(grep -hoE "'[A-Za-z][A-Za-z0-9]{10}'" config/datastore/*-mapping.ds \
       | tr -d "'" | sort -u)

echo "Checking $(echo "$uids" | wc -w | tr -d ' ') metadata UIDs against ${DHIS2_URL} ..."
for uid in $uids; do
  if curl -sf -u "$auth" "${DHIS2_URL}/api/identifiableObjects/${uid}" > /dev/null; then
    echo "  OK      ${uid}"
  else
    echo "  MISSING ${uid}"
    failures=$((failures + 1))
  fi
done

for code in "$@"; do
  count=$(curl -sf -u "$auth" \
    "${DHIS2_URL}/api/organisationUnits.json?filter=code:eq:${code}&fields=id&paging=false" \
    | grep -c '"id"' || true)
  if [ "$count" -ge 1 ]; then
    echo "  OK      org unit code ${code}"
  else
    echo "  MISSING org unit code ${code} (fix in DHIS2 metadata or the EMR site config)"
    failures=$((failures + 1))
  fi
done

if [ "$failures" -gt 0 ]; then
  echo "FAILED: ${failures} missing object(s). Do not point the mediator at this instance yet."
  exit 1
fi
echo "All metadata present."
