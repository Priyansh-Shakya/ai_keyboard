-- ============================================================
--  AI Keyboard — Supabase Database Setup
--  Run this in your Supabase SQL Editor
--  Dashboard → SQL Editor → New Query → Paste → Run
-- ============================================================

-- 1. Create the notes table
CREATE TABLE IF NOT EXISTS notes (
    id          UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    title       TEXT NOT NULL DEFAULT '',
    content     TEXT NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    updated_at  TIMESTAMPTZ DEFAULT NOW()
);

-- 2. Auto-update the updated_at column on any row change
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_notes_updated_at
    BEFORE UPDATE ON notes
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- 3. Row Level Security — allow all operations with the anon key
--    (for personal use; for multi-user, add user_id column and auth policies)
ALTER TABLE notes ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Allow all operations" ON notes
    FOR ALL
    USING (true)
    WITH CHECK (true);

-- 4. Index for fast ordering
CREATE INDEX IF NOT EXISTS idx_notes_created_at ON notes (created_at DESC);

-- Done! Your notes table is ready.
-- Test it: INSERT INTO notes (title, content) VALUES ('Test', 'Hello from setup!');
