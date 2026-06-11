# Slice UC1 US Core Validator And Patient Profile Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** First US Core slice per the alignment design
(`docs/architecture/us-core-alignment.md`, scope B): the conformance
validator learns US Core, and `Patient` becomes the first resource that
**declares and proves** a US Core profile — `meta.profile` stamped by the
mapper, `supportedProfile` emitted by the registry-driven
CapabilityStatement, and the example validated against the profile (not
just base R4) in tests.

**Architecture:**

- **Package loading.** The US Core IG package (`hl7.fhir.us.core` 6.1.0
  `.tgz` from packages.fhir.org, plus its `hl7.fhir.uv.extensions` and
  `us.nlm.vsac`-adjacent dependencies as needed) is committed under
  `src/test/resources/fhir-packages/` and loaded via
  `NpmPackageValidationSupport` into the existing `ValidationSupportChain`.
  Test-scope only: the runtime never validates inbound profiles.
- **Terminology honesty.** US Core leans on VSAC value sets that are not
  freely redistributable. Where the local chain cannot expand a binding, the
  validator surfaces it; the slice triages every such finding — fix what the
  mapper can fix, and record (in the gap report) any element whose binding
  genuinely cannot be validated offline. We never suppress error-severity
  findings to make a claim.
- **Registry carries the claim.** `SupportedResource` gains an optional
  `profile: String?`; the CapabilityStatement emits `supportedProfile` from
  it; the mapper stamps the same URL into `meta.profile`. Declaration,
  stamping, and validation land together — the registry stays the single
  source of truth and a review failure otherwise.
- **Patient must-supports.** The mapper already emits identifier
  (system+value), name (family+given), gender, and birthDate; UC1 closes
  whatever the profile validator flags (e.g. `Patient.name.use`/text
  handling, identifier slicing) rather than guessing in advance.

---

## File Structure

- Add `src/test/resources/fhir-packages/*.tgz` (US Core + dependencies).
- Modify `FhirConformanceValidationTest.kt` (package loading, per-resource declared-profile validation), `FhirCapabilityRegistry.kt` (+`profile`), `CapabilityStatementController.kt` (`supportedProfile`), `PatientFhirMapper.kt` (`meta.profile` + must-support fixes), `CapabilityStatementIntegrationTest.kt`.

## Acceptance Criteria

- The conformance test validates the Patient example against
  `http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient` with no
  error-severity findings; any offline-unvalidatable bindings are listed in
  the gap report, not suppressed.
- `GET /fhir/r4/Patient/{id}` responses carry `meta.profile`; the
  CapabilityStatement lists `supportedProfile` for Patient and for no
  resource that hasn't proven one.
- Full suite green; no policy change.

## Intentional Deferrals

- All other resources' profiles (UC2/UC3); search parameters (UC4);
  `_revinclude` (UC5); MedicationStatement stays base R4 (design decision 3).

## Tasks

- [ ] Fetch and commit the IG packages; wire `NpmPackageValidationSupport`.
- [ ] Failing test: Patient validated against the declared profile.
- [ ] Registry `profile` + CapabilityStatement `supportedProfile` + mapper stamp + must-support fixes.
- [ ] Focused + full suites; commit plan as `docs: add Slice UC1 US Core Patient plan`; implementation as `feat: declare and validate the US Core Patient profile`.

## Self-Review Checklist

- No profile is declared anywhere it isn't validated in the same commit.
- The validator chain change does not weaken base-R4 validation for the other resources.
- Terminology gaps are recorded, never silenced.
