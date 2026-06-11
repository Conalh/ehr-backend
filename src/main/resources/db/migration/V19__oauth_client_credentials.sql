-- Backend-services authorization (design: docs/architecture/authorization-server-integration.md, AS1).
-- Confidential and system clients authenticate to the embedded authorization
-- server with a server-generated secret; only its Argon2id hash is stored.
alter table oauth_clients
    add column client_type text not null default 'public',
    add column secret_hash text,
    add column granted_scopes text not null default '';

alter table oauth_clients
    add constraint oauth_clients_type_valid check (
        client_type in ('public', 'confidential', 'system')
    ),
    add constraint oauth_clients_secret_presence check (
        (client_type = 'public' and secret_hash is null)
        or (client_type <> 'public' and secret_hash is not null)
    );
