# AGENTS.md

Guidance for Codex and other coding agents working in this repository.

## Project Intent

This repository is for building a real, production-shaped EHR backend foundation using synthetic data only. Do not reduce the project to a toy FHIR CRUD demo. The core goal is a clinical-record backend kernel with explicit domain logic, standards-aligned FHIR boundaries, authorization, audit, provenance, terminology, migrations, and tests.

## Hard Constraints

- Do not introduce or require real PHI.
- Do not add AI/LLM clinical advice, summarization, diagnosis, triage, or recommendations.
- Do not claim HIPAA compliance, ONC certification, medical-device status, or production clinical readiness.
- Do not use a runtime flag that implies real-PHI mode is available. This product line is synthetic-only until a separate compliance gate exists.
- Do not store clinical concepts as display strings only. Use coded terminology structures.
- Do not expose clinical endpoints that bypass authorization, audit, tenancy, or provenance rules.
- Do not hand-wave FHIR search as unscoped SQL filters; resolve search parameters at the FHIR boundary over tenant- and compartment-scoped reads, and record index/extractor/`_count`/pagination gaps explicitly (see `docs/architecture/architecture-spine.md`).

## Architecture Commitments

- Internal clinical model is the source of truth.
- FHIR R4 is the public interoperability contract.
- Use current-state domain tables plus revision/provenance tables, not full event sourcing.
- Use Postgres with tenant isolation designed below the service layer.
- Prefer Spring MVC, Kotlin, jOOQ, Flyway, HAPI FHIR libraries, and Testcontainers.
- Use HAPI FHIR as a standards library for parsing, serialization, and validation. Do not adopt HAPI JPA Server as the primary persistence architecture unless the architecture docs are deliberately revised.

## Standards Baseline

Target the U.S. June 2026 baseline:

- FHIR R4.0.1
- Certification-aligned US Core 6.1.0 / USCDI v3
- SMART App Launch 2.0.0 for ONC-aligned behavior, designed compatibly with 2.2.0 where harmless
- OpenID Connect Core 1.0
- Bulk Data Access support as a later roadmap slice

Design terminology/profile support so later US Core and USCDI versions can be added without rewriting the clinical model.

## Required Docs To Read Before Building

- `docs/architecture/architecture-spine.md`
- `docs/architecture/terminology-model.md`
- `docs/superpowers/specs/2026-06-04-ehr-backend-clinical-core-design.md`

## Implementation Style

- Keep slices small enough to verify, but do not remove serious architecture from early slices.
- Add tests with each slice.
- Keep clinical write paths transactional with audit/provenance writes.
- Use explicit IDs, version fields, status fields, and lifecycle constraints.
- Prefer schema-level constraints for clinical lifecycle states where practical.
- Default logs must not contain clinical payloads, raw FHIR bundles, or unredacted query strings.

