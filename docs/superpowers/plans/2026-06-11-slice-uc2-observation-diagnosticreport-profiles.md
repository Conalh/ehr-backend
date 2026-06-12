# Slice UC2 Observation And DiagnosticReport Profiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Second US Core slice (scope B): the two meatiest profiles —
`us-core-vital-signs` and `us-core-observation-lab` for Observation
(stamped **by category**), and `us-core-diagnosticreport-lab` for
DiagnosticReport — declared, stamped, and validated like UC1.

**Decided-and-recorded:**

1. **Registry profiles become a list.** Observation legitimately claims two
   profiles (one per category); `SupportedResource.profile: String?`
   becomes `profiles: List<String>` and the CapabilityStatement emits all
   of them. The mapper stamps exactly the one matching the instance's
   category — an instance never claims a profile it does not satisfy.
2. **Quantities are UCUM.** The mapper already emits
   `system=unitsofmeasure.org, code=unit`; this slice makes that contract
   explicit: stored observation units are UCUM codes (true of every
   fixture and the API examples), recorded here rather than re-modeled.
3. **DiagnosticReport gains its LAB category** (`v2-0074#LAB`) — the model
   only produces order-result (laboratory) reports, so the category is
   constant and honest. If the profile demands `effective[x]`, it maps from
   `issuedAt` with a recorded approximation note (specimen-collection time
   is not modeled); otherwise it is omitted — the validator decides, per
   the empirical triage rule from UC1.
4. **The conformance suite gains a laboratory Observation example** so both
   Observation profiles are proven, not just the vital-signs one.

---

## File Structure

- Modify `FhirCapabilityRegistry.kt` (profiles list; Observation + DiagnosticReport entries), `CapabilityStatementController.kt` (emit all), `ObservationFhirMapper.kt` (category-conditional stamp), `DiagnosticReportFhirMapper.kt` (category + stamp ± effective), `FhirConformanceValidationTest.kt` (lab example + claim assertions), `CapabilityStatementIntegrationTest.kt`.

## Acceptance Criteria

- Vital-signs and laboratory Observation examples validate against their declared profiles with no error-severity findings; the DiagnosticReport example validates against `us-core-diagnosticreport-lab`.
- Instances stamp exactly one category-matched profile; the CapabilityStatement lists both Observation profiles and the DiagnosticReport profile; unproven resources still claim nothing.
- Full suite green; terminology gaps (if any) recorded in the gap report, never suppressed.

## Intentional Deferrals

- Remaining resource profiles (UC3); search parameters (UC4); `_revinclude` (UC5); per-code vital-sign profiles (blood pressure panels etc.) — the category-level US Core profiles are the g10 target.

## Tasks

- [ ] Registry list refactor + mapper stamps + DR category.
- [ ] Conformance lab example; run, triage findings empirically, fix or record.
- [ ] Focused + full suites; commit plan as `docs: add Slice UC2 Observation and DiagnosticReport profiles plan`; implementation as `feat: declare and validate the US Core Observation and DiagnosticReport profiles`.

## Self-Review Checklist

- No instance stamps a profile its category does not match.
- The registry/CapabilityStatement/mapper trio stays in lockstep.
- Every validator finding was either fixed or recorded — none silenced.
