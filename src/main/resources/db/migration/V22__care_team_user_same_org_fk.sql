alter table care_team_memberships
    add constraint ctm_user_same_org_fk
    foreign key (organization_id, user_id)
    references memberships (organization_id, user_id);
