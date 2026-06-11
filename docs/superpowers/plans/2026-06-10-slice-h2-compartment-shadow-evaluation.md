# Slice H2 Compartment Shadow Evaluation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Second compartment-authorization slice per the accepted design (`docs/architecture/compartment-authorization.md`): the policy spine learns about the patient compartment and records what enforcement *would* do — `relationship_basis` lands in every clinical-record audit row — while **denying nothing**. Enforcement (per-org flag, `NO_TREATMENT_RELATIONSHIP`, break-glass, auto-expiry) is H3.

**Architecture:** `PolicyEvaluationRequest` gains an optional `patientId: UUID?`. `PolicyEvaluator` gains a constructor-injected `RelationshipResolver` (narrow `fun interface` in the security package so evaluator tests stay cheap), implemented in the careteam package against the partial index `ctm_org_user_patient_idx`. Each `PolicyRule` declares `requiresRelationship`; it is true for the clinical-record types (CONDITION, ALLERGY, OBSERVATION, MEDICATION, NOTE, ORDER, DIAGNOSTIC_REPORT, PROVENANCE, CHART) and false for PATIENT/ENCOUNTER (org-wide by design decision 3) and the admin types. On an **allowed** decision for a relationship-requiring rule with a known patient, the evaluator resolves and records `relationshipBasis` (`care-team-member` for any explicit membership, else `encounter-derived`, else null); denied decisions are untouched — role/scope denial precedes relationship interest. The decision's basis flows into a new `audit_events.relationship_basis` column.

Call-site shape: services that know the patient before evaluating (creates, list-by-patient, chart) pass it in the request. Fetch-first paths (get-by-id, updates, provenance-by-target) keep the pre-fetch evaluation as the role/scope gate, then **re-evaluate with the discovered patient** and use that enriched decision for the success audit row — this second evaluation point is exactly where H3 will deny. `POLICY_VERSION` bumps to `policy-spine-v16`.

---

## File Structure

- Create `src/main/resources/db/migration/V16__audit_relationship_basis.sql`.
- Create `src/main/kotlin/dev/ehr/security/RelationshipResolver.kt`; create `src/main/kotlin/dev/ehr/careteam/CareTeamRelationshipResolver.kt`.
- Modify `PolicyModels.kt` (`RelationshipBasis` enum, request `patientId`, typed decision field), `PolicyEvaluator.kt` (resolver injection, `requiresRelationship`, v16), `AuditModels.kt`/`AuditEventRepository.kt`/`AuditEventService.kt` (column plumbing).
- Modify the nine clinical services: `ConditionService`, `AllergyService`, `ObservationService`, `MedicationStatementService`, `ClinicalNoteService`, `OrderService`, `DiagnosticReportService`, `ChartService`, `ProvenanceQueryService`.
- Create `src/test/kotlin/dev/ehr/security/CompartmentShadowIntegrationTest.kt`; extend `PolicyEvaluatorTest` (stub resolver).

## Acceptance Criteria

- `audit_events.relationship_basis` exists, constrained to `care-team-member`/`encounter-derived`/`break-glass`, and is written from the decision on every resource-access audit path.
- Evaluator: resolves only when the rule requires a relationship AND the request carries a patient AND the principal has a user id AND the decision is otherwise allowed; explicit membership wins over encounter-derived; PATIENT/ENCOUNTER/CARE_TEAM/EXPORT/OAUTH_CLIENT rules never consult the resolver.
- Shadow proof (integration): clinician who opened an encounter reads the problem list → `relationship_basis = 'encounter-derived'`; explicitly added colleague reads the chart → `'care-team-member'`; an unrelated same-org clinician still gets **200** with `relationship_basis` null — nothing denies.
- Fetch-first proof: get-by-id of a clinical resource records the basis discovered post-fetch.
- `policy-spine-v16` everywhere; full suite green.

## Intentional Deferrals

- No denials, no enforcement flag/column, no break-glass, no auto-expiry, no purpose-of-use population (H3); no FHIR CareTeam/RLS (H4); system-app principals untouched (resolver requires a user id, so they shadow as null).

## Tasks

- [ ] Failing tests: evaluator unit matrix (stub resolver) + shadow integration scenarios.
- [ ] Implement V16, resolver pair, evaluator + audit plumbing, nine service call sites, v16 bump + literal replace.
- [ ] Focused + full suites; commit plan as `docs: add Slice H2 compartment shadow evaluation plan`; implementation as `feat: add shadow-mode compartment evaluation`.

## Self-Review Checklist

- No endpoint changes behavior: every previously-allowed request is still allowed; only audit rows gain data.
- Resolver query uses the tenant-leading partial index; one indexed lookup per relationship-requiring decision.
- Denied decisions never carry a relationship basis; system principals (no user id) resolve to null, not an error.
