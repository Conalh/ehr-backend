# EHR Backend Clinical Core Design

Date: 2026-06-04
Status: Draft for review

## Purpose

Build a real, production-shaped EHR backend foundation, not a toy FHIR CRUD demo. The first product should be usable as a synthetic-data clinical record system with serious boundaries: identity, tenancy, authorization, audit, provenance, clinical workflows, migrations, testing, and a standards-aligned FHIR API edge.

The system is not a certified EHR in the first release. It is also not approved for real PHI, prescribing, clinical decision support, billing, or production care delivery. The point is to build the kernel that such a product would need before those higher-risk capabilities are added.

## Product Thesis

A credible EHR backend has two truths:

1. The clinical domain must make sense on its own: patients, encounters, problems, meds, allergies, observations, notes, orders, results, users, organizations, and care-team responsibilities.
2. The interoperability boundary must speak healthcare standards: FHIR R4, US Core-shaped resources, SMART/OIDC authorization patterns, audit/provenance metadata, and eventual Bulk Data export.

The architecture should not make FHIR the only internal model. FHIR is the public contract and integration language. The clinical core should remain explicit, testable, and understandable.

## Current Standards Baseline

Target date: June 2026, United States orientation.

- FHIR R4 is the primary API/resource baseline.
- US Core is the U.S. profile family to shape patient-accessible data and required search behavior.
- ONC HTI-1 makes USCDI v3 the ONC Certification Program baseline as of January 1, 2026.
- SMART App Launch / OAuth / OpenID Connect should guide app authorization, user authorization, patient launch context, scopes, and discovery.
- ONC Inferno g10 should be treated as a future conformance yardstick, not as the first slice's only goal.
- HIPAA Security Rule and NIST SP 800-66 Rev. 2 should shape safeguards even while using synthetic data.

References:

- FHIR R4 REST API: https://hl7.org/fhir/R4/http.html
- FHIR R4 Security: https://hl7.org/fhir/R4/security.html
- US Core: https://hl7.org/fhir/us/core/
- SMART App Launch: https://hl7.org/fhir/smart-app-launch/
- ONC Inferno g10 Test Kit: https://fhir.healthit.gov/test-kits/onc-certification-g10/
- HTI-1 Final Rule: https://healthit.gov/regulations/hti-rules/hti-1-final-rule/
- HHS HIPAA Security Rule: https://www.hhs.gov/hipaa/for-professionals/security/index.html
- NIST SP 800-66 Rev. 2: https://csrc.nist.gov/pubs/sp/800/66/r2/final

## Non-Goals For The First Product Line

- No real patient PHI.
- No electronic prescribing.
- No claims, eligibility, prior authorization, or billing.
- No AI clinical advice or summarization.
- No medical-device or CDS functionality.
- No attempt to claim HIPAA compliance or ONC certification.
- No frontend dependency in the first backend milestone.
- No vendor integration against Epic, Oracle Health, Athena, or eClinicalWorks until the core model is stable.

## Recommended Stack

Backend:

- Kotlin 2.x
- Spring Boot 3.x
- Spring Web MVC or WebFlux only if async streaming becomes necessary
- Spring Security resource-server support
- HAPI FHIR R4 libraries for parsing, serialization, and validation support at the FHIR boundary

Data:

- PostgreSQL 16+
- Flyway migrations
- jOOQ or Spring Data JDBC for explicit SQL and predictable persistence
- JSONB only for bounded FHIR payload snapshots and external payload capture, not as the main domain model

Local platform:

- Docker Compose for Postgres and optional Keycloak
- Testcontainers for integration tests
- Gradle wrapper for reproducible builds

Why this stack:

- Spring Security and Postgres are boring in the right way for a security-heavy backend.
- HAPI FHIR lets us parse and validate FHIR without outsourcing the whole product architecture.
- Kotlin keeps domain code concise while remaining close to the Java healthcare ecosystem.
- Flyway makes schema evolution visible and reviewable.

## Architecture

The backend has five layers.

### 1. API Layer

Exposes two API families:

- Internal product API under `/api/v1`.
- FHIR API under `/fhir/r4`.

The internal API exists for product workflows that do not map cleanly to raw FHIR interactions. The FHIR API exists for interoperability, app access, and conformance growth.

### 2. Application Services

