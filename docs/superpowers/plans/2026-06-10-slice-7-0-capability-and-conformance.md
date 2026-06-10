# Slice 7.0 FHIR Capability And Conformance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serve an accurate `GET /fhir/r4/metadata` CapabilityStatement generated from a single registry of what the server actually supports, and prove outbound FHIR validity with HAPI-validator conformance smoke tests over every served resource type.

**Architecture:** A declarative `FhirCapabilityRegistry` is the single source of truth: nine resource types, their interactions (`read`, `search-type`), and their search parameters (Patient: `identifier`; Observation: `patient`+`category`; Provenance: `target`+`patient`; the rest: `patient`). The metadata controller builds the CapabilityStatement from the registry — `fhirVersion 4.0.1`, JSON-only, `kind: instance`, REST security advertising SMART-on-FHIR with the `oauth-uris` extension pointing at the declared 6.2 stubs. `/fhir/r4/metadata` becomes public (FHIR convention; everything else under `/fhir/r4/**` stays authenticated). Conformance: HAPI validation (test-scope dependencies) runs `FhirInstanceValidator` + default R4 profile support over mapper-built instances of all nine resources plus an `OperationOutcome`, asserting no error-severity issues — the design spec's "local conformance smoke tests". Inline production-path outbound validation and US Core `supportedProfile` declarations are deferred: we do not claim profiles we have not validated against (AGENTS.md honesty constraints; Inferno work is Slice 10).

---

## File Structure

- Modify `build.gradle.kts`: test-scope `hapi-fhir-validation`, `hapi-fhir-validation-resources-r4`, `hapi-fhir-caching-caffeine`.
- Create `src/main/kotlin/dev/ehr/fhir/FhirCapabilityRegistry.kt`, `CapabilityStatementController.kt`.
- Modify `SecurityConfiguration.kt`: permit `/fhir/r4/metadata`.
- Create `src/test/kotlin/dev/ehr/fhir/CapabilityStatementIntegrationTest.kt`, `FhirConformanceValidationTest.kt`.

## Acceptance Criteria

- `GET /fhir/r4/metadata` is public and returns `application/fhir+json` with: `fhirVersion 4.0.1`, `format ["application/fhir+json"]`, `kind instance`, server REST mode, SMART-on-FHIR security service with `oauth-uris` extension (authorize/token matching the discovery document), and **exactly** the nine registry resources with their true interactions and search parameters — nothing more.
- Other `/fhir/r4/**` routes remain authenticated.
- The conformance test validates mapper-produced instances of all nine resources plus `OperationOutcome` against base R4 with zero error-severity issues.
- Full suite green; no policy change.

## Intentional Deferrals

- No US Core `supportedProfile` declarations (claimable only after profile validation; Slice 10/Inferno), no XML, no `_count`/paging declarations, no inline production-path response validation, no write interactions advertised.

## Tasks

- [ ] Failing capability test (public access, accuracy assertions) and conformance validation test.
- [ ] Implement registry + controller + security carve-out + test dependencies.
- [ ] Focused + full suites; commit plan as `docs: add Slice 7.0 capability and conformance plan`; implementation as `feat: add CapabilityStatement and FHIR conformance smoke tests`.

## Self-Review Checklist

- The CapabilityStatement cannot drift from reality without changing the registry next to the controllers that implement it.
- Nothing is advertised that is not served; nothing served is omitted.
- Validation issues of error severity fail the build.
