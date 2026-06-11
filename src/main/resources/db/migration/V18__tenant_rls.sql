-- Tenant row-level security (design: docs/architecture/compartment-authorization.md, H5).
-- Defense-in-depth below the repository layer: when a connection carries the
-- ehr.organization_id GUC (set per-request by the application), rows from any
-- other organization are invisible and unwritable — even if a query forgot
-- its organization_id predicate.
--
-- The null-bypass keeps RLS purely additive: migrations, test fixtures, and
-- background workers run without the GUC and are unaffected.
--
-- Honest limitation: Postgres superusers bypass RLS unconditionally. FORCE
-- covers the table owner; production deployments must connect as a
-- non-superuser role (see docs/operations/runbook.md).
do $$
declare
    tenant_table text;
begin
    foreach tenant_table in array array[
        'patients',
        'patient_identifiers',
        'encounters',
        'conditions',
        'allergies',
        'observations',
        'medication_statements',
        'clinical_notes',
        'orders',
        'diagnostic_reports',
        'diagnostic_report_results',
        'provenance_events',
        'resource_revisions',
        'care_team_memberships',
        'export_jobs',
        'export_job_files'
    ]
    loop
        execute format('alter table %I enable row level security', tenant_table);
        execute format('alter table %I force row level security', tenant_table);
        execute format(
            $policy$
            create policy %I on %I
            as permissive
            for all
            using (
                nullif(current_setting('ehr.organization_id', true), '') is null
                or organization_id = nullif(current_setting('ehr.organization_id', true), '')::uuid
            )
            with check (
                nullif(current_setting('ehr.organization_id', true), '') is null
                or organization_id = nullif(current_setting('ehr.organization_id', true), '')::uuid
            )
            $policy$,
            tenant_table || '_tenant_isolation',
            tenant_table
        );
    end loop;
end;
$$;
