-- Patient-scoped references must agree on patient_id, not only organization_id.
-- Earlier same-org foreign keys prevent cross-tenant links; these constraints
-- close same-tenant cross-patient links for encounter/order/report/result edges.

alter table encounters
    add constraint encounters_organization_patient_id_key
    unique (organization_id, patient_id, id);

alter table orders
    add constraint orders_organization_patient_id_key
    unique (organization_id, patient_id, id);

alter table observations
    add constraint observations_organization_patient_id_key
    unique (organization_id, patient_id, id);

alter table diagnostic_reports
    add constraint diagnostic_reports_organization_patient_id_key
    unique (organization_id, patient_id, id);

alter table conditions
    add constraint conditions_encounter_same_patient_fk
    foreign key (organization_id, patient_id, encounter_id)
    references encounters (organization_id, patient_id, id);

alter table allergies
    add constraint allergies_encounter_same_patient_fk
    foreign key (organization_id, patient_id, encounter_id)
    references encounters (organization_id, patient_id, id);

alter table observations
    add constraint observations_encounter_same_patient_fk
    foreign key (organization_id, patient_id, encounter_id)
    references encounters (organization_id, patient_id, id);

alter table medication_statements
    add constraint medication_statements_encounter_same_patient_fk
    foreign key (organization_id, patient_id, encounter_id)
    references encounters (organization_id, patient_id, id);

alter table clinical_notes
    add constraint clinical_notes_encounter_same_patient_fk
    foreign key (organization_id, patient_id, encounter_id)
    references encounters (organization_id, patient_id, id);

alter table orders
    add constraint orders_encounter_same_patient_fk
    foreign key (organization_id, patient_id, encounter_id)
    references encounters (organization_id, patient_id, id);

alter table diagnostic_reports
    add constraint diagnostic_reports_encounter_same_patient_fk
    foreign key (organization_id, patient_id, encounter_id)
    references encounters (organization_id, patient_id, id);

alter table diagnostic_reports
    add constraint diagnostic_reports_order_same_patient_fk
    foreign key (organization_id, patient_id, order_id)
    references orders (organization_id, patient_id, id);

create or replace function enforce_diagnostic_report_result_same_patient()
returns trigger
language plpgsql
as $$
begin
    if not exists (
        select 1
        from diagnostic_reports dr
        join observations o
          on o.organization_id = new.organization_id
         and o.id = new.observation_id
        where dr.organization_id = new.organization_id
          and dr.id = new.diagnostic_report_id
          and dr.patient_id = o.patient_id
    ) then
        raise exception 'diagnostic report result observation must belong to report patient'
            using errcode = '23514';
    end if;
    return new;
end;
$$;

create trigger diagnostic_report_results_same_patient_trg
before insert or update of organization_id, diagnostic_report_id, observation_id
on diagnostic_report_results
for each row
execute function enforce_diagnostic_report_result_same_patient();
