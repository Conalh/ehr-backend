# Slice AS2 User Authorization Flows Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Second authorization-server slice per the accepted design: user
apps obtain tokens through **authorization code + PKCE**, with rotating
refresh tokens, RFC 7009 revocation, and an OIDC `id_token` for `openid`.
The `/oauth/authorize` stub comes alive. After this slice the practitioner
Inferno group is runnable.

**Decided-and-recorded** (technical calls under the accepted design's
posture — flag only if they cross a goal of Conal's):

1. **Dev login.** The authorize endpoint requires an authenticated user;
   users have no credentials (synthetic data, no IdP). A dedicated session
   -based filter chain for the AS login uses Spring's generated login page —
   username = `users.external_subject`, password = a single shared
   `ehr.security.dev-login-password` (validated ≥16 chars at startup, same
   fail-at-boot posture as the dev JWT secret). This is the AS2 sibling of
   the accepted AS3 patient-picker decision: deliberately plain, honestly
   dev-only.
2. **Organization claim.** The principal converter requires an org claim.
   AS-issued user tokens carry the user's **single active membership's**
   organization; a multi-org user is rejected at token issuance with a clear
   error — org selection UX is deferred until something needs it (recorded
   limitation, mirrors the existing fail-closed posture).
3. **`fhirUser` deferred to AS2B.** Emitting a `fhirUser` reference to a
   Practitioner resource this server does not serve would be FHIR-shaped
   dishonesty. AS2 ships `openid` (id_token with `sub` = external subject);
   AS2B adds FHIR `Practitioner` read + the `fhirUser` claim together.
4. **No consent screen.** Synthetic data, first-party apps:
   `requireAuthorizationConsent(false)`. Revisit with real launch contexts.

**Architecture:** `oauth_clients` (V20) gains `redirect_uris` (space-
separated, required for non-system clients at registration). The
`RegisteredClientRepository` adapter maps grants by type — `system` →
client_credentials (AS1, unchanged); `confidential`/`public` →
authorization_code + refresh_token with registered redirect URIs; `public`
gets `ClientAuthenticationMethod.NONE` + `requireProofKey(true)` (PKCE
mandatory), `confidential` keeps secret auth. Token settings: 5-minute
access tokens (AS1), **rotating refresh tokens** (`reuseRefreshTokens =
false`, 90-day TTL); SAS invalidates a rotated token on reuse. Settings pin
`/oauth/authorize` and `/oauth/revoke`; the authorize stub is deleted. OIDC
is enabled on the AS chain; a user-token customizer adds the org claim
(decision 2) and flattened scopes so the existing converter path works
untouched. SMART discovery gains the new grant types, `client-public`, and
the revocation endpoint.

---

## File Structure

- Create `src/main/resources/db/migration/V20__oauth_client_redirect_uris.sql`.
- Modify `dev/ehr/authz/`: `AuthorizationServerConfiguration.kt` (OIDC, login chain, settings paths), `EhrRegisteredClientRepository.kt` (grants by type), new `DevLoginConfiguration.kt` (UserDetailsService over `users` + shared password) and `UserTokenCustomizer` logic (fold into the configuration file if small).
- Modify `OAuthClientService/Controller` (+`redirectUris` registration field, required unless system), `EhrProperties.kt` (`devLoginPassword`), `SmartConfigurationController.kt` (+grants/capabilities/revocation, drop authorize stub), V20 columns in `OAuthClientRepository.kt`.
- Create `src/test/kotlin/dev/ehr/authz/AuthorizationCodeFlowIntegrationTest.kt`; update `SmartConfigurationIntegrationTest`.

## Acceptance Criteria

- Full PKCE dance in tests: form login (shared dev password) → `GET /oauth/authorize` with S256 challenge → 302 with code to the registered redirect URI → `POST /oauth/token` with verifier → access + rotating refresh + `id_token` (when `openid` granted) whose `sub` is the user's external subject.
- The access token works against the clinical API end-to-end (existing converter path: org from the token's claim, roles from the DB).
- Public client without PKCE → rejected; wrong verifier → rejected; unregistered redirect URI → rejected; multi-org user → token issuance fails with a clear error.
- Refresh rotation: using a refresh token returns a new one and invalidates the old (reuse → `invalid_grant`); `POST /oauth/revoke` kills a refresh token.
- Wrong dev-login password → no session; the dev-login chain never touches `/api/**` or `/fhir/**` (stateless deny-all posture unchanged).
- Discovery accurate: `authorization_code` + `refresh_token` + `client_credentials`, `revocation_endpoint`, `client-public` capability. No `sso-openid-connect` until fhirUser ships (AS2B).
- Full suite green; no policy-spine change expected.

## Intentional Deferrals

- `fhirUser` + FHIR Practitioner read (AS2B); launch context and patient-context scopes (AS3); consent UX; org-selection UX for multi-org users; `$export` protocol (AS4).

## Tasks

- [ ] Failing tests: the PKCE dance matrix + rotation/revocation + discovery.
- [ ] V20 + registration changes; adapter grants; login chain + properties; OIDC + customizer; discovery updates.
- [ ] Focused + full suites; commit plan as `docs: add Slice AS2 user authorization flows plan`; implementation as `feat: add authorization code flow with PKCE, refresh rotation, and OIDC`.

## Self-Review Checklist

- The dev login is loudly dev-only: shared password validated at startup, no self-registration, no password storage on users.
- PKCE is mandatory for public clients; refresh reuse is detected; revocation works.
- The API surface stays stateless; only the AS endpoints gain session handling.
