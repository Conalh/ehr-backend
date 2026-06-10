create table export_jobs (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references organizations (id),
    status text not null default 'pending',
    requested_by uuid references users (id),
    requested_at timestamptz not null default now(),
    started_at timestamptz,
    completed_at timestamptz,
    error_message text,
    version integer not null default 1,
    constraint export_jobs_status_valid check (
        status in ('pending', 'in-progress', 'completed', 'failed')
    ),
    constraint export_jobs_error_not_blank check (
        error_message is null or length(trim(error_message)) > 0
    ),
    constraint export_jobs_version_positive check (version >= 1),
    constraint export_jobs_organization_id_id_key unique (organization_id, id)
);

create index export_jobs_organization_requested_idx
    on export_jobs (organization_id, requested_at desc, id);
create index export_jobs_status_idx
    on export_jobs (status, requested_at);

create table export_job_files (
    id uuid primary key default gen_random_uuid(),
    export_job_id uuid not null,
    organization_id uuid not null,
    resource_type text not null,
    resource_count integer not null,
    storage_path text not null,
    created_at timestamptz not null default now(),
    constraint export_job_files_job_same_org_fk foreign key (organization_id, export_job_id)
        references export_jobs (organization_id, id),
    constraint export_job_files_resource_type_not_blank check (length(trim(resource_type)) > 0),
    constraint export_job_files_count_non_negative check (resource_count >= 0),
    constraint export_job_files_storage_path_not_blank check (length(trim(storage_path)) > 0),
    constraint export_job_files_job_type_key unique (export_job_id, resource_type)
);

create index export_job_files_organization_job_idx
    on export_job_files (organization_id, export_job_id);
