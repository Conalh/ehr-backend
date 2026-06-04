create table runtime_markers (
    name text primary key,
    created_at timestamptz not null default now()
);

insert into runtime_markers (name)
values ('runtime-skeleton');
