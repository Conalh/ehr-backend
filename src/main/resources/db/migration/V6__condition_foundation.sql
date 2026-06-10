create table conditions (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references organizations (id),
    patient_id uuid not null,
    encounter_id uuid,
    clinical_status text not null default 'active',
    verification_status text not null default 'confirmed',
    code_concept_id uuid not null references codeable_concepts (id),
    onset_date date,
    abatement_date date,
    recorded_at timestamptz not null default now(),
    version integer not null default 1,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by uuid references users (id),
    updated_by uuid references users (id),
    constraint conditions_patient_same_org_fk foreign key (organization_id, patient_id)
        references patients (organization_id, id),
    constraint conditions_encounter_same_org_fk foreign key (organization_id, encounter_id)
        references encounters (organization_id, id),
    constraint conditions_clinical_status_valid check (
        clinical_status in ('active', 'inactive', 'remission', 'resolved')
    ),
    constraint conditions_verification_status_valid check (
        verification_status in ('provisional', 'confirmed', 'refuted', 'entered-in-error')
    ),
    constraint conditions_dates_valid check (
        onset_date is null
        or abatement_date is null
        or abatement_date >= onset_date
    ),
    constraint conditions_version_positive check (version >= 1),
    constraint conditions_organization_id_id_key unique (organization_id, id)
);

create index conditions_organization_patient_recorded_idx
    on conditions (organization_id, patient_id, recorded_at desc, id);
create index conditions_organization_clinical_status_idx
    on conditions (organization_id, clinical_status, id);
create index conditions_organization_encounter_idx
    on conditions (organization_id, encounter_id)
    where encounter_id is not null;
create index conditions_organization_code_idx
    on conditions (organization_id, code_concept_id);
