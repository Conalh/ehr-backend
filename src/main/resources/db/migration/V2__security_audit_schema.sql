create extension if not exists pgcrypto;

create table organizations (
    id uuid primary key default gen_random_uuid(),
    slug text not null,
    display_name text not null,
    status text not null default 'active',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint organizations_slug_not_blank check (length(trim(slug)) > 0),
    constraint organizations_display_name_not_blank check (length(trim(display_name)) > 0),
    constraint organizations_status_valid check (status in ('active', 'suspended', 'inactive')),
    constraint organizations_slug_lowercase check (slug = lower(slug))
);

create unique index organizations_slug_key on organizations (slug);

create table users (
    id uuid primary key default gen_random_uuid(),
    external_subject text not null,
    email text not null,
    display_name text not null,
    status text not null default 'active',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint users_external_subject_not_blank check (length(trim(external_subject)) > 0),
    constraint users_email_not_blank check (length(trim(email)) > 0),
    constraint users_display_name_not_blank check (length(trim(display_name)) > 0),
    constraint users_status_valid check (status in ('active', 'inactive', 'locked'))
);

create unique index users_external_subject_key on users (external_subject);
create unique index users_email_lower_key on users (lower(email));

create table practitioners (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references users (id),
    npi text,
    display_name text not null,
    status text not null default 'active',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint practitioners_display_name_not_blank check (length(trim(display_name)) > 0),
    constraint practitioners_npi_not_blank check (npi is null or length(trim(npi)) > 0),
    constraint practitioners_status_valid check (status in ('active', 'inactive'))
);

alter table practitioners add constraint practitioners_id_user_id_key unique (id, user_id);
create unique index practitioners_user_id_key on practitioners (user_id);
create unique index practitioners_npi_key on practitioners (npi) where npi is not null;

create table oauth_clients (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid references organizations (id),
    client_identifier text not null,
    display_name text not null,
    status text not null default 'active',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint oauth_clients_identifier_not_blank check (length(trim(client_identifier)) > 0),
    constraint oauth_clients_display_name_not_blank check (length(trim(display_name)) > 0),
    constraint oauth_clients_status_valid check (status in ('active', 'inactive', 'revoked'))
);

create unique index oauth_clients_identifier_key on oauth_clients (client_identifier);
create index oauth_clients_organization_id_idx on oauth_clients (organization_id);

create table memberships (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references organizations (id),
    user_id uuid not null references users (id),
    practitioner_id uuid,
    status text not null default 'active',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint memberships_status_valid check (status in ('active', 'inactive', 'suspended')),
    constraint memberships_practitioner_user_fk foreign key (practitioner_id, user_id)
        references practitioners (id, user_id)
);

create unique index memberships_organization_user_key on memberships (organization_id, user_id);
create index memberships_user_id_idx on memberships (user_id);
create index memberships_practitioner_id_idx on memberships (practitioner_id);

create table membership_roles (
    membership_id uuid not null references memberships (id) on delete cascade,
    role text not null,
    created_at timestamptz not null default now(),
    primary key (membership_id, role),
    constraint membership_roles_role_valid check (
        role in (
            'SYSTEM_ADMIN',
            'ORG_ADMIN',
            'CLINICIAN',
            'STAFF',
            'PATIENT',
            'SYSTEM_APP'
        )
    )
);

create table audit_events (
    id uuid primary key default gen_random_uuid(),
    occurred_at timestamptz not null default now(),
    organization_id uuid references organizations (id),
    subject_user_id uuid references users (id),
    client_id uuid references oauth_clients (id),
    patient_id uuid,
    resource_type text not null,
    resource_id uuid,
    operation text not null,
    outcome text not null,
    purpose_of_use text,
    policy_version text,
    policy_reason_code text,
    correlation_id text,
    source_ip inet,
    user_agent text,
    metadata jsonb not null default '{}'::jsonb,
    constraint audit_events_resource_type_not_blank check (length(trim(resource_type)) > 0),
    constraint audit_events_operation_valid check (
        operation in (
            'READ',
            'SEARCH',
            'CREATE',
            'UPDATE',
            'DELETE',
            'EXPORT',
            'AUTHORIZATION_DENIED',
            'SYSTEM'
        )
    ),
    constraint audit_events_outcome_valid check (outcome in ('SUCCESS', 'DENIED', 'FAILURE')),
    constraint audit_events_correlation_id_length check (correlation_id is null or length(correlation_id) <= 128),
    constraint audit_events_metadata_object check (jsonb_typeof(metadata) = 'object')
);

create index audit_events_organization_time_idx on audit_events (organization_id, occurred_at desc);
create index audit_events_subject_time_idx on audit_events (subject_user_id, occurred_at desc);
create index audit_events_patient_time_idx on audit_events (patient_id, occurred_at desc);
create index audit_events_resource_idx on audit_events (resource_type, resource_id);

create table audit_event_resources (
    id uuid primary key default gen_random_uuid(),
    audit_event_id uuid not null references audit_events (id) on delete cascade,
    organization_id uuid references organizations (id),
    patient_id uuid,
    resource_type text not null,
    resource_id uuid,
    action text not null,
    created_at timestamptz not null default now(),
    constraint audit_event_resources_resource_type_not_blank check (length(trim(resource_type)) > 0),
    constraint audit_event_resources_action_valid check (action in ('REQUESTED', 'RETURNED', 'MUTATED', 'EXPORTED'))
);

create index audit_event_resources_event_id_idx on audit_event_resources (audit_event_id);
create index audit_event_resources_patient_idx on audit_event_resources (patient_id, resource_type, resource_id);

create function prevent_audit_table_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'audit tables are append-only';
end;
$$;

create trigger audit_events_append_only_update
before update or delete on audit_events
for each row execute function prevent_audit_table_mutation();

create trigger audit_event_resources_append_only_update
before update or delete on audit_event_resources
for each row execute function prevent_audit_table_mutation();
