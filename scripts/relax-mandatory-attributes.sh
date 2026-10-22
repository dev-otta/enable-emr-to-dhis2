#!/usr/bin/env bash
# Relax the ANC program metadata that the real EMR exports cannot satisfy:
#
#   mandatory TEAs:  First Name, Father's Name   (Bahmni sends no names)
#                    Age/Date of birth           (PulseTech sends age, not DOB —
#                                                 mapped to the optional Age (years))
#   compulsory DEs:  Source of gestational age   (Woman's Profile stage — sent
#                    as LMP only when the export carries an LNMP; records
#                    without one have no source, never a guessed one)
#
# Client MRN and SMS phone number deliberately STAY mandatory, and Gestational
# age STAYS compulsory: MRN is the identity key, the SMS programme is the point
# of this integration, and a contact that cannot be placed in the pregnancy is
# not worth importing — the mediator's validation rejects all three cases with
# a readable 400 before DHIS2 is ever involved.
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

patch() { # object-type link-uid json-pointer
  curl -X PATCH -sf -u "${DHIS2_USER}:${DHIS2_PASSWORD}" \
    -H "Content-Type: application/json-patch+json" \
    -d "[{\"op\":\"replace\",\"path\":\"$3\",\"value\":false}]" \
    "${DHIS2_URL}/api/$1/$2" > /dev/null
}

# programTrackedEntityAttribute link UIDs in the ANC program (WSGAb5XwJ3Y),
# resolved from the ENABLE dump (db-dump/db-dump.sql.gz, program_attributes):
#   CPqaCwQRcKF  First Name         (sB1IHYu2xQT)
#   TK8w3cO2T8s  Father's Name      (ZtQqOYot5ut)
#   Qz8xmualICa  Age/Date of birth  (Fgf497oYvSC)
for link in CPqaCwQRcKF TK8w3cO2T8s Qz8xmualICa; do
  echo "Relaxing mandatory on programTrackedEntityAttribute ${link} ..."
  patch programTrackedEntityAttributes "${link}" /mandatory
done

# programStageDataElement link UID on the Woman's Profile stage (iF5roNU7QWm),
# resolved from the ENABLE dump (programstagedataelement):
#   ZbtY0NyC9lV  Source of gestational age  (RPSgZF1i0hk)
for link in ZbtY0NyC9lV; do
  echo "Relaxing compulsory on programStageDataElement ${link} ..."
  patch programStageDataElements "${link}" /compulsory
done

echo "Done. MRN and SMS phone stay mandatory; Gestational age stays compulsory."
