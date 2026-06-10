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
| `ehr.security.dev-jwt-secret` | dev-only value via `EHR_DEV_JWT_SECRET` | HS256, ≥32 bytes enforced at startup |
| `ehr.export.storage-dir` | `${java.io.tmpdir}/ehr-exports` | NDJSON export output |
| `ehr.rate-limit.requests-per-minute` | 1000 | per client IP, `/api` + `/fhir` |
| `EHR_DB_URL` / `EHR_DB_USERNAME` / `EHR_DB_PASSWORD` | local compose values | |

## Authentication in development

There is no authorization server yet (`/oauth/*` are declared stubs). Requests
are authenticated with locally signed HS256 JWTs carrying the claims in
`JwtClaimNames` (subject = `users.external_subject`, organization id, space-
separated SMART scopes). The test suite's `DevJwtFactory` is the reference
implementation for minting them; the subject must exist as an active user with
an active membership in the target organization — identity is always resolved
from the database, never trusted from the token.

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
