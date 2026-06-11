# Slice AS2B fhirUser And FHIR Practitioner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the OIDC story deferred from AS2: the `fhirUser` id_token
claim and the FHIR `Practitioner` resource it references, shipped together so
the server never emits a dangling reference. With both live, the discovery
document can honestly claim `sso-openid-connect`.

**Decided-and-recorded:**

1. **`fhirUser` is conditional on reality.** Emitted only when the `fhirUser`
   scope was granted AND the user has a `practitioners` row — an absolute
   `{issuer}/fhir/r4/Practitioner/{id}` URL. Users without a practitioner
   identity get no claim rather than a broken one.
2. **Practitioner visibility is membership-scoped.** Practitioners are global
   identity rows; a requester may read one only if its user holds an active
   membership in the requester's organization (join through `memberships`,
   fail-closed 404). Roles: clinician + staff, mirroring PATIENT-read
   (directory data; `Practitioner` scopes). New `PRACTITIONER` policy
   resource → `policy-spine-v19`.
3. **Read-only, no search.** The CapabilityStatement generator currently
   claims `search-type` unconditionally; it becomes conditional on declared
   search params so Practitioner is advertised honestly (read only). No
   change for the ten existing resources.
4. **Provisioning stays out-of-band.** `PractitionerRepository.create` exists;
   an admin API for practitioner provisioning is deferred until something
   needs it (no clinical data involved; identity rows only).

---

## File Structure

- Create `src/main/kotlin/dev/ehr/practitioner/PractitionerService.kt` (or fold into identity), `src/main/kotlin/dev/ehr/fhir/PractitionerFhirMapper.kt`, `PractitionerFhirController.kt`.
- Modify `PractitionerRepository.kt` (membership-scoped lookup), `PolicyModels.kt`/`PolicyEvaluator.kt` (PRACTITIONER, v19), `AuthorizationServerConfiguration.kt` (id_token fhirUser), `FhirCapabilityRegistry.kt` + `CapabilityStatementController.kt` (conditional search-type), `SmartConfigurationController.kt` (`sso-openid-connect`).
- Create `src/test/kotlin/dev/ehr/fhir/PractitionerFhirApiIntegrationTest.kt`; extend `AuthorizationCodeFlowIntegrationTest` (fhirUser present/absent), conformance + capability tests.

## Acceptance Criteria

- id_token: with `fhirUser` granted and a practitioner row → absolute Practitioner URL whose id resolves over the FHIR API; without a practitioner row → no claim; without the scope → no claim.
- `GET /fhir/r4/Practitioner/{id}`: 200 with name, NPI identifier (`us-npi` system), active flag for same-org-membership practitioners; 404 cross-org/unknown; 403 + denial audit for roles outside the rule; 401 unauthenticated; READ audit rows.
- CapabilityStatement: 11 resources; Practitioner advertises read only; existing resources unchanged. Practitioner output validates against base R4. Discovery includes `sso-openid-connect`.
- `policy-spine-v19` everywhere; full suite green.

## Intentional Deferrals

- Practitioner search and PractitionerRole; provisioning API; patient `fhirUser` (no patient-user linkage exists); AS3/AS4 unchanged.

## Tasks

- [ ] Failing tests: fhirUser matrix + Practitioner read matrix + capability/conformance updates.
- [ ] Implement membership-scoped lookup, policy v19 + literals, service/mapper/controller, customizer claim, registry + conditional search-type, discovery capability.
- [ ] Focused + full suites; commit plan as `docs: add Slice AS2B fhirUser and Practitioner plan`; implementation as `feat: emit fhirUser and serve FHIR R4 Practitioner read`.

## Self-Review Checklist

- No dangling references: every emitted fhirUser resolves on this server.
- Practitioner reads are tenant-honest (membership join), audited, and fail closed.
- The CapabilityStatement never advertises an interaction that does not exist.
