-- User-app authorization flows (design: docs/architecture/authorization-server-integration.md, AS2).
-- Authorization-code clients redirect only to URIs registered up front.
alter table oauth_clients
    add column redirect_uris text not null default '';
