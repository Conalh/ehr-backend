create table encounters (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references organizations (id),
    patient_id uuid not null,
    status text not null,
    class_concept_id uuid not null references codeable_concepts (id),
    period_start timestamptz not null,
    period_end timestamptz,
    version integer not null default 1,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by uuid references users (id),
    updated_by uuid references users (id),
    constraint encounters_patient_same_org_fk foreign key (organization_id, patient_id)
        references patients (organization_id, id),
    constraint encounters_status_valid check (
        status in ('planned', 'in-progress', 'finished', 'cancelled', 'entered-in-error')
    ),
    constraint encounters_period_valid check (
        period_end is null or period_end >= period_start
    ),
    constraint encounters_finished_requires_end check (
        status <> 'finished' or period_end is not null
    ),
    constraint encounters_version_positive check (version >= 1),
    constraint encounters_organization_id_id_key unique (organization_id, id)
);

create index encounters_organization_patient_period_idx
    on encounters (organization_id, patient_id, period_start desc, id);
create index encounters_organization_status_idx
    on encounters (organization_id, status, id);
create index encounters_organization_class_idx
    on encounters (organization_id, class_concept_id);
