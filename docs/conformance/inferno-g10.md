# Inferno g10 Conformance: Run Instructions And Gap Report

Status as of Slice 10 (June 2026). This document satisfies the design spec's
Slice 10 exit criterion: we know which g10 areas pass, which fail, and which
are intentionally out of scope. **This product does not claim ONC certification
or g10 conformance**, and several g10 prerequisites are deliberately deferred.

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

Point Inferno at `http://host.docker.internal:8080/fhir/r4`. Because there is
no authorization server yet, SMART launch test groups cannot execute; for the
API-only groups, supply a locally minted dev JWT (see the runbook) as the
bearer token where the kit allows manual token entry.

## Gap matrix

| g10 area | Status | Grounding |
| --- | --- | --- |
| Standalone Patient App / EHR Practitioner App (SMART launch, OAuth flows) | **Out of scope** | No authorization server; `/oauth/authorize` and `/oauth/token` are declared 501 stubs (Slice 6.2). Patient-context scopes fail closed by design (Slice 6.0). |
| OpenID Connect (`openid`/`fhirUser`, id_token) | **Out of scope** | OIDC deferred with the authorization server. Discovery deliberately omits `sso-openid-connect`. |
| Token refresh / revocation | **Out of scope** | No token issuance exists to refresh or revoke. |
| SMART discovery (`.well-known/smart-configuration`) | **Partial — passes shape checks** | Served and accurate (Slice 6.2 tests); will fail checks requiring live authorize/token endpoints. |
| Capability metadata (`/fhir/r4/metadata`) | **Expected pass (base checks)** | Public, registry-generated, accurate to the nine served resources (Slice 7.0 tests). US Core profile declarations absent — checks requiring `supportedProfile` will flag this. |
| Single Patient API: read + search for Patient, Encounter, Condition, AllergyIntolerance, Observation, MedicationStatement, DocumentReference, DiagnosticReport, Provenance | **Partial** | Read + compartment search served, validated against base R4 (Slice 7.0 smoke tests), searchset Bundles carry `self` links (Slice 10). Gaps: US Core **profile** conformance unvalidated (missing `Patient.name` slicing requirements, `Observation` vital-signs profile fields, etc.); only the search parameters in the CapabilityStatement exist (no `_id`, `date`, `code`, `status`, `_revinclude=Provenance:target`). |
| Missing resource types vs US Core (Immunization, Procedure, CarePlan, CareTeam, Device, Goal, Location, Organization read, Practitioner read, PractitionerRole) | **Out of scope (roadmap)** | Clinical model covers the Slice 3–5 resources only; additions follow the established vertical pattern. |
| Multi-Patient (Bulk Data) API | **Partial — internal only** | NDJSON export of all nine types exists with an async state machine (Slice 8.0), but over internal endpoints; the FHIR `$export` kickoff/status protocol, `_type` filters, and backend-services authorization are deferred. |
| Invalid token / invalid request handling | **Expected pass** | 401 on missing/invalid tokens everywhere (tested per resource); `OperationOutcome` errors with correct issue codes. |
| US Core profile validation of returned resources | **Out of scope until validated** | We validate against base R4 only; claiming profiles before validating against them violates the project's honesty rule (AGENTS.md). |

## Explicit unsupported-criteria list

1. SMART App Launch flows (EHR + standalone), PKCE, launch context.
2. OpenID Connect identity tokens.
3. Patient-context (`patient/*`) scope authorization.
4. Token refresh, revocation, and introspection.
5. US Core profile conformance and `supportedProfile` declarations.
6. US Core resource types beyond the nine served.
7. FHIR Bulk Data `$export` protocol (internal export only).
8. `_revinclude`, `_include`, and the wider US Core search parameter set.

Each item traces to a recorded deferral (Slices 6.0–6.2, 7.0, 8.0) and most
collapse into two prerequisites: a real authorization server, and US Core
profile work on the clinical model.

## What this buys us

The gap between "FHIR-shaped" and "certifiable" is now an explicit, reviewed
list instead of an unknown. The two work programs that close most of it are
(1) authorization server integration and (2) US Core profile alignment —
both candidates for design documents before implementation.
