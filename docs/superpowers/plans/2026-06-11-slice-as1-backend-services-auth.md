# Slice AS1 Backend Services Authorization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** First authorization-server slice per the accepted design
(`docs/architecture/authorization-server-integration.md`): an embedded Spring
Authorization Server issuing **client-credentials** tokens (RS256, local
JWKS), system-app principals resolved from `oauth_clients`, and `SYSTEM_APP`
authorized for EXPORT only. The 501 stubs at `/oauth/token` come alive;
`/oauth/authorize` stays a stub until AS2.

**Architecture:**

- **Clients:** `oauth_clients` (V19) gains `client_type`
  (`public | confidential | system`, default `public`), `secret_hash`
  (Argon2id via spring-security-crypto + BouncyCastle; null for public), and
  `granted_scopes` (space-separated). Registration stays on the existing
  audited org-admin API; for confidential/system clients the server generates
  the secret and returns it **once** in the create response — it is never
  retrievable again.
- **Issuance:** embedded Spring Authorization Server with a dedicated
  `@Order`-ed filter chain for `/oauth/token` + `/oauth/jwks`; settings pin
  the endpoint paths the SMART discovery document already advertises. A
  `RegisteredClientRepository` adapter reads `oauth_clients` (active,
  secret-bearing types only; grant type client_credentials; scopes =
  granted_scopes). RSA keypair generated at startup — tokens die on restart,
  which is the documented dev posture; persistent/rotating JWKs are an ops
  concern recorded in the runbook.
- **Validation:** the resource server's decoder routes by unverified `iss`:
  the embedded issuer → RS256 against the local JWK source; anything else →
  the existing HS256 dev decoder (unchanged contract for the whole test
  suite).
- **Principals:** a token customizer stamps `ehr_principal=system-app` and
  the client's organization. `JwtPrincipalAuthenticationConverter` branches
  on that claim: resolve the client by identifier (active client + active
  org), build a `SecurityPrincipal` with **no userId**, the client id, scopes
  from the token, and a synthetic `MembershipContext` (membership id = client
  id — evidence-only, documented) carrying role `SYSTEM_APP`. Client
  revocation is immediate: identity resolution re-checks `oauth_clients.status`
  on every request.
- **Policy:** EXPORT READ/WRITE gain `SYSTEM_APP` (`policy-spine-v18` +
  literal replace). Nothing else: clinical-record rules unchanged, and system
  principals already fail closed in compartment-enforced orgs (H3).
  `export_jobs.requested_by` is already nullable and the worker already
  tolerates a null requester — no export changes needed.

---

## File Structure

- Modify `build.gradle.kts` (`spring-boot-starter-oauth2-authorization-server`, `bcprov-jdk18on`).
- Create `src/main/resources/db/migration/V19__oauth_client_credentials.sql`.
- Create `src/main/kotlin/dev/ehr/authz/`: `AuthorizationServerConfiguration.kt`, `EhrRegisteredClientRepository.kt`, `SystemTokenCustomizer.kt` (or fold into the configuration).
- Modify `OAuthClientModels/Repository/Service/Controller` (client type, secret generation + hashing, granted scopes, one-time secret in response), `SecurityConfiguration.kt` (issuer-routing decoder; `/oauth/jwks` public), `JwtPrincipalAuthenticationConverter.kt` (system branch), `PolicyEvaluator.kt` (EXPORT roles, v18), `EhrProperties.kt` (issuer), SMART discovery doc/controller if grant types are listed.
- Create `src/test/kotlin/dev/ehr/authz/BackendServicesAuthIntegrationTest.kt`; extend `OAuthClientApiIntegrationTest` (type/secret matrix).

## Acceptance Criteria

- Org admin registers a `system` client → response carries the generated secret exactly once; subsequent reads never include it; `public` clients get none.
- `POST /oauth/token` (client_credentials, basic auth) → RS256 access token with the client's org and granted scopes; wrong secret → 401; revoked client → 401; `public` client → rejected for the grant.
- The token authorizes export kickoff/status (SYSTEM_APP + `system/*` wildcard scopes) with audit rows carrying a null subject user; the same token on a clinical read → 403 `INSUFFICIENT_ROLE` with denial audit.
- Dev HS256 tokens keep working everywhere (the suite is the proof); `/oauth/jwks` is public; `policy-spine-v18` everywhere.

## Intentional Deferrals

- Authorization code/PKCE/refresh/OIDC (AS2); patient launch (AS3); FHIR `$export` protocol (AS4); JWK persistence/rotation (runbook note); `private_key_jwt` client auth (revisit with SMART backend-services JWKS registration).

## Tasks

- [ ] Failing tests: registration secret matrix + token issuance/use matrix.
- [ ] V19 + client model/service changes; SAS configuration + adapter; decoder routing; converter branch; policy v18 + literals.
- [ ] Focused + full suites; commit plan as `docs: add Slice AS1 backend services auth plan`; implementation as `feat: issue backend-services tokens from an embedded authorization server`.

## Self-Review Checklist

- The secret appears in exactly one HTTP response ever, and only its Argon2id hash is stored; audit metadata never carries it.
- The AS filter chain owns only its own endpoints; everything else still hits the app chain's deny-all posture.
- System principals cannot touch clinical records (role) or compartments (no user id) — both denials audited.
