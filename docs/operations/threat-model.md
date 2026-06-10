# Threat Model

Scope: the ehr-core backend as built through Slice 9 — synthetic data only, no
real PHI, dev-JWT authentication, no production deployment. The model still
treats the data as if it were PHI, because the controls are the product.

## Assets

1. The clinical record (patients, encounters, conditions, allergies,
   observations, medications, notes, orders, reports) and its history
   (revisions, provenance).
2. The audit trail (tamper-evidence for every access decision).
3. Identity/tenancy data (organizations, users, memberships, OAuth clients).
4. Export artifacts (NDJSON files hold whole-population data).
5. The dev JWT signing secret.

## Trust boundaries

- HTTP edge (`/api/v1`, `/fhir/r4`) — authenticated; `/actuator/metrics`
  authenticated; discovery/health/stubs/metadata deliberately public;
  everything else deny-by-default.
- Database — application-level tenancy (every read tenant-scoped, composite
  same-org FKs make cross-tenant references unrepresentable); Postgres RLS is a
  deferred second layer.
- Filesystem — export storage directory.
- Background execution — the async export worker acts with a recorded
  requester, never an ambient identity.

## Principal threats and mitigations

| Threat | Mitigation | Residual risk / deferral |
| --- | --- | --- |
| Cross-tenant data access | `TenantScope` on every repository read; composite same-org FKs; fail-closed 404s; isolation tests per resource | RLS not yet enabled; a raw-SQL bug could bypass scoping until then |
| Token forgery / stolen token | HS256 with ≥32-byte secret validated at startup; identity re-resolved from DB per request (roles never trusted from claims); membership/org status checks | Dev-grade shared secret; no rotation, no real AS, no token revocation |
| Privilege escalation | Central `PolicyEvaluator` (role × SMART scope × resource × operation), versioned decisions in audit; per-resource granularity; patient-context scopes fail closed | No care-team/assignment compartment yet — any clinician in the org reads any chart (documented) |
| Audit/provenance tampering | Append-only DB triggers; writes transactional with the clinical mutation | DB superuser can drop triggers; no off-host audit shipping |
| Data exfiltration via export | Clinician-only + wildcard scopes; tenant-scoped download; every request/file/download audited | Files unencrypted at rest; no retention/cleanup; single-node storage |
| Payload leakage in logs/errors | Single logger in main code (job id + exception class); Boot error bodies exclude messages by default; audit rows carry IDs, never demographics or note content | Convention-enforced; no automated log-content scanner |
| DoS / brute force | Per-IP fixed-window rate limiting on `/api` + `/fhir` (429 + Retry-After); stateless auth | In-memory single-node limiter; no per-principal limits; health/discovery unthrottled by design |
| Misconfiguration | `@Validated` typed properties fail startup; deny-by-default routing | — |
| Injection | Parameterized JDBC everywhere; enums constrained in schema and code | — |
| CSRF / clickjacking / sniffing | Stateless bearer-token API, CSRF disabled deliberately; CSP `default-src 'none'`; `X-Frame-Options: DENY`; `nosniff`; `Referrer-Policy: no-referrer` | — |

## Standing constraints

No real PHI. No claims of HIPAA compliance or ONC certification. The dev JWT
path must be replaced (not extended) by a real authorization server before any
non-synthetic use is even discussed.

## Top deferred hardening items

1. Care-team / assignment-based compartment authorization.
2. Postgres RLS as defense-in-depth under the application tenancy.
3. Real authorization server (client secrets, rotation, introspection) and
   system-app principals.
4. Export encryption-at-rest, retention, and signed URLs.
5. Off-host audit log shipping.