Coordinates use cases:

- Register patient.
- Open encounter.
- Record problem.
- Record allergy.
- Record medication statement or request.
- Record vital sign/lab observation.
- Write clinical note.
- Place order.
- Attach result.
- Search patient chart.
- Export patient chart.

Application services enforce authorization, create audit records, and create provenance records for clinical writes.

### 3. Domain Model

Owns clinical concepts independent of FHIR serialization.

Core aggregates:

- Organization
- User
- Practitioner
- Patient
- Encounter
- Problem
- Allergy
- Medication
- Observation
- ClinicalNote
- Order
- DiagnosticResult
- Document
- AuditEvent
- ProvenanceEvent

The domain should use explicit IDs, timestamps, status enums, and lifecycle rules. Clinical writes should be append-aware even when the latest view is easy to query.

### 4. Persistence

Stores normalized operational records with revision and audit support.

Persistence principles:

- Tenant/organization ID on every clinical row.
- Patient compartment key on every patient-scoped row.
- Soft deletion or status transitions for clinical records; avoid destructive delete for clinical data.
- Revision table or version columns for mutable clinical resources.
- Immutable audit table.
- Optional FHIR JSON snapshot table for rendered external representations.

### 5. Standards Boundary

Maps domain records to FHIR R4 resources and back where write support exists.

Initial FHIR resource support:

- `Patient`
- `Practitioner`
- `Organization`
- `Encounter`
- `Condition`
- `AllergyIntolerance`
- `MedicationRequest`
- `MedicationStatement`
- `Observation`
- `DiagnosticReport`
- `DocumentReference`
- `Bundle`
- `OperationOutcome`
- `CapabilityStatement`
- `AuditEvent`
- `Provenance`

FHIR should start read-heavy. Write support can begin with a small controlled subset once authorization, validation, audit, and provenance are reliable.

## Security Model

Security is a product feature, not middleware dust.

Principles:

- Deny by default.
- Authenticate every non-healthcheck request.
- Authorize by organization, role, patient compartment, resource type, operation, and purpose of use.
- Log every clinical read, search, write, export, and denied access.
- Use scoped tokens, not raw user IDs passed in request bodies.
- Require reason/purpose metadata for sensitive workflows where applicable.
- Keep synthetic data mode obvious and hard to confuse with live PHI mode.

Roles for V1:

- `SYSTEM_ADMIN`: platform operations, no routine chart access by default.
- `ORG_ADMIN`: manages users and organization settings.
- `CLINICIAN`: reads and writes assigned or in-scope patient charts.
- `STAFF`: reads demographic and scheduling-adjacent data; limited clinical write access.
- `PATIENT`: reads their own patient compartment.
- `SYSTEM_APP`: machine-to-machine integration with explicit scopes.

Authorization inputs:

- Authenticated subject.
- Organization.
- Role.
- SMART/FHIR scopes when present.
- Patient relationship or assignment.
- Requested resource type and operation.
- Patient compartment.
- Purpose of use.

## Audit And Provenance

Audit answers: who accessed or changed what, when, from where, using which app, with what outcome.

Provenance answers: where did this clinical fact come from, who authored it, and what prior source or transform produced it.

Audit requirements:

- Immutable append-only audit records.
- Audit all FHIR and internal API reads/searches/writes/exports.
- Include subject, app/client, organization, patient, resource, action, timestamp, IP/user agent where available, outcome, and correlation ID.
- Audit denied access attempts.
- Do not let application code bypass audit for clinical resources.

Provenance requirements:

- Clinical writes create provenance automatically.
- Imported or transformed data keeps source metadata.
- Updates link to prior revision where applicable.
- FHIR `Provenance` representation is generated from internal provenance events.

## Data Model Sketch

System tables:

- `organizations`
- `users`
- `practitioners`
- `memberships`
- `roles`
- `oauth_clients`
- `access_grants`

Clinical tables:

- `patients`
- `patient_identifiers`
- `encounters`
- `conditions`
- `allergies`
- `medications`
- `observations`
- `diagnostic_reports`
- `clinical_notes`
- `orders`
- `documents`

Control tables:

- `audit_events`
- `provenance_events`
- `resource_revisions`
- `fhir_resource_snapshots`
- `export_jobs`

Every clinical table has:

