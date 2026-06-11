-- Compartment enforcement rollout flag (design: docs/architecture/compartment-authorization.md, H3).
-- off: relationships are not evaluated; shadow: evaluated and audited, never
-- denied (H2 behavior, the default); enforced: clinical-record access without
-- a treatment relationship is denied (break-glass excepted).
alter table organizations
    add column compartment_enforcement text not null default 'shadow';

alter table organizations
    add constraint organizations_compartment_enforcement_valid check (
        compartment_enforcement in ('off', 'shadow', 'enforced')
    );
