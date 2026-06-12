# Slice UC4 US Core Search Parameters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fourth US Core slice: the search parameters the g10 Single
Patient API group actually SHALLs for the resources whose profiles we
claim — no generic FHIR search engine, every parameter a typed registry
entry backed by an explicit repository predicate (design decision 4).

**The bounded set** (US Core SHALL searches against claimed profiles):

| Resource | New params | Mechanics |
|---|---|---|
| Patient | `_id` | token → findById |
| Condition | `category`, `clinical-status` | category is the **constant** `problem-list-item` (match-all or match-none — the model has one category); clinical-status filters the existing column |
| Observation | `code`, `date` | code → coding join (`system\|code` or bare code); date → effective_at range |
| CareTeam | `status` | constant `active` (the served team is always active) |
| DiagnosticReport | `category`, `code`, `date` | category is the constant `LAB`; code → coding join; date → issued_at range |

**Decided-and-recorded:**

0. **Predicates apply at the FHIR boundary, not in SQL** (amended from the
   first draft of this plan): every search is already constrained to one
   authorized patient compartment by the audited service, and the
   controllers already resolve each resource's coded concept for mapping —
   so token/date predicates filter that small, scoped list in one place
   instead of duplicating the terminology join per repository. This is a
   presentation-boundary filter over an authorized result set, not
   FHIR-search-as-naive-SQL.

1. **Constant-valued params filter honestly.** Where the model has exactly
   one value (Condition category, CareTeam status, DiagnosticReport
   category), the parameter is real: the matching value returns everything,
   any other returns an empty bundle — never ignored, never pretended.
2. **Token params** accept `system|code` or bare `code` (bare matches any
   system), the standard FHIR form, parsed by one shared helper.
3. **Date params** support `eq|ge|gt|le|lt` prefixes at day or instant
   precision (day expands to the day's range); an unknown prefix or
   unparsable value is a 400 OperationOutcome, not a guess.
4. **Combination searches compose** (e.g. `patient+code+date`) because each
   param is an independent predicate ANDed in the repository query.
5. Patient `name`/`birthdate` searches (US Core's fuller Patient set) are
   deferred and recorded — string matching is its own design conversation.

---

## File Structure

- Create `src/main/kotlin/dev/ehr/fhir/FhirSearchParams.kt` (token + date parsing).
- Modify the five FHIR controllers, their services/repositories (predicate args: Observation code/date, DiagnosticReport code/date, Condition clinical-status), `FhirCapabilityRegistry.kt` (new declared params).
- Extend the five FHIR API integration tests (match/no-match/bad-input matrix), `CapabilityStatementIntegrationTest`.

## Acceptance Criteria

- Every new parameter: a matching search returns the resource, a non-matching one returns an empty bundle (total 0), and combinations AND together; malformed dates → 400 OperationOutcome.
- Patient `_id` returns a singleton bundle for an own-org patient and an empty bundle cross-tenant (tenancy unchanged — the service path is the same audited one).
- The CapabilityStatement lists exactly the implemented parameters (registry lockstep); full suite green; no policy change.

## Intentional Deferrals

- Patient name/birthdate/gender searches; `_revinclude=Provenance:target` (UC5); `_lastUpdated`, `_sort`, paging.

## Tasks

- [ ] Shared param parsing + repository predicates.
- [ ] Controller wiring + registry entries; failing tests first per resource.
- [ ] Focused + full suites; commit plan as `docs: add Slice UC4 search parameters plan`; implementation as `feat: add the US Core search parameters`.

## Self-Review Checklist

- No parameter is advertised that is not implemented, and vice versa.
- Searches stay inside the audited service paths — no new authorization surface.
- Constant-valued filters return honest empties, never silently ignore the param.
