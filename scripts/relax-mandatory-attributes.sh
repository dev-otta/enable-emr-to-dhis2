#!/usr/bin/env bash
# Relax the ANC program's mandatory flags on First Name and Father's Name
# (keeping Client MRN, Date of birth AND Phone Number mandatory).
#
# WHY: Bahmni's real export carries no name fields, and mandatory-ness is
# PROGRAM METADATA, not a mediator setting. The phone number deliberately STAYS
# mandatory: the SMS programme is the point of this integration, so a phoneless
# record is a vendor error — the mediator's validation rules reject it with a
# readable 400 before it ever reaches DHIS2.
#
# This is a metadata change affecting every channel (including Capture). The
# dump in db-dump/ is expected to already carry these flags relaxed
# (db-dump/README.md) — run this script only against an instance whose ANC
# program was NOT loaded from that dump, deliberately, with the metadata
# owner's sign-off:
#
#   DHIS2_URL=https://host/dhis DHIS2_USER=... DHIS2_PASSWORD=... \
#     ./scripts/relax-mandatory-attributes.sh
set -euo pipefail

: "${DHIS2_URL:?Set DHIS2_URL (e.g. https://host/dhis — no trailing /api)}"
: "${DHIS2_USER:?Set DHIS2_USER (an account allowed to edit program metadata)}"
: "${DHIS2_PASSWORD:?Set DHIS2_PASSWORD}"

# programTrackedEntityAttribute link UIDs in the ANC program (WSGAb5XwJ3Y),
# resolved from the ENABLE dump (db-dump/db-dump.sql.gz, program_attributes):
#   CPqaCwQRcKF  First Name        (sB1IHYu2xQT)
#   TK8w3cO2T8s  Father's Name     (ZtQqOYot5ut)
for link in CPqaCwQRcKF TK8w3cO2T8s; do
  echo "Relaxing mandatory on programTrackedEntityAttribute ${link} ..."
  curl -X PATCH -sf -u "${DHIS2_USER}:${DHIS2_PASSWORD}" \
    -H "Content-Type: application/json-patch+json" \
    -d '[{"op":"replace","path":"/mandatory","value":false}]' \
    "${DHIS2_URL}/api/programTrackedEntityAttributes/${link}" > /dev/null
done

echo "Done. MRN, Date of birth and Phone Number remain mandatory."
