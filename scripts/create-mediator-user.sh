#!/usr/bin/env bash
# Create (or update) the mediator's least-privilege DHIS2 account from the
# templates in config/dhis2/, and give it access to the ANC programme:
#
#   1. checks that the org units in the user template exist on the instance
#   2. creates the role (updates it if it already exists)
#   3. creates the user with MEDIATOR_DHIS2_PASSWORD (leaves an existing user as is)
#   4. shares the ANC programme, its two stages and the Person tracked entity
#      type with the user: metadata read, data read + write (r-rw----).
#      Uses /api/sharing, not a metadata PATCH: a PATCH re-validates the whole
#      programme and fails on instances with data (E6000). Existing sharing on
#      those objects is kept; the user is added to it.
#   5. logs in as the new user to confirm the account works
#
#   DHIS2_URL=https://host/dhis DHIS2_USER=admin DHIS2_PASSWORD=... \
#   MEDIATOR_DHIS2_PASSWORD=... \
#     ./scripts/create-mediator-user.sh
#
# DHIS2_USER must be a superuser (or allowed to manage users, roles and
# programme sharing). Use the same MEDIATOR_DHIS2_PASSWORD as in the
# mediator's .env. Afterwards, seed and lock the datastore with
# DATASTORE_READER_USERNAME set to the new username (scripts/seed-datastore.sh).
set -euo pipefail

: "${DHIS2_URL:?Set DHIS2_URL (e.g. https://host/dhis — no trailing /api)}"
: "${DHIS2_USER:?Set DHIS2_USER (a superuser)}"
: "${DHIS2_PASSWORD:?Set DHIS2_PASSWORD}"
: "${MEDIATOR_DHIS2_PASSWORD:?Set MEDIATOR_DHIS2_PASSWORD (the password the mediator will use)}"

cd "$(dirname "$0")/.."
auth="${DHIS2_USER}:${DHIS2_PASSWORD}"
api="${DHIS2_URL}/api"
ROLE_FILE=config/dhis2/mediator-role.json
USER_FILE=config/dhis2/mediator-user.json

# The objects the mediator imports into (see docs/REFERENCE.md §2), as
# <sharing type>:<uid>.
SHARED_OBJECTS=(
  program:WSGAb5XwJ3Y             # ANC - RMNCAH - Antenatal care registry
  programStage:iF5roNU7QWm        # Woman's Profile and History
  programStage:JqW7c9HYjVr        # ANC Examination
  trackedEntityType:MCPQUTHX1Ze   # Person
)

if [[ "$MEDIATOR_DHIS2_PASSWORD" == *[\"\\]* ]]; then
  echo "MEDIATOR_DHIS2_PASSWORD must not contain \" or \\." >&2
  exit 1
fi

# json_field <file> <field>: first string value of a top-level field (no jq dependency)
json_field() { grep -oE "\"$2\" *: *\"[^\"]*\"" "$1" | head -n 1 | sed -E 's/.*"([^"]*)"$/\1/'; }

role_id=$(json_field "$ROLE_FILE" id)
user_id=$(json_field "$USER_FILE" id)
username=$(json_field "$USER_FILE" username)

# call <method> <path> [body] [content-type]: fails with DHIS2's response on HTTP >= 400
call() {
  local out code
  out=$(mktemp)
  code=$(curl -s -o "$out" -w '%{http_code}' -u "$auth" -X "$1" \
    -H "Content-Type: ${4:-application/json}" ${3:+--data-binary "$3"} "${api}/$2")
  if [ "$code" -ge 400 ]; then
    echo "  FAILED  $1 $2 (HTTP $code)" >&2
    cat "$out" >&2; echo >&2
    rm -f "$out"
    exit 1
  fi
  rm -f "$out"
}
exists() { curl -sf -o /dev/null -u "$auth" "${api}/$1"; }

echo "1. Org units in ${USER_FILE}"
for ou in $(grep -oE '"id" *: *"[A-Za-z][A-Za-z0-9]{10}"' "$USER_FILE" | grep -oE '[A-Za-z][A-Za-z0-9]{10}"$' | tr -d '"' \
            | grep -vx "$user_id" | grep -vx "$role_id" | sort -u); do
  if exists "organisationUnits/${ou}"; then
    echo "  OK      ${ou}"
  else
    echo "  MISSING org unit ${ou}: edit ${USER_FILE} to match this instance." >&2
    exit 1
  fi
done

echo "2. Role ${role_id}"
if exists "userRoles/${role_id}"; then
  call PUT "userRoles/${role_id}" "@${ROLE_FILE}"
  echo "  updated"
else
  call POST userRoles "@${ROLE_FILE}"
  echo "  created"
fi

echo "3. User ${username} (${user_id})"
if exists "users/${user_id}"; then
  echo "  exists, left unchanged (password not reset)"
else
  user_json=$(cat "$USER_FILE")
  user_json="${user_json//REPLACE_WITH_STRONG_PASSWORD_FROM_ENV/$MEDIATOR_DHIS2_PASSWORD}"
  call POST users "$user_json"
  echo "  created"
fi

echo "4. Sharing (r-rw----) for ${username}"
entry="{\"id\":\"${user_id}\",\"access\":\"r-rw----\"}"
for object in "${SHARED_OBJECTS[@]}"; do
  type="${object%%:*}"; uid="${object#*:}"
  sharing=$(curl -sf -u "$auth" "${api}/sharing?type=${type}&id=${uid}") || {
    echo "  FAILED  could not read sharing of ${type} ${uid}" >&2; exit 1; }
  if grep -qE "\"userAccesses\" *: *\[[^]]*\"id\" *: *\"${user_id}\"" <<< "$sharing"; then
    echo "  OK      ${type} ${uid} (already shared)"
    continue
  fi
  # Add the user to the existing userAccesses list; everything else is posted back unchanged.
  if grep -qE '"userAccesses" *: *\[ *\]' <<< "$sharing"; then
    sharing=$(sed -E "s/\"userAccesses\" *: *\[ *\]/\"userAccesses\":[${entry}]/" <<< "$sharing")
  else
    sharing=$(sed -E "s/\"userAccesses\" *: *\[/\"userAccesses\":[${entry},/" <<< "$sharing")
  fi
  call POST "sharing?type=${type}&id=${uid}" "$sharing"
  echo "  OK      ${type} ${uid}"
done

echo "5. Login check"
if curl -sf -o /dev/null -u "${username}:${MEDIATOR_DHIS2_PASSWORD}" "${api}/me"; then
  echo "  OK      ${username} can log in"
else
  echo "  FAILED  ${username} cannot log in with MEDIATOR_DHIS2_PASSWORD." >&2
  echo "          If the user already existed, its password was not changed by this script." >&2
  exit 1
fi

echo "Done. Next: seed and lock the datastore with DATASTORE_READER_USERNAME=${username}."
