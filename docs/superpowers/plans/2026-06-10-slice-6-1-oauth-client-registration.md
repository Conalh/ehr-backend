# Slice 6.1 OAuth Client Registration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose the existing `oauth_clients` model through an audited, org-admin-managed registration API: register, list, read, and revoke organization-scoped OAuth clients.

**Architecture:** The `oauth_clients` table, `OAuthClient` model, and status enum exist since Slice 1; this slice adds the tenant-scoped `OAuthClientRepository` (create / findById / findByOrganization / revoke with status guard) and the `/api/v1/oauth-clients` surface. Policy: new `OAUTH_CLIENT` resource managed by `ORG_ADMIN`/`SYSTEM_ADMIN`; client management is an admin function, so like `CHART` it requires wildcard-resource scopes (`user/*.read|write`, `system/*.read|write`) rather than a FHIR resource scope (`policy-spine-v13`). Audit records `CREATE`/`READ`/`SEARCH`/`UPDATE` on resource type `OAUTH_CLIENT` (no patient compartment, no provenance — provenance is for clinical writes). Client secrets are deferred until a real authorization server exists; registration records identity metadata only.

---

## File Structure

- Create `src/main/kotlin/dev/ehr/identity/OAuthClientRepository.kt`.
- Create `src/main/kotlin/dev/ehr/oauth/OAuthClientService.kt`, `OAuthClientController.kt`.
- Modify `PolicyModels.kt`/`PolicyEvaluator.kt`: `OAUTH_CLIENT` admin rules, `policy-spine-v13` + literal updates.
- Create `src/test/kotlin/dev/ehr/oauth/OAuthClientApiIntegrationTest.kt`.

## Acceptance Criteria

- `POST /api/v1/oauth-clients` (`clientIdentifier`, `displayName`): `201` org-scoped client (status `active`); `409` duplicate identifier; `400` blanks.
- `GET /api/v1/oauth-clients` lists the caller's organization's clients; `GET /api/v1/oauth-clients/{id}` reads one (`404` cross-tenant/missing); `POST /api/v1/oauth-clients/{id}/revoke` sets status `revoked` (`422` if already revoked).
- Policy: `OAUTH_CLIENT` READ/WRITE for `ORG_ADMIN`/`SYSTEM_ADMIN` with wildcard scopes; clinicians/staff denied with audited `INSUFFICIENT_ROLE`; `policy-spine-v13`.
- Audit parity on resource type `OAUTH_CLIENT`; unauthenticated `401` unaudited.
- All prior suites green apart from policy-version literals.

## Intentional Deferrals

- No client secrets, JWKS, or authentication of clients (needs an authorization server); no system-app principal/token path; no global (org-null) client management; no scope allow-lists per client.

## Tasks

- [ ] Failing API tests (admin CRUD+revoke matrix, role/scope denials, tenancy, duplicate identifier).
- [ ] Implement repository, policy bump + literals, service, controller.
- [ ] Focused + full suites; commit plan as `docs: add Slice 6.1 OAuth client registration plan`; implementation as `feat: add OAuth client registration API`.

## Self-Review Checklist

- Clients are tenant-scoped and fail closed; the unique identifier is global per the existing schema.
- Admin-only policy is enforced and audited; no clinical-data access is implied by client management.
- Secret handling is deferred, not faked.
