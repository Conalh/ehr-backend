# Slice 5.0 Order Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add clinical orders as an internal-first vertical: compartment-keyed schema with a coded orderable, an explicit status lifecycle with validated/audited transitions, provenance and revision capture, and the internal placement/transition API. FHIR mapping waits until the workflow stabilizes (the design spec's recommended default); results arrive in 5.1.

**Architecture:** `orders` follows the established compartment pattern (composite same-org FKs to `patients`/`encounters`, required `codeable_concepts` orderable — e.g. a LOINC lab panel, constrained `priority`). The lifecycle reuses the encounter-transition machinery: transitions encoded on `OrderStatus`, version-guarded repository update with required `expectedVersion` (`409` stale, `422` invalid transition), prior-state revision + `updated` provenance + `UPDATE` audit in one transaction. Placement is `POST /api/v1/orders` (patient in the body, per the design spec's API sketch) with `created` provenance. Policy: `ORDER` clinician-only read/write with FHIR-aligned `ServiceRequest` scope names (`policy-spine-v10`).

**Standards Notes:** Status vocabulary is the FHIR R4 `ServiceRequest.status` subset: `active` (placed), `on-hold`, `completed`, `revoked`, `entered-in-error`. Transitions: `active` → `on-hold`/`completed`/`revoked`/`entered-in-error`; `on-hold` → `active`/`revoked`/`entered-in-error`; `completed`/`revoked` → `entered-in-error`; `entered-in-error` terminal. `intent` is implicitly `order` and deferred as a column until other intents exist. Result attachment (5.1) will also complete orders.

**Tech Stack:** unchanged.

---

## File Structure

- Create `src/main/resources/db/migration/V12__order_foundation.sql`.
- Create `src/main/kotlin/dev/ehr/order/`: `OrderIds.kt`, `OrderEnums.kt`, `OrderModels.kt`, `OrderRepository.kt`, `OrderService.kt`, `OrderController.kt`.
- Modify `PolicyModels.kt`/`PolicyEvaluator.kt`: `ORDER` clinician-only rules, `policy-spine-v10` + test literal updates.
- Create `src/test/kotlin/dev/ehr/order/OrderSchemaMigrationTest.kt`, `OrderApiIntegrationTest.kt`.

## Acceptance Criteria

- `orders` table: compartment shape, `status` (default `active`, constrained to the five values), required `code_concept_id`, constrained nullable `priority` (`routine`/`urgent`/`stat`), `placed_at` (default now), version/audit columns, `unique (organization_id, id)` (for 5.1 result FKs), tenant-leading indexes including `(organization_id, patient_id, placed_at desc)`.
- `OrderStatus.canTransitionTo` encodes the transition matrix above; invalid transitions throw before touching the database.
- Internal API:
  - `POST /api/v1/orders` (body: `patientId`, `codeConceptId`, optional `encounterId`/`priority`) → `201` with `created` provenance; `404` cross-tenant patient/encounter; `400` unknown orderable;
  - `GET /api/v1/orders/{id}` → `200`/`404`; `GET /api/v1/patients/{id}/orders` → newest-first, `404` unknown patient;
  - `POST /api/v1/orders/{id}/status` (required `expectedVersion`) → `200` with revision + `updated` provenance (`entered-in-error` activity when voiding); `409` stale; `422` invalid transition; `404`/`403` as standard.
- Audit parity: `CREATE`/`READ`/`SEARCH`/`UPDATE` on resource type `ORDER` with patient + order IDs; failures and denials audited; unauthenticated unaudited.
- Policy: `ORDER` clinician-only read/write, `user|system / ServiceRequest|* . read|write` scopes, `policy-spine-v10`.
- All prior suites green apart from policy-version literals.

## Intentional Deferrals

- No result attachment or order auto-completion (5.1), no FHIR `ServiceRequest`, no `intent`/`category`/performer/specimen fields, no order sets or panels-of-panels.

## Tasks

- [ ] Failing schema tests (constraints, cross-org FKs, indexes, composite key) and API tests (placement/read/list/transition matrix incl. provenance + revision asserts, stale/invalid/denied paths).
- [ ] Implement V12, order package (mirroring encounter lifecycle patterns), policy bump + literals.
- [ ] Focused + full suites; commit plan as `docs: add Slice 5.0 order lifecycle plan`; implementation as `feat: add order lifecycle vertical`.

## Self-Review Checklist

- Orders are compartment-keyed and fail closed; transitions are optimistic-concurrency-guarded and leave a complete revision/provenance chain.
- The orderable is coded terminology; no display-string orders.
- FHIR mapping deliberately deferred, not half-built.
