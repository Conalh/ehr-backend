# Slice UC5 Provenance Revinclude And Gap Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close US Core scope B: `_revinclude=Provenance:target` on the
profiled compartment searches (the way g10 fetches who-did-what alongside
clinical data), then refresh the Inferno gap report to record the whole
arc.

**Decided-and-recorded:**

1. **Exactly one revinclude.** `_revinclude=Provenance:target` is supported
   on the Condition, AllergyIntolerance, Observation, and DiagnosticReport
   compartment searches; any other `_revinclude` value is a 400
   OperationOutcome — bounded support, refused loudly (design decision 4).
2. **One authorized batch, not N audits.** `ProvenanceQueryService` gains
   `searchByTargets` (one policy evaluation carrying the compartment
   patient, one SEARCH audit row for the batch) over a new repository
   in-list query — included provenance rides the same authorization as the
   matches it annotates.
3. **Bundle semantics:** included entries carry `search.mode = include` and
   Provenance fullUrls; `total` keeps counting matches only, per FHIR.
4. The CapabilityStatement declares `searchRevInclude: Provenance:target`
   from a registry flag on exactly those four resources.
5. **Gap report refresh** records the arc: eight profiles proven
   (Patient, vital-signs, lab Observation, DiagnosticReport-lab, Condition,
   AllergyIntolerance, CareTeam, Provenance), four honest demotions
   (Encounter — type not modeled; DocumentReference — type binding needs
   full LOINC offline; Practitioner — no structured name;
   MedicationStatement — dropped by US Core), the SHALL-search set, and the
   still-pending live Inferno run.

---

## File Structure

- Modify `ProvenanceRepository.kt` (+`findByTargets`), `ProvenanceQueryService.kt` (+`searchByTargets`), the four FHIR controllers, `FhirCapabilityRegistry.kt` (+`revIncludesProvenance`), `CapabilityStatementController.kt`.
- Extend Condition + Observation FHIR API tests (include entries, bad value 400), `CapabilityStatementIntegrationTest`.
- Rewrite `docs/conformance/inferno-g10.md` (separate commit).

## Acceptance Criteria

- A compartment search with `_revinclude=Provenance:target` returns the matches plus their provenance as `include` entries (total unchanged); without it, no change; an unsupported value → 400.
- Included provenance is audited once per search and only for the authorized compartment.
- CapabilityStatement lists the revinclude on exactly the four resources; full suite green.

## Intentional Deferrals

- `_include`; revinclude on Patient/CareTeam/MedicationStatement searches; everything previously recorded.

## Tasks

- [ ] Repository/service batch lookup + controller wiring + registry flag.
- [ ] Tests; focused + full suites; commit plan as `docs: add Slice UC5 revinclude and gap report plan`; implementation as `feat: add Provenance revinclude to the profiled compartment searches`; gap report as `docs: refresh the Inferno g10 gap report after the US Core arc`.

## Self-Review Checklist

- Includes never widen authorization: same compartment, same audit trail.
- `total` semantics correct; include entries marked as such.
- The gap report claims exactly what the suite proves — nothing more.
