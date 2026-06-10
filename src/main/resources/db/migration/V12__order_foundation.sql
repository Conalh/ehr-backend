create table orders (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references organizations (id),
    patient_id uuid not null,
    encounter_id uuid,
    status text not null default 'active',
    code_concept_id uuid not null references codeable_concepts (id),
    priority text,
    placed_at timestamptz not null default now(),
    version integer not null default 1,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by uuid references users (id),
    updated_by uuid references users (id),
    constraint orders_patient_same_org_fk foreign key (organization_id, patient_id)
        references patients (organization_id, id),
    constraint orders_encounter_same_org_fk foreign key (organization_id, encounter_id)
        references encounters (organization_id, id),
    constraint orders_status_valid check (
        status in ('active', 'on-hold', 'completed', 'revoked', 'entered-in-error')
    ),
    constraint orders_priority_valid check (
        priority is null or priority in ('routine', 'urgent', 'stat')
    ),
    constraint orders_version_positive check (version >= 1),
    constraint orders_organization_id_id_key unique (organization_id, id)
);

create index orders_organization_patient_placed_idx
    on orders (organization_id, patient_id, placed_at desc, id);
create index orders_organization_status_idx
    on orders (organization_id, status, id);
create index orders_organization_encounter_idx
    on orders (organization_id, encounter_id)
    where encounter_id is not null;
create index orders_organization_code_idx
    on orders (organization_id, code_concept_id);
