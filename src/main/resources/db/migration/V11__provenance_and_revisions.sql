create table provenance_events (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references organizations (id),
    patient_id uuid not null,
    target_resource_type text not null,
    target_resource_id uuid not null,
    target_version integer not null,
    activity text not null,
    agent_user_id uuid references users (id),
    agent_client_id uuid references oauth_clients (id),
    recorded_at timestamptz not null default now(),
    source_type text not null,
    source_reference text,
    prior_resource_version integer,
    synthetic_generation_run_id uuid,
    constraint provenance_events_resource_type_not_blank check (length(trim(target_resource_type)) > 0),
    constraint provenance_events_target_version_positive check (target_version >= 1),
    constraint provenance_events_activity_valid check (
        activity in ('created', 'updated', 'corrected', 'amended', 'addended', 'entered-in-error')
    ),
    constraint provenance_events_source_type_valid check (
        source_type in (
            'clinician-authored',
            'staff-recorded',
            'system-imported',
            'transformed',
            'synthetic-generated',
            'corrected',
            'amended',
            'addended'
        )
    ),
    constraint provenance_events_prior_version_positive check (
        prior_resource_version is null or prior_resource_version >= 1
    )
);

create index provenance_events_organization_target_idx
    on provenance_events (organization_id, target_resource_type, target_resource_id, target_version);
create index provenance_events_organization_patient_time_idx
    on provenance_events (organization_id, patient_id, recorded_at desc);

create table resource_revisions (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references organizations (id),
    patient_id uuid not null,
    resource_type text not null,
    resource_id uuid not null,
    version integer not null,
    snapshot jsonb not null,
    recorded_at timestamptz not null default now(),
    recorded_by uuid references users (id),
    constraint resource_revisions_resource_type_not_blank check (length(trim(resource_type)) > 0),
    constraint resource_revisions_version_positive check (version >= 1),
    constraint resource_revisions_snapshot_object check (jsonb_typeof(snapshot) = 'object'),
    constraint resource_revisions_resource_version_key
        unique (organization_id, resource_type, resource_id, version)
);

create index resource_revisions_organization_patient_time_idx
    on resource_revisions (organization_id, patient_id, recorded_at desc);

create trigger provenance_events_append_only
before update or delete on provenance_events
for each row execute function prevent_audit_table_mutation();

create trigger resource_revisions_append_only
before update or delete on resource_revisions
for each row execute function prevent_audit_table_mutation();
