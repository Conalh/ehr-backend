-- Shadow-mode compartment evaluation (design: docs/architecture/compartment-authorization.md, H2).
-- Records what relationship satisfied (or would have satisfied) the compartment
-- requirement for a policy decision. Nothing enforces yet.
alter table audit_events
    add column relationship_basis text;

alter table audit_events
    add constraint audit_events_relationship_basis_valid check (
        relationship_basis is null
        or relationship_basis in ('care-team-member', 'encounter-derived', 'break-glass')
    );
