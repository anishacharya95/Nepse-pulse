-- NEPSE Pulse V23: cloud Watchlist
-- Run this in Supabase SQL Editor.
create table if not exists public.watchlists (
  user_id uuid not null references auth.users(id) on delete cascade,
  symbol text not null,
  created_at timestamptz not null default now(),
  primary key (user_id, symbol)
);

alter table public.watchlists enable row level security;

drop policy if exists "Users can read their own watchlist" on public.watchlists;
create policy "Users can read their own watchlist"
on public.watchlists for select
to authenticated
using (auth.uid() = user_id);

drop policy if exists "Users can add to their own watchlist" on public.watchlists;
create policy "Users can add to their own watchlist"
on public.watchlists for insert
to authenticated
with check (auth.uid() = user_id);

drop policy if exists "Users can delete their own watchlist" on public.watchlists;
create policy "Users can delete their own watchlist"
on public.watchlists for delete
to authenticated
using (auth.uid() = user_id);