- `id`
- `organization_id`
- `patient_id`
- `status`
- `created_at`
- `created_by`
- `updated_at`
- `updated_by`
- `version`

## API Surface

Internal API examples:

- `POST /api/v1/patients`
- `GET /api/v1/patients/{patientId}/chart`
- `POST /api/v1/patients/{patientId}/encounters`
- `POST /api/v1/encounters/{encounterId}/notes`
- `POST /api/v1/encounters/{encounterId}/observations`
- `POST /api/v1/orders`
- `POST /api/v1/orders/{orderId}/results`
- `GET /api/v1/audit/patients/{patientId}`

FHIR API examples:

- `GET /fhir/r4/metadata`
- `GET /fhir/r4/Patient/{id}`
- `GET /fhir/r4/Patient?identifier=...`
- `GET /fhir/r4/Encounter?patient=...`
- `GET /fhir/r4/Observation?patient=...&category=vital-signs`
- `GET /fhir/r4/Condition?patient=...`
- `GET /fhir/r4/AllergyIntolerance?patient=...`
- `GET /fhir/r4/MedicationRequest?patient=...`
- `GET /fhir/r4/DocumentReference?patient=...`

FHIR responses use `application/fhir+json` and return `OperationOutcome` for structured errors.

## Error Handling

Internal APIs return stable problem details:

- `400` validation error.
- `401` unauthenticated.
- `403` authenticated but not authorized.
- `404` not found or not visible to caller.
- `409` version conflict.
- `422` clinical rule violation.
- `500` unexpected server error with correlation ID.

FHIR APIs return FHIR `OperationOutcome` where practical.

## Testing Strategy

Unit tests:

- Domain lifecycle rules.
- FHIR mapping functions.
- Authorization policy decisions.
- Audit/provenance creation.

Integration tests:

- Database migrations.
- Repository behavior.
- API auth.
- Patient chart workflows.
- FHIR resource read/search.
- Audit records for reads and writes.

Security tests:

- Cross-organization access denied.
- Patient compartment restrictions.
- Role restrictions.
- Denied attempts are audited.
- Token scope restrictions.

Conformance tests:

- Basic FHIR content-type behavior.
- `CapabilityStatement` availability.
- Required resource shape checks.
- Later: local ONC Inferno g10 runs for the supported subset.

## Roadmap Slices

### Slice 0: Repository And Runtime Skeleton

Goal: a real service that boots locally and in CI.

Deliverables:

- Gradle Kotlin Spring Boot project.
- Docker Compose with Postgres.
- Flyway migration pipeline.
- Health endpoint.
- Structured logging with correlation IDs.
- Testcontainers integration test proving DB connectivity and migrations.

Exit criteria:

- `./gradlew test` passes.
- Service boots against local Postgres.

### Slice 1: Identity, Tenancy, Authorization, Audit Spine

Goal: every future clinical endpoint runs through real access-control and audit plumbing.

Deliverables:

- Organizations, users, practitioners, memberships, roles.
- JWT resource-server validation in dev.
- Local development token fixture or Keycloak profile.
- Authorization policy service.
- Immutable `audit_events` table.
- Request audit interceptor for clinical API paths.

Exit criteria:

- Cross-organization access is impossible in tests.
- Denied clinical access creates an audit event.

### Slice 2: Patient Registry

Goal: create and retrieve patients safely.

Deliverables:

- Patient demographic model.
- Patient identifiers.
- Internal patient create/read/search.
- FHIR `Patient` read/search mapping.
- FHIR `OperationOutcome` errors.

Exit criteria:

- Patient data cannot leak across organizations.
- Every patient read/search is audited.

### Slice 3: Encounter Timeline

Goal: produce a real chart timeline.

Deliverables:

- Encounters.
- Conditions/problems.
- Allergies.
- Medications.
- Observations for vitals and labs.
- Clinical notes.
- Internal chart endpoint.
- FHIR read/search for `Encounter`, `Condition`, `AllergyIntolerance`, `MedicationRequest` or `MedicationStatement`, `Observation`, and `DocumentReference`.

Exit criteria:

- A synthetic patient chart can show a longitudinal timeline.
- FHIR read/search returns coherent bundles for the patient compartment.

### Slice 4: Provenance And Revision History

Goal: clinical facts have origin and version history.

Deliverables:

