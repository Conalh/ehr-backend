# Architecture Spine

Date: 2026-06-04
Status: Draft for review

## Purpose

This document captures the load-bearing architectural decisions for the EHR backend. It exists so implementation does not drift into a demo-shaped system.

## Product Boundary

The backend is a synthetic-data clinical record platform. It should be shaped like real EHR infrastructure, but it must not accept real PHI, prescribe medications, bill claims, make clinical recommendations, or claim regulatory certification.

## Source Of Truth

The internal clinical model is the source of truth. FHIR is the external contract and integration format.

This means:

- Domain tables store clinical facts in normalized form.
- FHIR resources are rendered from domain state and terminology metadata.
- Inbound FHIR writes, when supported, are validated and projected into domain commands.
- Original inbound FHIR payloads may be retained as bounded snapshots for round-trip and provenance reasons, but snapshots are not the primary business model.

## History Model

Use current-state rows plus revision and provenance tables.

Rejected alternative:

- Full event sourcing is deferred. It is powerful, but it would force every read model, search index, export job, and FHIR projection to depend on event replay before the product has proven its clinical workflows.

Chosen model:

- Clinical tables store the current visible state.
- Every mutable clinical record has a version compatible with FHIR `meta.versionId`.
- Clinical updates use optimistic concurrency and later map to ETag / `If-Match` where applicable.
- `resource_revisions` stores prior versions and change metadata.
- `provenance_events` stores authorship, source, import, synthetic-generation, correction, and transform metadata.
- Clinical data is not physically deleted through normal product workflows.

Clinical correction vocabulary:

- `active`: current valid fact.
- `inactive`: no longer clinically active, but historically true.
- `resolved`: clinically resolved condition or allergy status where the resource supports it.
- `entered-in-error`: recorded mistake; visible through history/audit but excluded from normal current chart views.
- `amended`: prior version corrected with traceable replacement.
- `addended`: appended clarification that does not overwrite the original authorial content.
- `superseded`: replaced by a newer version or merged identity path.

## Tenancy

Tenancy must be enforced in more than one layer.

Rules:

- Every tenant-scoped table has `organization_id NOT NULL`.
- Every patient-scoped clinical table has `organization_id` and stable internal `patient_id`.
- Repository APIs must require `organization_id` for tenant-scoped reads and writes.
- Composite indexes should begin with `organization_id` for tenant-scoped clinical queries.
- Prefer Postgres Row-Level Security once the first schema stabilizes.
- Until RLS is active, integration tests must deliberately attempt cross-org reads/searches/exports.

Patient identifiers are not tenancy keys. A future patient merge must repoint links through stable internal UUIDs rather than rewriting identifiers as if they were primary keys.

## Authorization Spine

Authorization produces a structured policy decision, not a boolean hidden in controller logic.

Policy decision fields:

- `allowed`
- `subject_id`
- `organization_id`
- `patient_id`
- `resource_type`
- `operation`
- `role_basis`
- `scope_basis`
- `relationship_basis`
- `purpose_of_use`
- `policy_version`
- `reason_code`

The effective permission is the intersection of local policy and token scopes. If SMART/FHIR scopes permit more than local policy, local policy wins. If local policy permits more than token scopes, scopes win.

Deferred but reserved:

- Break-glass emergency override with heightened audit.
- Sensitive data labels.
- Consent and patient-directed restrictions.
- Minor/guardian access policy.

## Audit Spine

Audit is a domain service, not only an HTTP interceptor.

Mechanisms:

- HTTP read/search audit for clinical API and FHIR API requests.
- Transactional write audit inside application services.
- Denied authorization audit emitted by the policy engine.
- Background/export/system-job audit emitted explicitly by job services.
- Materialized-record audit for searches that return patient-specific clinical resources.

Audit records must be append-only and include:

- subject
- client/app
- organization
- patient where known
- resource type and ID where known
- operation
- purpose of use where known
- policy decision reason where applicable
- timestamp
- correlation ID
- outcome

Large operations such as bulk export may use a parent audit event plus child/resource summary records rather than one unbounded row per field. The schema must still answer which patient records were included in an export.

Logs are not audit records. Default logs must not include clinical payloads, raw FHIR bundles, raw bearer tokens, or unredacted query strings.

## Provenance Spine

Provenance begins with the first clinical write.

Minimal provenance fields:

