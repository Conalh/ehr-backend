create table care_team_memberships (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references organizations (id),
    patient_id uuid not null,
    user_id uuid not null references users (id),
    role text not null,
    origin text not null,
    period_start timestamptz not null default now(),
    period_end timestamptz,
    created_at timestamptz not null default now(),
    created_by uuid references users (id),
    constraint ctm_patient_same_org_fk foreign key (organization_id, patient_id)
        references patients (organization_id, id),
    constraint ctm_role_valid check (role in ('attending', 'covering', 'care-team')),
    constraint ctm_origin_valid check (origin in ('explicit', 'encounter-derived')),
    constraint ctm_period_valid check (period_end is null or period_end > period_start)
);

-- One active membership per (org, patient, user, role); ended rows are history
-- and re-establishing after an end is allowed.
create unique index ctm_active_unique
    on care_team_memberships (organization_id, patient_id, user_id, role)
    where period_end is null;

-- The lookup every future policy decision performs.
create index ctm_org_user_patient_idx
    on care_team_memberships (organization_id, user_id, patient_id)
    where period_end is null;

create index ctm_org_patient_idx
    on care_team_memberships (organization_id, patient_id)
    where period_end is null;
