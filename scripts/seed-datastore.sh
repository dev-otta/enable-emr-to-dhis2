#!/usr/bin/env bash
# Seed (or re-seed) the per-source DataSonnet expressions into ANY DHIS2
# instance's datastore — the deployment step for validation rules and
# mappings. Used against the Addis/staging instance where the demo compose
# stack (and its seed container) doesn't run.
#
#   DHIS2_URL=https://host/dhis DHIS2_USER=... DHIS2_PASSWORD=... \
#     ./scripts/seed-datastore.sh [source ...]
#
# With no arguments, every source found in config/datastore/ is seeded.
# Namespaces can be overridden with DATASTORE_VALIDATION_NAMESPACE /
# DATASTORE_MAPPING_NAMESPACE (must match the mediator's configuration).
set -euo pipefail

: "${DHIS2_URL:?Set DHIS2_URL (e.g. https://host/dhis — no trailing /api)}"
: "${DHIS2_USER:?Set DHIS2_USER (an account allowed to write the datastore)}"
: "${DHIS2_PASSWORD:?Set DHIS2_PASSWORD}"
ENVELOPE_NS="${DATASTORE_ENVELOPE_NAMESPACE:-enable-emr-envelope}"
VALIDATION_NS="${DATASTORE_VALIDATION_NAMESPACE:-enable-emr-validation}"
MAPPING_NS="${DATASTORE_MAPPING_NAMESPACE:-enable-emr-mapping}"

cd "$(dirname "$0")/.."

if [ "$#" -gt 0 ]; then
  sources=("$@")
else
  sources=()
  for f in config/datastore/*-mapping.json; do
    sources+=("$(basename "$f" -mapping.json)")
  done
fi

put_key() { # $1 = namespace/key, $2 = file
  echo "Writing ${1} ..."
  if ! curl -X PUT -sf -u "${DHIS2_USER}:${DHIS2_PASSWORD}" \
        -H "Content-Type: application/json" -d @"${2}" \
        "${DHIS2_URL}/api/dataStore/${1}" > /dev/null; then
    curl -X POST -sf -u "${DHIS2_USER}:${DHIS2_PASSWORD}" \
        -H "Content-Type: application/json" -d @"${2}" \
        "${DHIS2_URL}/api/dataStore/${1}" > /dev/null
  fi
}

for source in "${sources[@]}"; do
  put_key "${ENVELOPE_NS}/${source}"   "config/datastore/${source}-envelope.json"
  put_key "${VALIDATION_NS}/${source}" "config/datastore/${source}-validation.json"
  put_key "${MAPPING_NS}/${source}"    "config/datastore/${source}-mapping.json"
done

echo "Seeded: ${sources[*]}"
