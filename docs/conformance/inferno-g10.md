# Inferno g10 Conformance: Run Instructions And Gap Report

Status as of the authorization-server arc (AS1–AS4, June 2026), which
replaced the Slice 10 baseline. **This product does not claim ONC
certification or g10 conformance**, and the statuses below are
implementation-based expectations — an actual Inferno run against this
build is the next verification step and may move rows.

## Running Inferno locally

```powershell
# 1. Run the stack (see docs/operations/runbook.md)
docker compose up -d
.\gradlew.bat bootRun

# 2. Run the ONC Certification (g10) test kit
git clone https://github.com/inferno-framework/onc-certification-g10-test-kit
cd onc-certification-g10-test-kit
docker compose run inferno bundle exec inferno migrate
docker compose up -d
# UI at http://localhost (port 80 by default)
```

Point Inferno at `http://host.docker.internal:8080/fhir/r4`. The embedded
authorization server is live: register a client through
`POST /api/v1/oauth-clients` (see the runbook for the dev login and client
types), and use the issued credentials in Inferno's SMART configuration.
Discovery is at `/.well-known/smart-configuration`.

## Gap matrix

| g10 area | Status | Grounding |
| --- | --- | --- |
| Standalone Patient App (SMART launch, PKCE, patient context) | **Expected pass (core flow)** | Authorization code + PKCE live (AS2); standalone patient launch with the synthetic picker, `patient` token-response context, and patient-context scopes bound to the launched patient (AS3). |
| EHR Practitioner App (user-context launch) | **Partial** | The authorize/token flow and `openid fhirUser` work for user apps (AS2/AS2B); the `launch` (EHR-context) scope itself is deferred — nothing exists to launch from. |
| OpenID Connect (`openid`/`fhirUser`, id_token) | **Expected pass** | OIDC id_token with `sub`; `fhirUser` emitted when the user has a practitioner identity, referencing the served FHIR `Practitioner` (AS2B). |
| Token refresh / revocation | **Expected pass (confidential clients)** | Rotating refresh tokens with reuse detection and RFC 7009 revocation (AS2). Public clients receive no refresh tokens — the authorization server's posture; Inferno groups expecting public-client refresh will flag this. |
| SMART discovery (`.well-known/smart-configuration`) | **Expected pass** | Live endpoints throughout; capabilities list is accurate by test (`launch-standalone`, `context-standalone-patient`, `sso-openid-connect`, `client-public`, `client-confidential-symmetric`, permissions). |
| Capability metadata (`/fhir/r4/metadata`) | **Expected pass (base checks)** | Public, registry-generated, accurate to the eleven served resources, search advertised only where it exists. US Core `supportedProfile` declarations still absent. |
| Single Patient API: read + search for the served resources | **Partial** | Read + compartment search served and validated against base R4; searchset bundles carry `self` links. Gaps unchanged: US Core profile conformance unvalidated; only the registry's search parameters exist (no `_id`, `date`, `code`, `status`, `_revinclude=Provenance:target`). |
| Missing resource types vs US Core (Immunization, Procedure, CarePlan, Device, Goal, Location, Organization read, PractitionerRole) | **Out of scope (roadmap)** | CareTeam and Practitioner were added (H4/AS2B); the rest follow the established vertical pattern. |
| Multi-Patient (Bulk Data) API | **Partial — system-level** | The FHIR `$export` kickoff/status protocol is live over the async NDJSON engine, authorized by backend-services (client-credentials) tokens (AS1/AS4). Gaps: `Group/[id]/$export` (no Group resource), `_type`/`_since` filters (refused with OperationOutcome), DELETE-cancel. g10's multi-patient group specifically drives Group-level export. |
| Invalid token / invalid request handling | **Expected pass** | 401 on missing/invalid tokens everywhere; OperationOutcome errors with correct issue codes; OAuth protocol errors from the live endpoints. |
| US Core profile validation of returned resources | **Out of scope until validated** | Base R4 validation only; claiming profiles before validating against them violates the project's honesty rule (AGENTS.md). |

## Explicit unsupported-criteria list

1. EHR launch (`launch` scope and launch-context resolution).
2. Refresh tokens for public clients.
3. `Group/[id]/$export` and `Patient/$export`; `_type`/`_since`; export cancel.
4. US Core profile conformance and `supportedProfile` declarations.
5. US Core resource types beyond the eleven served.
6. `_revinclude`, `_include`, and the wider US Core search parameter set.
7. `private_key_jwt` (asymmetric) client authentication for backend services.

Each item traces to a recorded decision in the design docs and slice plans.
The dominant remaining work program is **US Core profile alignment**; the
authorization-server prerequisite from the Slice 10 report is done.

## What this buys us

The Slice 10 report's two work programs were authorization-server
integration and US Core profile alignment. The first shipped (H1–H5
compartment hardening, AS1–AS4 token issuance and SMART flows); the gap
list is now dominated by profile and search-parameter work on the clinical
model. Next verification step: execute the Inferno kit against this build
and replace the "expected" qualifiers above with observed results.
