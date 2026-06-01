-- ============================================
-- PARLONS — Schéma Supabase
-- À exécuter dans : Supabase Dashboard → SQL Editor
-- ============================================

-- Sessions de tchat
CREATE TABLE IF NOT EXISTS sessions (
  id               UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
  client_pseudo    TEXT        NOT NULL,
  formule          TEXT        NOT NULL,  -- '10min', '30min', '1h', 'sub', 'group'
  montant          DECIMAL(6,2),
  stripe_payment_id TEXT       UNIQUE,
  statut           TEXT        DEFAULT 'pending',  -- pending, paid, active, ended, failed, refunded
  token            TEXT        UNIQUE,
  started_at       TIMESTAMPTZ,
  ends_at          TIMESTAMPTZ,
  agent_first_reply TIMESTAMPTZ,
  rating           INTEGER     CHECK (rating BETWEEN 1 AND 5),
  rating_comment   TEXT,
  created_at       TIMESTAMPTZ DEFAULT NOW()
);

-- Signalements agent lent
CREATE TABLE IF NOT EXISTS signalements (
  id                    UUID      DEFAULT gen_random_uuid() PRIMARY KEY,
  stripe_payment_id     TEXT,
  client_pseudo         TEXT,
  formule               TEXT,
  delai_attente         INTEGER,  -- en secondes
  remboursement_effectue BOOLEAN  DEFAULT FALSE,
  created_at            TIMESTAMPTZ DEFAULT NOW()
);

-- Clients fidélité
CREATE TABLE IF NOT EXISTS clients (
  id          UUID    DEFAULT gen_random_uuid() PRIMARY KEY,
  pseudo      TEXT    UNIQUE NOT NULL,
  email       TEXT,
  nb_sessions INTEGER DEFAULT 0,
  statut      TEXT    DEFAULT 'standard',  -- standard, abonne
  created_at  TIMESTAMPTZ DEFAULT NOW()
);

-- Agents (écoutants)
CREATE TABLE IF NOT EXISTS agents (
  id             UUID    DEFAULT gen_random_uuid() PRIMARY KEY,
  pseudo         TEXT    UNIQUE NOT NULL,
  nom            TEXT,
  email          TEXT,
  siret          TEXT,
  sessions_mois  INTEGER DEFAULT 0,
  gains_total    DECIMAL(8,2) DEFAULT 0,
  actif          BOOLEAN DEFAULT TRUE,
  created_at     TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================
-- Sécurité RLS (Row Level Security)
-- Les fonctions utilisent la clé SERVICE qui bypass le RLS
-- ============================================
ALTER TABLE sessions     ENABLE ROW LEVEL SECURITY;
ALTER TABLE signalements ENABLE ROW LEVEL SECURITY;
ALTER TABLE clients      ENABLE ROW LEVEL SECURITY;
ALTER TABLE agents       ENABLE ROW LEVEL SECURITY;

-- Politique : personne ne peut lire via clé anon
-- (tout passe par les Netlify Functions avec SERVICE_KEY)
CREATE POLICY "no_public_read" ON sessions     FOR ALL TO anon USING (false);
CREATE POLICY "no_public_read" ON signalements FOR ALL TO anon USING (false);
CREATE POLICY "no_public_read" ON clients      FOR ALL TO anon USING (false);
CREATE POLICY "no_public_read" ON agents       FOR ALL TO anon USING (false);

-- ============================================
-- Index pour les requêtes fréquentes
-- ============================================
CREATE INDEX IF NOT EXISTS idx_sessions_payment   ON sessions (stripe_payment_id);
CREATE INDEX IF NOT EXISTS idx_sessions_token     ON sessions (token);
CREATE INDEX IF NOT EXISTS idx_sessions_statut    ON sessions (statut);
CREATE INDEX IF NOT EXISTS idx_signalements_payment ON signalements (stripe_payment_id);

-- ============================================
-- Migrations
-- ============================================
ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS visitor_ip TEXT;
ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS extension_pending JSONB;
ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS transfer_session_id TEXT;
ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS closed_at TIMESTAMPTZ;