- `id`
- `organization_id`
- `patient_id`
- `target_resource_type`
- `target_resource_id`
- `target_version`
- `activity`
- `agent_user_id`
- `agent_client_id`
- `recorded_at`
- `source_type`
- `source_reference`
- `prior_resource_version`
- `synthetic_generation_run_id`

Supported source types:

- clinician-authored
- staff-recorded
- system-imported
- transformed
- synthetic-generated
- corrected
- amended
- addended

FHIR `Provenance` read/search can arrive later, but the internal data needed to render it must exist before clinical writes.

## FHIR Boundary

FHIR support is not free because we are not adopting HAPI JPA Server as the source of truth.

The project owns:

- FHIR resource mapping.
- FHIR search parameter registry.
- Search indexes.
- Paging.
- CapabilityStatement generation from actual supported behavior.
- OperationOutcome generation.
- Compartment enforcement.
- Authorization checks for included/revincluded resources.
- Version/ETag behavior for supported writes.

Initial FHIR writes should be narrow and documented. If inbound FHIR contains fields we do not model, choose one of these per endpoint:

- reject as unsupported with `OperationOutcome`;
- preserve inbound payload in a snapshot and rehydrate supported fields;
- accept lossy projection only when explicitly documented.

Silent lossy round-tripping is not allowed.

## FHIR Search Infrastructure

FHIR search gets its own infrastructure slice.

Supported search parameter types:

- token
- reference
- date
- string
- quantity

Search tables or indexed projections should record extracted search parameters from normalized resources. Every FHIR search must enforce tenant and patient-compartment restrictions after resolving includes and reverse-includes.

Minimum search infrastructure:

- search parameter registry
- extractor from domain state to search index
- cursor pagination
- supported `_count`
- generated CapabilityStatement search declarations
- tests proving search returns only visible records

Chained search, `_include`, `_revinclude`, `_sort`, and `_history` should be added deliberately, not accidentally through broad query parameters.

## Terminology Spine

Terminology is a first-class subsystem. See `docs/architecture/terminology-model.md`.

Clinical concepts must be stored as codeable concepts, not display strings:

- system
- code
- display
- version
- user-selected flag
- optional free text
- binding/profile context

The terminology model must support multiple codings for one concept, such as local code plus LOINC or SNOMED CT mapping.

## HAPI Decision

Use HAPI FHIR libraries first, not HAPI JPA Server.

Reason:

- The product thesis requires a clean internal clinical model.
- HAPI JPA Server would make FHIR persistence the center of the application.
- HAPI libraries still provide mature FHIR types, parsing, serialization, and validation support.

Cost:

- We own FHIR server plumbing.
- FHIR search must be designed intentionally.
- CapabilityStatement must be generated from our registry.
- Conformance testing must happen early enough to prevent drift.

This is not for style points. It is a trade: more domain control, more responsibility at the standards boundary.

## Early Roadmap

Slice 0: Runtime Skeleton

- Spring MVC Kotlin project.
- Postgres Docker Compose.
- Flyway.
- Testcontainers.
- Health endpoint.
- Correlation IDs and redacted logs.

Slice 1: Identity, Tenancy, Policy, Audit

- Organizations, users, practitioners, memberships.
- JWT fixture auth.
- Policy decision object.
- Audit service.
- Tenant guard tests.

Slice 2: Patient Registry And Identity

- Patients.
- Identifiers with system/value/use/type/assigner/period.
- Patient identity rules.
- Minimal provenance stub.
- FHIR Patient read/search.

Slice 2.5: FHIR Search Infrastructure

- Search parameter registry.
- Indexed token/reference/date/string/quantity params.
- Patient compartment checks.
- Cursor pagination.
- CapabilityStatement from registry.

Slice 3: Encounter Timeline With Terminology

- Encounters.
- Conditions.
- Allergies.
- Medications.
- Observations.
- Notes.
- Codeable concepts throughout.
- Internal chart endpoint and FHIR read/search bundles.

Slice 3.5: Minimal Inferno Smoke

- Content type.
- CapabilityStatement.
- OperationOutcome.
- Patient read/search.
- Basic Encounter, Condition, Observation behavior.

Slice 4: Revision And Provenance Expansion

- Version-aware updates.
- Resource revisions.
- FHIR Provenance read/search.
- Amendment/addendum/entered-in-error workflows.

## Slice Sizing Principle

Slices may be large in ambition but must be narrow in acceptance criteria. Build a whale as many vertebrae, not one giant blob.

