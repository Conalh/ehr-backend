# Threat Model

Scope: the ehr-core backend as built through the US Core alignment arc
(UC1–UC5) and the authorization-server/hardening arcs (AS1–AS4, H1–H5) —
synthetic data only, no real PHI, no production deployment. The model still
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
  same-org FKs make cross-tenant references unrepresentable) plus Postgres
  row-level security (`FORCE ROW LEVEL SECURITY`, V18) as a defense-in-depth
  second layer; a restricted application role is required for RLS to bind.
- Filesystem — export storage directory.
- Background execution — the async export worker acts with a recorded
  requester, never an ambient identity.

## Principal threats and mitigations

| Threat | Mitigation | Residual risk / deferral |
| --- | --- | --- |
| Cross-tenant data access | `TenantScope` on every repository read; composite same-org FKs (including `care_team_memberships.user_id` → `memberships`); fail-closed 404s; RLS on; isolation tests per resource | A raw-SQL bug that skipped `TenantScope` would still hit RLS; superuser sessions bypass RLS — the application must connect as a non-superuser |
| Token forgery / stolen token | Embedded AS issues RS256/JWKS tokens; the dev HS256 decoder is off by default (`dev-jwt-enabled`, fail-closed to untrusted issuers); identity re-resolved from DB per request (roles never trusted from claims); membership/org status checks; RFC 7009 revocation | Dev decoder, when enabled, uses a shared HS256 secret with no rotation; in-memory authorization store (refresh-token reuse detection does not revoke the family across restarts); no signing-key rotation |
| Privilege escalation | Central `PolicyEvaluator` (org → role → SMART scope → launch context → treatment relationship), versioned `policy-spine-vN` decisions in audit; per-organization compartment enforcement (`off`/`shadow`/`enforced`); break-glass reads only with mandatory reason | Encounter-derived relationships auto-expire; `shadow` mode records but does not deny by default |
| Audit/provenance tampering | Append-only DB triggers; writes transactional with the clinical mutation | DB superuser can drop triggers; no off-host audit shipping |
| Data exfiltration via export | Clinician-only + wildcard scopes; the async export worker binds tenant context for RLS; tenant-scoped download; every request/file/download audited | Files unencrypted at rest in the OS temp dir; no retention/cleanup; single-node storage; unbounded in-memory fan-out per job |
| Payload leakage in logs/errors | Single logger in main code (job id + exception class); Boot error bodies exclude messages by default; audit rows carry IDs, never demographics or note content | Convention-enforced; no automated log-content scanner |
| DoS / brute force | Per-IP fixed-window rate limiting on `/api` + `/fhir` (429 + Retry-After); stateless auth | In-memory single-node limiter; no per-principal limits; health/discovery unthrottled by design |
| Misconfiguration | `@Validated` typed properties fail startup; deny-by-default routing | — |
| Injection | Parameterized JDBC everywhere; enums constrained in schema and code | — |
| CSRF / clickjacking / sniffing | Stateless bearer-token API, CSRF disabled deliberately; CSP `default-src 'none'`; `X-Frame-Options: DENY`; `nosniff`; `Referrer-Policy: no-referrer` | — |

## Standing constraints

No real PHI. No claims of HIPAA compliance or ONC certification. The embedded
authorization server runs in a deliberate dev posture (shared login password,
generated signing keys, in-memory authorization store); it must be hardened
(key rotation, persistent authorization store, real user credentials) before
any non-synthetic use is even discussed. The dev JWT path remains test-only.

## Implemented controls (previously deferred)

1. Care-team / assignment-based compartment authorization (H1–H4; per-org
   `off`/`shadow`/`enforced`, encounter-derived memberships with auto-expiry).
2. Postgres RLS as defense-in-depth under the application tenancy (V18,
   `FORCE ROW LEVEL SECURITY`).
3. Embedded authorization server: authorization code + PKCE, standalone
   patient launch, OIDC `fhirUser`, client-credentials backend services,
   RFC 7009 revocation, Argon2id client-secret hashing (AS1–AS4).

## Top deferred hardening items

1. Authorization server production posture: persistent authorization store
   (refresh-token reuse revokes the family), signing-key rotation, real user
   credentials, `private_key_jwt`.
2. Export encryption-at-rest, retention/cleanup, signed URLs, and moving
   storage out of the OS temp dir.
3. Off-host audit log shipping.
4. FHIR search `_count`/cursor pagination and indexed search tables (see
   `docs/architecture/architecture-spine.md`).
