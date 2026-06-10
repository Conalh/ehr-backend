create table observations (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references organizations (id),
    patient_id uuid not null,
    encounter_id uuid,
    status text not null default 'final',
    category text not null,
    code_concept_id uuid not null references codeable_concepts (id),
    value_quantity numeric,
    value_quantity_unit text,
    value_concept_id uuid references codeable_concepts (id),
    value_text text,
    effective_at timestamptz not null,
    version integer not null default 1,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by uuid references users (id),
    updated_by uuid references users (id),
    constraint observations_patient_same_org_fk foreign key (organization_id, patient_id)
        references patients (organization_id, id),
    constraint observations_encounter_same_org_fk foreign key (organization_id, encounter_id)
        references encounters (organization_id, id),
    constraint observations_status_valid check (
        status in ('preliminary', 'final', 'amended', 'cancelled', 'entered-in-error')
    ),
    constraint observations_category_valid check (
        category in ('vital-signs', 'laboratory')
    ),
    constraint observations_exactly_one_value check (
        (value_quantity is not null)::int
        + (value_concept_id is not null)::int
        + (value_text is not null)::int = 1
    ),
    constraint observations_quantity_unit_paired check (
        (value_quantity is null) = (value_quantity_unit is null)
    ),
    constraint observations_quantity_unit_not_blank check (
        value_quantity_unit is null or length(trim(value_quantity_unit)) > 0
    ),
    constraint observations_value_text_not_blank check (
        value_text is null or length(trim(value_text)) > 0
    ),
    constraint observations_version_positive check (version >= 1),
    constraint observations_organization_id_id_key unique (organization_id, id)
);

create index observations_organization_patient_category_effective_idx
    on observations (organization_id, patient_id, category, effective_at desc, id);
create index observations_organization_patient_effective_idx
    on observations (organization_id, patient_id, effective_at desc, id);
create index observations_organization_encounter_idx
    on observations (organization_id, encounter_id)
    where encounter_id is not null;
create index observations_organization_code_idx
    on observations (organization_id, code_concept_id);
