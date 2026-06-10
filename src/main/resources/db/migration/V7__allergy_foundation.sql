create table allergies (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references organizations (id),
    patient_id uuid not null,
    encounter_id uuid,
    clinical_status text not null default 'active',
    verification_status text not null default 'confirmed',
    code_concept_id uuid not null references codeable_concepts (id),
    category text,
    criticality text,
    onset_date date,
    recorded_at timestamptz not null default now(),
    version integer not null default 1,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by uuid references users (id),
    updated_by uuid references users (id),
    constraint allergies_patient_same_org_fk foreign key (organization_id, patient_id)
        references patients (organization_id, id),
    constraint allergies_encounter_same_org_fk foreign key (organization_id, encounter_id)
        references encounters (organization_id, id),
    constraint allergies_clinical_status_valid check (
        clinical_status in ('active', 'inactive', 'resolved')
    ),
    constraint allergies_verification_status_valid check (
        verification_status in ('unconfirmed', 'confirmed', 'refuted', 'entered-in-error')
    ),
    constraint allergies_category_valid check (
        category is null
        or category in ('food', 'medication', 'environment', 'biologic')
    ),
    constraint allergies_criticality_valid check (
        criticality is null
        or criticality in ('low', 'high', 'unable-to-assess')
    ),
    constraint allergies_version_positive check (version >= 1),
    constraint allergies_organization_id_id_key unique (organization_id, id)
);

create index allergies_organization_patient_recorded_idx
    on allergies (organization_id, patient_id, recorded_at desc, id);
create index allergies_organization_clinical_status_idx
    on allergies (organization_id, clinical_status, id);
create index allergies_organization_encounter_idx
    on allergies (organization_id, encounter_id)
    where encounter_id is not null;
create index allergies_organization_code_idx
    on allergies (organization_id, code_concept_id);
