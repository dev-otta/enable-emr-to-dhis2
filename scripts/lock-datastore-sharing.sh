#!/usr/bin/env bash
# Lock down sharing on the mediator's datastore entries (envelope, validation and
# mapping per source). By default DHIS2 creates datastore entries with public
# read/write sharing, which lets ANY logged-in user rewrite a mapping and change
# what the mediator imports. After this script:
#
#   public access          none                 (--------)
#   mediator service user  read only            (r-------)
#   editor user group      read + write         (rw------, optional)
#
# Superusers (ALL authority) bypass sharing and can always edit the entries.
#
#   DHIS2_URL=https://host/dhis DHIS2_USER=... DHIS2_PASSWORD=... \
#   DATASTORE_READER_USERNAME=enable_mediator \
#   DATASTORE_EDITOR_GROUP=<user group UID or exact name> \
#     ./scripts/lock-datastore-sharing.sh [source ...]
#
#   CHECK_ONLY=1 ... ./scripts/lock-datastore-sharing.sh   # report, change nothing;
#                                                         # exits 1 if any entry is public
#
# DHIS2_USER must be allowed to manage sharing on the entries (a superuser, or
# the entry's owner). With no arguments, every source in config/datastore/ is
# handled. Namespaces follow the same DATASTORE_*_NAMESPACE overrides as the
# mediator and scripts/seed-datastore.sh. Re-run after seeding a NEW key: a key
# created by POST starts with public read/write sharing again. Updating an
# existing key with PUT keeps its sharing.
set -euo pipefail

: "${DHIS2_URL:?Set DHIS2_URL (e.g. https://host/dhis — no trailing /api)}"
: "${DHIS2_USER:?Set DHIS2_USER (an account allowed to manage sharing on the entries)}"
: "${DHIS2_PASSWORD:?Set DHIS2_PASSWORD}"
CHECK_ONLY="${CHECK_ONLY:-0}"
if [ "$CHECK_ONLY" != "1" ]; then
  : "${DATASTORE_READER_USERNAME:?Set DATASTORE_READER_USERNAME (the DHIS2 username of the mediator)}"
fi
EDITOR_GROUP="${DATASTORE_EDITOR_GROUP:-}"
ENVELOPE_NS="${DATASTORE_ENVELOPE_NAMESPACE:-enable-emr-envelope}"
VALIDATION_NS="${DATASTORE_VALIDATION_NAMESPACE:-enable-emr-validation}"
MAPPING_NS="${DATASTORE_MAPPING_NAMESPACE:-enable-emr-mapping}"

cd "$(dirname "$0")/.."
auth="${DHIS2_USER}:${DHIS2_PASSWORD}"
api="${DHIS2_URL}/api"

if [ "$#" -gt 0 ]; then
  sources=("$@")
else
  sources=()
  for f in config/datastore/*-mapping.json; do
    sources+=("$(basename "$f" -mapping.json)")
  done
fi

# First 11-character DHIS2 id in a JSON response (no jq dependency).
first_id() { grep -oE '"id" *: *"[A-Za-z][A-Za-z0-9]{10}"' | head -n 1 | grep -oE '[A-Za-z][A-Za-z0-9]{10}"$' | tr -d '"'; }
public_access() { grep -oE '"publicAccess" *: *"[^"]*"' | head -n 1 | sed -E 's/.*"([^"]*)"$/\1/'; }

entry_uid() { # namespace/key -> the entry's UID (empty when the key does not exist)
  curl -sf -u "$auth" "${api}/dataStore/$1/metaData" | first_id || true
}

failures=0

if [ "$CHECK_ONLY" != "1" ]; then
  reader_id=$(curl -sf -u "$auth" \
    "${api}/users?filter=username:eq:${DATASTORE_READER_USERNAME}&fields=id&paging=false" | first_id || true)
  if [ -z "$reader_id" ]; then
    echo "User '${DATASTORE_READER_USERNAME}' not found on ${DHIS2_URL}. Create the mediator user first." >&2
    exit 1
  fi

  group_access=""
  if [ -n "$EDITOR_GROUP" ]; then
    if [[ "$EDITOR_GROUP" =~ ^[A-Za-z][A-Za-z0-9]{10}$ ]]; then
      group_id="$EDITOR_GROUP"
    else
      group_id=$(curl -sf -u "$auth" -G "${api}/userGroups" \
        --data-urlencode "filter=name:eq:${EDITOR_GROUP}" \
        --data-urlencode "fields=id" --data-urlencode "paging=false" | first_id || true)
    fi
    if [ -z "$group_id" ]; then
      echo "User group '${EDITOR_GROUP}' not found on ${DHIS2_URL}." >&2
      exit 1
    fi
    group_access="{\"id\":\"${group_id}\",\"access\":\"rw------\"}"
  fi
  echo "Mediator user ${DATASTORE_READER_USERNAME} (${reader_id}): read only"
  [ -n "$EDITOR_GROUP" ] && echo "Editor group ${EDITOR_GROUP} (${group_id}): read + write"
fi

for source in "${sources[@]}"; do
  for ns in "$ENVELOPE_NS" "$VALIDATION_NS" "$MAPPING_NS"; do
    key="${ns}/${source}"
    uid=$(entry_uid "$key")
    if [ -z "$uid" ]; then
      echo "  MISSING ${key} (seed it first: scripts/seed-datastore.sh)"
      failures=$((failures + 1))
      continue
    fi

    if [ "$CHECK_ONLY" != "1" ]; then
      body="{\"object\":{\"id\":\"${uid}\",\"publicAccess\":\"--------\",\"externalAccess\":false,\
\"userAccesses\":[{\"id\":\"${reader_id}\",\"access\":\"r-------\"}],\
\"userGroupAccesses\":[${group_access}]}}"
      if ! curl -sf -u "$auth" -X POST -H "Content-Type: application/json" \
            -d "$body" "${api}/sharing?type=dataStore&id=${uid}" > /dev/null; then
        echo "  FAILED  ${key} (${uid}): could not update sharing"
        failures=$((failures + 1))
        continue
      fi
    fi

    public=$(curl -sf -u "$auth" "${api}/sharing?type=dataStore&id=${uid}" | public_access || true)
    if [ "$public" = "--------" ]; then
      echo "  OK      ${key} (${uid}) public access: none"
    else
      echo "  OPEN    ${key} (${uid}) public access: ${public:-unknown}"
      failures=$((failures + 1))
    fi
  done
done

if [ "$failures" -gt 0 ]; then
  echo "FAILED: ${failures} entr(y/ies) missing, open or not updated."
  exit 1
fi
if [ "$CHECK_ONLY" = "1" ]; then
  echo "All mediator datastore entries are closed to public access."
else
  echo "Locked: ${sources[*]}"
fi
