# Authorization Server Integration Design

Date: 2026-06-11
Status: **Accepted 2026-06-11.** Decisions: 1=B (embedded — this project is
GitHub-first, one runnable service; the issuer/JWKS seam keeps extraction
mechanical), 2=AS1→AS4 as proposed, 3=EXPORT-only SYSTEM_APP first,
4=5-minute access / 90-day rotating refresh, 5=synthetic patient-picker at
AS3.

## Problem

Every request today authenticates with a locally signed HS256 dev JWT; the
`/oauth/authorize` and `/oauth/token` endpoints are declared 501 stubs. That
was the Slice 6 deferral, and it is now the single prerequisite behind most of
the Inferno g10 gap list (`docs/conformance/inferno-g10.md`): SMART launch,
OIDC, patient-context scopes, refresh/revocation, and backend-services bulk
export all need real token issuance. This document decides how tokens get
issued. It deliberately does **not** decide US Core profile work — the other
g10 prerequisite — which is orthogonal.

What carries over unchanged regardless of the decisions: identity is always
resolved from the database, never trusted from the token (the
`JwtPrincipalAuthenticationConverter` contract); the policy spine stays the
sole authorization authority — the AS authenticates and scopes, it never
authorizes clinical access.

## Decision 1 — Build, embed, or federate

**Options:**

A. **Federate to an external AS (Keycloak/Auth0).** Realistic for production
   EHRs, but the interesting engineering moves into YAML, SMART launch
   context requires vendor-specific extension SPIs anyway, and local dev
   gains a heavyweight dependency.
B. **Embed Spring Authorization Server (recommended).** First-party Spring
   project, in-language, in-process: authorization code + PKCE, client
   credentials, refresh tokens, revocation, and OIDC out of the box, with
   token-customizer hooks where SMART launch context and our org/scope
   claims get minted. The AS lives in this service for now (a `dev.ehr.authz`
   package with its own slice of endpoints) — extractable later because the
   resource-server side already validates by issuer/JWKS, not by sharing
   code.
C. **Hand-roll a minimal SMART AS.** Maximum control, but re-implementing
   PKCE, token rotation, and OIDC correctly is exactly the wheel Spring
   Authorization Server exists to provide; hand-rolling it is risk without
   portfolio upside.

**Recommendation: B.**

## Decision 2 — Token format and validation path

RS256 JWTs from a generated JWK set (`kid`-rotatable), issuer
`{base}/oauth`, JWKS at `/oauth/jwks`. The resource-server side moves from
the HS256 shared-secret decoder to issuer/JWKS validation. The dev-JWT path
stays available **in tests only** (the suite's `DevJwtFactory` continues to
work via a test-profile multi-issuer decoder); the runtime property that
enables it refuses to start outside dev, mirroring the existing fail-at-boot
posture in `EhrProperties`.

## Decision 3 — System-app principals (backend services)

Client-credentials tokens have no user. Shape:

- `oauth_clients` gains `client_type` (`public | confidential | system`),
  `secret_hash` (Argon2id; null for public clients), and granted-scope
  allowlist. Registration stays org-admin-only on the existing audited API.
- A system token resolves to a `SecurityPrincipal` whose subject carries
  `clientId` and **no userId**, organization from the client's org, and a
  synthetic membership with role `SYSTEM_APP` (already in the role enum).
- Policy spine: `SYSTEM_APP` is added per rule, starting narrow — EXPORT
  read/write (unlocking FHIR `$export` backend services) and clinical-record
  READ with `system/*` scopes. Compartment behavior is already decided:
  no user id means no treatment relationship, so system apps **fail closed in
  enforced orgs** (population-scale work goes through EXPORT, which is
  relationship-exempt by design).

## Decision 4 — Token lifecycle

- Access tokens: 5 minutes (SMART recommends short; everything re-validates
  cheaply via JWKS).
- Refresh tokens: confidential + public-with-PKCE clients, rotating
  (one-time-use, reuse detection revokes the family), 90-day absolute cap,
  revoked when the client is revoked — `oauth_clients.status` is already
  checked at identity resolution, so revocation is immediate even for live
  access tokens.
- Revocation endpoint (`/oauth/revoke`) per RFC 7009; introspection deferred
  until something opaque exists to introspect.

## Decision 5 — SMART launch context

Phased:

1. **Standalone launch, user context** (`launch/patient` absent): authorize +
   token with PKCE, `openid fhirUser` id_token. Unlocks the practitioner-app
   Inferno group.
2. **Standalone patient launch**: a deliberately plain patient-picker page at
   authorize-time (synthetic data only); token response carries `patient=`
   launch context, and **patient-context scopes finally authorize**: the
   policy spine's `SmartScope` patient-context branch stops failing closed
   and instead constrains the compartment to the launched patient — note the
   compartment plumbing from H2 (patientId on every request) is exactly the
   enforcement point this needs.
3. **EHR launch** (`launch` + context resolution): deferred until something
   exists to launch from.

## Slice breakdown (proposed)

- **AS1:** Spring Authorization Server skeleton: client-credentials grant
  only, RS256/JWKS, resource server validates both issuers (test profile),
  `oauth_clients` schema extension + secret hashing. System-app principal +
  SYSTEM_APP policy rows for EXPORT.
- **AS2:** Authorization code + PKCE + refresh rotation + revocation for
  user apps; OIDC id_token (`openid fhirUser`).
- **AS3:** Standalone patient launch + patient-context scope authorization
  through the compartment plumbing.
- **AS4:** FHIR Bulk Data `$export` kickoff/status protocol over the existing
  export engine, authorized via backend-services tokens.
- Re-run Inferno after AS2 and AS4 and update the gap report.

## Open questions for review

1. Embed Spring Authorization Server in this service (recommended), or stand
   it up as a separate deployable from day one?
2. Slice order: AS1→AS4 as above, or pull AS4 ($export) before AS2/AS3
   because backend services are simpler than interactive flows?
3. Should SYSTEM_APP clinical-record READ ship in AS1, or EXPORT-only until a
   concrete consumer exists? (Recommended: EXPORT-only first.)
4. Token TTLs: 5-minute access / 90-day rotating refresh acceptable, or
   tighter?
5. Patient-context scopes (AS3): is the synthetic patient-picker acceptable
   as the launch UX, or defer AS3 entirely until there is a real app to
   launch?
