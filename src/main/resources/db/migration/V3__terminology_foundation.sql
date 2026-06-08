create table code_systems (
    id uuid primary key default gen_random_uuid(),
    canonical_uri text not null,
    name text not null,
    publisher text,
    license_note text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint code_systems_canonical_uri_not_blank check (length(trim(canonical_uri)) > 0),
    constraint code_systems_name_not_blank check (length(trim(name)) > 0),
    constraint code_systems_publisher_not_blank check (publisher is null or length(trim(publisher)) > 0),
    constraint code_systems_license_note_not_blank check (license_note is null or length(trim(license_note)) > 0),
    constraint code_systems_canonical_uri_key unique (canonical_uri)
);

create table code_system_versions (
    id uuid primary key default gen_random_uuid(),
    code_system_id uuid not null references code_systems (id),
    version text not null,
    release_date date,
    created_at timestamptz not null default now(),
    constraint code_system_versions_version_not_blank check (length(trim(version)) > 0),
    constraint code_system_versions_system_version_key unique (code_system_id, version)
);

create table codings (
    id uuid primary key default gen_random_uuid(),
    code_system_version_id uuid references code_system_versions (id),
    system text not null,
    version text,
    code text not null,
    display text,
    user_selected boolean not null default false,
    created_at timestamptz not null default now(),
    constraint codings_system_not_blank check (length(trim(system)) > 0),
    constraint codings_version_not_blank check (version is null or length(trim(version)) > 0),
    constraint codings_code_not_blank check (length(trim(code)) > 0),
    constraint codings_display_not_blank check (display is null or length(trim(display)) > 0)
);

create unique index codings_system_code_version_key on codings (system, code, coalesce(version, ''));
create index codings_code_system_version_id_idx on codings (code_system_version_id);

create table codeable_concepts (
    id uuid primary key default gen_random_uuid(),
    text text,
    primary_coding_id uuid not null references codings (id),
    binding_context text,
    created_at timestamptz not null default now(),
    constraint codeable_concepts_text_not_blank check (text is null or length(trim(text)) > 0),
    constraint codeable_concepts_binding_context_not_blank check (
        binding_context is null or length(trim(binding_context)) > 0
    )
);

create table codeable_concept_codings (
    codeable_concept_id uuid not null references codeable_concepts (id) on delete cascade,
    coding_id uuid not null references codings (id),
    ordinal integer not null,
    created_at timestamptz not null default now(),
    primary key (codeable_concept_id, coding_id),
    constraint codeable_concept_codings_ordinal_nonnegative check (ordinal >= 0),
    constraint codeable_concept_codings_concept_ordinal_key unique (codeable_concept_id, ordinal)
);

create index codeable_concept_codings_coding_id_idx on codeable_concept_codings (coding_id);

create table value_sets (
    id uuid primary key default gen_random_uuid(),
    canonical_url text not null,
    oid text,
    name text not null,
    source text,
    profile_context text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint value_sets_canonical_url_not_blank check (length(trim(canonical_url)) > 0),
    constraint value_sets_oid_not_blank check (oid is null or length(trim(oid)) > 0),
    constraint value_sets_name_not_blank check (length(trim(name)) > 0),
    constraint value_sets_source_not_blank check (source is null or length(trim(source)) > 0),
    constraint value_sets_profile_context_not_blank check (
        profile_context is null or length(trim(profile_context)) > 0
    ),
    constraint value_sets_canonical_url_key unique (canonical_url)
);

create unique index value_sets_oid_key on value_sets (oid) where oid is not null;

create table value_set_versions (
    id uuid primary key default gen_random_uuid(),
    value_set_id uuid not null references value_sets (id),
    version text not null,
    effective_date date,
    created_at timestamptz not null default now(),
    constraint value_set_versions_version_not_blank check (length(trim(version)) > 0),
    constraint value_set_versions_value_set_version_key unique (value_set_id, version)
);

create table value_set_members (
    id uuid primary key default gen_random_uuid(),
    value_set_version_id uuid not null references value_set_versions (id),
    system text not null,
    code text not null,
    display text,
    code_system_version text,
    created_at timestamptz not null default now(),
    constraint value_set_members_system_not_blank check (length(trim(system)) > 0),
    constraint value_set_members_code_not_blank check (length(trim(code)) > 0),
    constraint value_set_members_display_not_blank check (display is null or length(trim(display)) > 0),
    constraint value_set_members_code_system_version_not_blank check (
        code_system_version is null or length(trim(code_system_version)) > 0
    )
);

create unique index value_set_members_version_system_code_key
    on value_set_members (value_set_version_id, system, code, coalesce(code_system_version, ''));

create table terminology_import_runs (
    id uuid primary key default gen_random_uuid(),
    source text not null,
    source_version text,
    status text not null default 'created',
    started_at timestamptz not null default now(),
    completed_at timestamptz,
    metadata jsonb not null default '{}'::jsonb,
    constraint terminology_import_runs_source_not_blank check (length(trim(source)) > 0),
    constraint terminology_import_runs_source_version_not_blank check (
        source_version is null or length(trim(source_version)) > 0
    ),
    constraint terminology_import_runs_status_valid check (status in ('created', 'running', 'completed', 'failed')),
    constraint terminology_import_runs_metadata_object check (jsonb_typeof(metadata) = 'object')
);
