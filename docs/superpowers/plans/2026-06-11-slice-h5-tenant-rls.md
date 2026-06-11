# Slice H5 Tenant Row-Level Security Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Defense-in-depth below the application: Postgres row-level security on every organization-scoped table, so a repository bug that drops the `organization_id` predicate cannot return or write another tenant's rows during a request that carries tenant context. This completes the compartment-authorization design's H4 second half (split out as H5).

**Architecture:**

- **Policies (V18):** every org-scoped table (patients, patient_identifiers, encounters, conditions, allergies, observations, medication_statements, clinical_notes, orders, diagnostic_reports, diagnostic_report_results, provenance_events, resource_revisions, care_team_memberships, export_jobs, export_job_files — 16 tables) gets `enable` + `force row level security` and one `FOR ALL` policy: rows are visible/writable when the transaction-context GUC `ehr.organization_id` is **unset** (`nullif(current_setting('ehr.organization_id', true), '') is null`) or matches the row's `organization_id`. The null-bypass mirrors the shadow-first philosophy: RLS is purely additive — migrations, test fixtures, and background workers (export processor, care-team expiry sweep) run without context and keep working; request paths set context and get the second wall.
- **Context plumbing:** a `TenantContextHolder` (ThreadLocal), a `TenantContextFilter` registered as a `@Bean` in `SecurityConfiguration` (the RateLimitFilter lesson: never `@Component`, or `@WebMvcTest` slices break) that binds the authenticated principal's organization for the request and always clears it, and a `TenantAwareDataSource` (BeanPostProcessor-applied `DelegatingDataSource`) whose borrowed connections run `set_config('ehr.organization_id', <org>, false)` when context is present and reset it on `close()` before the connection returns to the pool — pool-safe by construction. Kotlin `Connection by delegate` keeps the wrapper small.
- **The superuser caveat, stated honestly:** Postgres superusers bypass RLS unconditionally, and the dev/test containers connect as the image superuser. So the *policies* are proven in tests through a dedicated non-superuser `rls_probe` role with raw JDBC, the *plumbing* is proven through the app DataSource, and the runbook documents the production requirement: the application must connect as a non-superuser role (FORCE covers the table owner; nothing covers a superuser).
- No policy-spine change; no `POLICY_VERSION` bump.

---

## File Structure

- Create `src/main/resources/db/migration/V18__tenant_rls.sql` (DO-block loop over the 16 tables).
- Create `src/main/kotlin/dev/ehr/security/TenantContextHolder.kt`, `TenantContextFilter.kt`; `src/main/kotlin/dev/ehr/runtime/TenantAwareDataSource.kt` (+ BeanPostProcessor registration).
- Modify `SecurityConfiguration.kt` (filter bean), `docs/operations/runbook.md` (non-superuser deployment requirement), `docs/operations/threat-model.md` if it names RLS as future work.
- Create `src/test/kotlin/dev/ehr/security/RlsTenantIsolationIntegrationTest.kt`.

## Acceptance Criteria

- Probe-role proof (raw JDBC as non-superuser `rls_probe`): without the GUC, rows from both orgs are visible (bypass); with `ehr.organization_id = orgA`, only org A rows are returned, an org B row fetched by primary key returns nothing, and an insert claiming org B fails the `with check`.
- Plumbing proof: inside `TenantContextHolder` context, a query through the application DataSource sees the GUC set to the org id; outside, unset. The holder is cleared after each request (filter `finally`).
- Whole-suite proof: every existing test passes unchanged — fixtures, background jobs, and migrations are unaffected by construction.
- Runbook documents: app role must be non-superuser; FORCE covers the owner; the GUC convention.

## Intentional Deferrals

- No relationship-level RLS (the care-team predicate stays in the policy spine — H3 already enforces it; RLS mirrors tenancy, the stronger invariant). No split migration/runtime DB roles in dev (documented for production). No session-pooling (pgBouncer transaction-mode) caveats beyond a runbook note.

## Tasks

- [ ] Failing tests: probe-role isolation matrix + GUC plumbing assertions.
- [ ] Implement V18, holder/filter/datasource wrapper, runbook note.
- [ ] Focused + full suites; commit plan as `docs: add Slice H5 tenant RLS plan`; implementation as `feat: add tenant row-level security beneath the repository layer`.

## Self-Review Checklist

- RLS is additive: zero behavior change for any existing path; the suite is the proof.
- Connection reset on close is unconditional when set — a pooled connection can never carry a previous request's tenant.
- The honest limitation (superuser bypass in dev) is documented, not hidden.
