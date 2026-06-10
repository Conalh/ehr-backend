create table diagnostic_reports (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references organizations (id),
    patient_id uuid not null,
    encounter_id uuid,
    order_id uuid not null,
    status text not null default 'final',
    code_concept_id uuid not null references codeable_concepts (id),
    conclusion_text text,
    issued_at timestamptz not null default now(),
    version integer not null default 1,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by uuid references users (id),
    updated_by uuid references users (id),
    constraint diagnostic_reports_patient_same_org_fk foreign key (organization_id, patient_id)
        references patients (organization_id, id),
    constraint diagnostic_reports_encounter_same_org_fk foreign key (organization_id, encounter_id)
        references encounters (organization_id, id),
    constraint diagnostic_reports_order_same_org_fk foreign key (organization_id, order_id)
        references orders (organization_id, id),
    constraint diagnostic_reports_status_valid check (
        status in ('partial', 'final', 'amended', 'entered-in-error')
    ),
    constraint diagnostic_reports_conclusion_not_blank check (
        conclusion_text is null or length(trim(conclusion_text)) > 0
    ),
    constraint diagnostic_reports_version_positive check (version >= 1),
    constraint diagnostic_reports_organization_id_id_key unique (organization_id, id)
);

create index diagnostic_reports_organization_patient_issued_idx
    on diagnostic_reports (organization_id, patient_id, issued_at desc, id);
create index diagnostic_reports_organization_order_idx
    on diagnostic_reports (organization_id, order_id);
create index diagnostic_reports_organization_status_idx
    on diagnostic_reports (organization_id, status, id);

create table diagnostic_report_results (
    diagnostic_report_id uuid not null,
    organization_id uuid not null,
    observation_id uuid not null,
    ordinal integer not null,
    created_at timestamptz not null default now(),
    primary key (diagnostic_report_id, observation_id),
    constraint diagnostic_report_results_report_same_org_fk foreign key (organization_id, diagnostic_report_id)
        references diagnostic_reports (organization_id, id),
    constraint diagnostic_report_results_observation_same_org_fk foreign key (organization_id, observation_id)
        references observations (organization_id, id),
    constraint diagnostic_report_results_ordinal_key unique (diagnostic_report_id, ordinal),
    constraint diagnostic_report_results_ordinal_non_negative check (ordinal >= 0)
);

create index diagnostic_report_results_organization_observation_idx
    on diagnostic_report_results (organization_id, observation_id);
