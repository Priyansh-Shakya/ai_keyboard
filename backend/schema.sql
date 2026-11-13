-- Run in Supabase: SQL Editor -> New query -> Run. Safe to re-run.
-- Notes are now owned by a user. The backend (service role) enforces ownership;
-- RLS is ON with no policies, so the public anon key cannot read anything.

create table if not exists notes (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references auth.users(id) on delete cascade,
    title       text not null default '',
    content     text not null default '',
    created_at  timestamptz default now(),
    updated_at  timestamptz default now()
);

-- If you ran the OLD script (no user_id), upgrade that table:
alter table notes add column if not exists user_id uuid references auth.users(id) on delete cascade;
drop policy if exists "Allow all operations" on notes;   -- old policy made notes public!

alter table notes enable row level security;
create index if not exists idx_notes_user_created on notes (user_id, created_at desc);

create or replace function update_updated_at_column() returns trigger as $$
begin new.updated_at = now(); return new; end;
$$ language plpgsql;

drop trigger if exists update_notes_updated_at on notes;
create trigger update_notes_updated_at before update on notes
for each row execute function update_updated_at_column();