- `resource_revisions`.
- `provenance_events`.
- Automatic provenance for clinical writes.
- Version-aware updates for internal APIs.
- FHIR `Provenance` read/search for supported resources.

Exit criteria:

- Updating a clinical note or observation creates a new revision.
- Provenance identifies author, organization, timestamp, and target resource.

### Slice 5: Clinical Orders And Results

Goal: model a basic order/result lifecycle.

Deliverables:

- Order placement.
- Order status transitions.
- Diagnostic result attachment.
- FHIR `ServiceRequest` or internal `Order` with later FHIR mapping.
- FHIR `DiagnosticReport` mapping.

Exit criteria:

- A clinician can place a synthetic lab order and attach a synthetic result.
- Order state transitions are validated and audited.

### Slice 6: SMART/OIDC Shape

Goal: make the auth boundary look like a future SMART-compatible backend.

Deliverables:

- OAuth client registration model.
- Scope parser for patient/user/system-style scopes.
- `.well-known/smart-configuration`.
- Scope-to-policy authorization mapping.
- Token introspection stub or integration point.

Exit criteria:

- FHIR endpoints can be authorized by scopes plus local policy.
- Discovery document accurately describes supported capabilities.

### Slice 7: FHIR Capability And US Core Alignment

Goal: move from FHIR-shaped to standards-testable.

Deliverables:

- `CapabilityStatement` generated from actual supported endpoints.
- Profile metadata where supported.
- Required search parameter coverage for chosen resources.
- HAPI validator integration for outbound resources.
- Local conformance smoke tests.

Exit criteria:

- `GET /fhir/r4/metadata` is accurate.
- Supported resources validate against expected FHIR R4 structure.

### Slice 8: Bulk Export Foundation

Goal: support population-scale export mechanics without overbuilding analytics.

Deliverables:

- `export_jobs`.
- Async job state machine.
- NDJSON writer for supported FHIR resources.
- Secure download URL pattern for local development.
- Audit events for export request, file creation, and file download.

Exit criteria:

- A system app can request a synthetic patient-group export.
- Export produces valid NDJSON resource files for supported types.

### Slice 9: Operator Hardening

Goal: make the backend maintainable as real software.

Deliverables:

- Backup/restore notes.
- Migration rollback policy.
- Environment config validation.
- Rate limiting or request throttling.
- Security headers where relevant.
- Metrics endpoint.
- Redacted logging.
- Threat model document.

Exit criteria:

- A new developer can run the full stack locally.
- Logs do not expose clinical payloads by default.

### Slice 10: Inferno-Oriented Conformance Pass

Goal: identify the gap between our FHIR boundary and ONC g10 expectations.

Deliverables:

- Local Inferno run instructions.
- Test gap report.
- Fixes for supported-resource failures.
- Explicit list of unsupported certification criteria.

Exit criteria:

- We know which tests pass, which fail, and which are intentionally out of scope.

## First Milestone Recommendation

Build Slices 0 through 3 first.

That creates a real vertical backend:

- It boots.
- It persists data.
- It authenticates.
- It authorizes.
- It audits.
- It stores patient records.
- It exposes a patient chart.
- It exposes initial FHIR resources.

This is the smallest useful EHR backend kernel. It is not the whole game, but it is not Pong either.

## Open Decisions

1. Whether to use Keycloak immediately or start with signed local JWT fixtures and add Keycloak in Slice 6.
2. Whether to expose internal APIs publicly in early builds or keep them localhost/admin-only until a frontend exists.
3. Whether order/result mapping should begin with FHIR `ServiceRequest` in Slice 5 or stay internal until diagnostic workflows are better defined.
4. Whether patient assignment is explicit care-team membership or broader organization-level access in the first pass.

Recommended defaults:

- Start with signed local JWT fixtures, but design the claims for Keycloak/OIDC.
- Expose internal APIs only for local development.
- Keep orders internal first, then map after the workflow stabilizes.
- Use explicit patient assignment for clinician chart access.

## Review Checklist

- No real PHI is required or allowed.
- FHIR is a boundary, not the entire internal architecture.
- Auth, authorization, audit, and provenance are early slices.
- The first milestone produces a usable vertical clinical-record backend.
- Certification and HIPAA compliance are not claimed.
- The roadmap has clear exit criteria per slice.
