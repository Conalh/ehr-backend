create table patients (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references organizations (id),
    status text not null default 'active',
    given_name text not null,
    family_name text not null,
    birth_date date,
    administrative_gender text,
    version integer not null default 1,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by uuid references users (id),
    updated_by uuid references users (id),
    constraint patients_status_valid check (status in ('active', 'inactive', 'entered-in-error')),
    constraint patients_given_name_not_blank check (length(trim(given_name)) > 0),
    constraint patients_family_name_not_blank check (length(trim(family_name)) > 0),
    constraint patients_administrative_gender_valid check (
        administrative_gender is null
        or administrative_gender in ('male', 'female', 'other', 'unknown')
    ),
    constraint patients_version_positive check (version >= 1),
    constraint patients_organization_id_id_key unique (organization_id, id)
);

create index patients_organization_status_idx on patients (organization_id, status, id);
create index patients_organization_family_given_idx on patients (organization_id, family_name, given_name, id);

create table patient_identifiers (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references organizations (id),
    patient_id uuid not null,
    system text not null,
    value text not null,
    use text,
    type_concept_id uuid references codeable_concepts (id),
    assigner_text text,
    period_start date,
    period_end date,
    created_at timestamptz not null default now(),
    constraint patient_identifiers_patient_same_org_fk foreign key (organization_id, patient_id)
        references patients (organization_id, id),
    constraint patient_identifiers_system_not_blank check (length(trim(system)) > 0),
    constraint patient_identifiers_value_not_blank check (length(trim(value)) > 0),
    constraint patient_identifiers_use_valid check (
        use is null
        or use in ('usual', 'official', 'temp', 'secondary', 'old')
    ),
    constraint patient_identifiers_assigner_text_not_blank check (
        assigner_text is null or length(trim(assigner_text)) > 0
    ),
    constraint patient_identifiers_period_valid check (
        period_start is null
        or period_end is null
        or period_end >= period_start
    )
);

create unique index patient_identifiers_organization_system_value_key
    on patient_identifiers (organization_id, system, value);
create index patient_identifiers_organization_patient_idx
    on patient_identifiers (organization_id, patient_id, id);
create index patient_identifiers_organization_type_idx
    on patient_identifiers (organization_id, type_concept_id)
    where type_concept_id is not null;
