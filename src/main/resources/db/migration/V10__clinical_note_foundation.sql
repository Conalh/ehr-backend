create table clinical_notes (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references organizations (id),
    patient_id uuid not null,
    encounter_id uuid not null,
    status text not null default 'current',
    type_concept_id uuid not null references codeable_concepts (id),
    title text not null,
    content_text text not null,
    authored_at timestamptz not null default now(),
    version integer not null default 1,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by uuid references users (id),
    updated_by uuid references users (id),
    constraint clinical_notes_patient_same_org_fk foreign key (organization_id, patient_id)
        references patients (organization_id, id),
    constraint clinical_notes_encounter_same_org_fk foreign key (organization_id, encounter_id)
        references encounters (organization_id, id),
    constraint clinical_notes_status_valid check (
        status in ('current', 'superseded', 'entered-in-error')
    ),
    constraint clinical_notes_title_not_blank check (length(trim(title)) > 0),
    constraint clinical_notes_content_not_blank check (length(trim(content_text)) > 0),
    constraint clinical_notes_version_positive check (version >= 1),
    constraint clinical_notes_organization_id_id_key unique (organization_id, id)
);

create index clinical_notes_organization_patient_authored_idx
    on clinical_notes (organization_id, patient_id, authored_at desc, id);
create index clinical_notes_organization_encounter_idx
    on clinical_notes (organization_id, encounter_id);
create index clinical_notes_organization_status_idx
    on clinical_notes (organization_id, status, id);
