# Backup, Restore, And Migration Policy

## Backup and restore (development posture)

All durable state lives in Postgres; export NDJSON files are derived artifacts
and can be regenerated.

```powershell
# Backup (compose database)
docker compose exec postgres pg_dump -U ehr_core -Fc ehr_core > ehr_core.dump

# Restore into a fresh database
docker compose exec -T postgres pg_restore -U ehr_core -d ehr_core --clean --if-exists < ehr_core.dump
```

Production posture (when one exists) requires: scheduled `pg_dump`/WAL
archiving, restore drills, and encrypted backup storage — backups contain the
clinical record and must be protected like the database itself. The audit,
provenance, and revision tables are append-only by trigger; a restore is the
only sanctioned way data leaves them.

## Migration policy

- **Forward-only.** Applied migrations (`V1`...`Vn`) are immutable history;
  never edit one after it has run anywhere. Fixes are new migrations.
- **Expand/contract for breaking changes.** Add the new shape, backfill,
  migrate readers/writers, then remove the old shape in a later migration once
  nothing depends on it.
- **Rollback = roll forward.** There are no down-migrations. Recovering from a
  bad migration means restoring from backup or shipping a corrective forward
  migration. Test migrations against a restored copy before applying anywhere
  that matters.
- **Append-only tables are sacred.** No migration may update or delete rows in
  `audit_events`, `audit_event_resources`, `provenance_events`, or
  `resource_revisions`; schema additions to them must preserve the triggers.
- Every migration runs inside Flyway's transaction; keep DDL transactional
  (avoid `CREATE INDEX CONCURRENTLY` until an online-migration lane exists).
