# Local Development Runbook

How a new developer runs the full stack locally. Synthetic data only — this
deployment must never hold real PHI.

## Prerequisites

- JDK 17+ (Gradle toolchain targets 17)
- Docker Desktop (Postgres via Docker Compose, Testcontainers for tests)

## Run the stack

```powershell
# 1. Start Postgres (port 54328, credentials in docker-compose.yml)
docker compose up -d

# 2. Run the service (Flyway migrates on boot)
.\gradlew.bat bootRun
```

The service listens on `http://localhost:8080` (`PORT` env overrides).

- Health: `GET /internal/health`, `GET /actuator/health` (public)
- Metrics: `GET /actuator/metrics` (authenticated)
- SMART discovery: `GET /.well-known/smart-configuration` (public)
- CapabilityStatement: `GET /fhir/r4/metadata` (public)

## Configuration

All security-critical settings live under the validated `ehr.*` prefix
(`EhrProperties`); the service refuses to start on invalid values.

| Property | Default | Notes |
| --- | --- | --- |
| `ehr.security.dev-jwt-enabled` | `false` (`EHR_DEV_JWT_ENABLED`) | gates the HS256 dev-JWT decoder; on only in the `test` profile |
| `ehr.security.dev-jwt-secret` | none committed (`EHR_DEV_JWT_SECRET`) | HS256, ≥32 bytes; required only when `dev-jwt-enabled=true` |
| `ehr.export.storage-dir` | `${java.io.tmpdir}/ehr-exports` | NDJSON export output |
| `ehr.rate-limit.requests-per-minute` | 1000 | per client IP, `/api` + `/fhir` |
| `ehr.compartment.encounter-derived-expiry-days` | 30 | encounter-derived care-team memberships auto-end this long after the sustaining encounter finishes |
| `EHR_DB_URL` / `EHR_DB_USERNAME` / `EHR_DB_PASSWORD` | local compose values | |

### Tenant row-level security

Every organization-scoped table carries `FORCE ROW LEVEL SECURITY` with a
tenant-isolation policy keyed on the `ehr.organization_id` GUC, which the
application sets on every borrowed connection (empty when no request context —
migrations, fixtures, and background workers bypass by design).

**Production requirement: the application must connect as a non-superuser
role.** Postgres superusers bypass RLS unconditionally — `FORCE` covers the
table owner, but nothing covers a superuser. The local compose and test
containers connect as the image superuser, so RLS is real defense-in-depth
only in a deployment with a restricted application role. If a session pooler
(pgBouncer in transaction mode) is introduced, session-level GUCs no longer
stick to a logical session — revisit the set-on-borrow convention first.

### Compartment enforcement

Compartment authorization (`docs/architecture/compartment-authorization.md`)
rolls out per organization via `organizations.compartment_enforcement`
(`off | shadow | enforced`, default `shadow`). There is no operator API yet —
flip it with SQL after reviewing the org's shadow audit data
(`audit_events.relationship_basis` null-rate on clinical reads):

```sql
update organizations set compartment_enforcement = 'enforced' where slug = '<org>';
```

In `enforced` mode, clinical reads without a treatment relationship can carry
an `X-Break-Glass-Reason` header (mandatory free text). The access is granted
and audited with `purpose_of_use = 'ETREAT'`, `relationship_basis =
'break-glass'`, and the reason in audit metadata — report on these regularly.

## Authentication in development

The embedded Spring Authorization Server is the primary authentication path:
`/oauth/authorize` (authorization code + PKCE, with the synthetic patient
launch picker; one selected patient per authorize transaction), `/oauth/token`,
`/oauth/revoke`, JWKS, and `/.well-known/smart-configuration`. The dev login
page accepts any active user with the shared `ehr.security.dev-login-password` (users carry no credentials —
synthetic data, no IdP); identity is always re-resolved from the database on
every request, so revoking a client kills its live tokens immediately.

The HS256 dev-JWT path is a test/dev convenience, **off by default**
(`ehr.security.dev-jwt-enabled=false`): the resource server validates only the
embedded AS's RS256 tokens and rejects every other issuer. It is flipped on by
the `test` profile (`src/test/resources/application-test.yml`) so the
`DevJwtFactory`-issued tokens authenticate in integration tests. The
`ehr.security.dev-jwt-secret` is not committed by default — set
`EHR_DEV_JWT_SECRET` (≥32 bytes) and `EHR_DEV_JWT_ENABLED=true` to use it
locally. As on the AS path, identity is resolved from the database, never
trusted from the token.

## Tests

```powershell
.\gradlew.bat test              # full suite (Testcontainers needs Docker)
.\gradlew.bat test --tests "dev.ehr.patient.*"   # focused
```

## Known local pitfalls

- Docker Desktop on Windows can fail to restart when orphaned AF_UNIX socket
  files survive an unclean shutdown (`%LOCALAPPDATA%\Docker\run\dockerInference`,
  `%LOCALAPPDATA%\docker-secrets-engine\engine.sock`). Rename the offending
  directory aside and relaunch; the husks become deletable after a reboot.
