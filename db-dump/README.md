# DHIS2 database dump

`db-dump.sql.gz` is the ENABLE ANC package export (DHIS2 2.41.5.x schema).
Every `*.sql.gz` file in this directory is loaded automatically by the `db`
container on the first start of a fresh database volume, so keep exactly one
dump file here. Run `yarn clean` to wipe the volume and force a reload.

The dump is the authoritative source for every identifier used in the
mappings: the programme, the tracked entity attributes, the data elements,
the stages and the org unit codes documented in `docs/REFERENCE.md`. It must
match the `DHIS2_IMAGE` version pinned in `.env`.

The dump also carries the metadata state the EMR contracts assume. First
Name and Father's Name are non-mandatory in the ANC programme, and the
"Regular ANC / Scheduled visit?" data element is non-compulsory on the
examination stage. Phone Number stays mandatory. For an instance that was not loaded from this dump,
you can apply the attribute changes with `scripts/relax-mandatory-attributes.sh`.

The dump ships its own user accounts. Set `SEED_DHIS2_USER` and
`SEED_DHIS2_PASSWORD` in `.env` accordingly.

## Refreshing the dump

To change metadata and bake it into the dump:

```sh
# 1. run the stack and make the change in the Maintenance app or via the API
yarn start

# 2. make sure no test data rides along: delete test women, then hard-purge
#    the soft-deleted rows (scripts/check-mrns-free.sh verifies the state)
curl -su admin:district -X POST "http://localhost:8080/api/maintenance?softDeletedRelationshipRemoval=true&softDeletedEventRemoval=true&softDeletedEnrollmentRemoval=true&softDeletedTrackedEntityRemoval=true"

# 3. dump straight out of the db container
docker compose exec -T db pg_dump -U dhis -d dhis --no-owner --no-privileges \
  | gzip > db-dump/db-dump.sql.gz

# 4. verify: boot from the new dump and check the change survived
yarn clean && yarn start
```
