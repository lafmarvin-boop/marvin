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
-- Sessions de tchat en temps réel
-- (séparée de la table sessions qui gère les paiements Stripe)
-- ============================================
CREATE TABLE IF NOT EXISTS chat_sessions (
  id                  UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
  visitor_id          TEXT        NOT NULL,
  status              TEXT        DEFAULT 'waiting',  -- waiting, active, ended, closed
  pre_name            TEXT,
  pre_topic           TEXT,
  session_type        TEXT        DEFAULT 'paid',  -- paid, sub, group
  session_label       TEXT,
  duration_sec        INTEGER     DEFAULT 1800,
  stripe_payment_id   TEXT,
  visitor_ip          TEXT,
  agent_email         TEXT,
  assigned_at         TIMESTAMPTZ,
  response_deadline   TIMESTAMPTZ,
  extension_pending   JSONB,
  transfer_session_id TEXT,
  closed_at           TIMESTAMPTZ,
  created_at          TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_visitor  ON chat_sessions (visitor_id);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_status   ON chat_sessions (status);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_agent    ON chat_sessions (agent_email);
ALTER TABLE chat_sessions ENABLE ROW LEVEL SECURITY;
CREATE POLICY "no_public_read" ON chat_sessions FOR ALL TO anon USING (false);

-- Messages de tchat
CREATE TABLE IF NOT EXISTS chat_messages (
  id           UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
  session_id   UUID        REFERENCES chat_sessions(id) ON DELETE CASCADE,
  content      TEXT        NOT NULL,
  sender_type  TEXT        NOT NULL,  -- visitor, agent, system
  created_at   TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_chat_messages_session ON chat_messages (session_id, created_at);
ALTER TABLE chat_messages ENABLE ROW LEVEL SECURITY;
CREATE POLICY "no_public_read" ON chat_messages FOR ALL TO anon USING (false);

-- Présence des agents
CREATE TABLE IF NOT EXISTS agent_presence (
  agent_email         TEXT        PRIMARY KEY,
  status              TEXT        DEFAULT 'offline',  -- offline, online, busy
  session_token       TEXT,
  current_session_id  UUID,
  connected_since     TIMESTAMPTZ,
  last_seen           TIMESTAMPTZ DEFAULT NOW()
);
ALTER TABLE agent_presence ENABLE ROW LEVEL SECURITY;
CREATE POLICY "no_public_read" ON agent_presence FOR ALL TO anon USING (false);

-- Profils agents (informations personnelles et contractuelles)
CREATE TABLE IF NOT EXISTS agent_profiles (
  email           TEXT        PRIMARY KEY,
  pseudo          TEXT,
  prenom          TEXT,
  nom             TEXT,
  adresse         TEXT,
  siret           TEXT,
  iban            TEXT,
  notify_email    TEXT,
  notify_requests BOOLEAN     DEFAULT FALSE,
  updated_at      TIMESTAMPTZ DEFAULT NOW()
);
ALTER TABLE agent_profiles ENABLE ROW LEVEL SECURITY;
CREATE POLICY "no_public_read" ON agent_profiles FOR ALL TO anon USING (false);

-- Mots de passe agents (hashés + salés)
CREATE TABLE IF NOT EXISTS agent_passwords (
  email          TEXT        PRIMARY KEY,
  password_hash  TEXT        NOT NULL,
  password_salt  TEXT        NOT NULL,
  updated_at     TIMESTAMPTZ DEFAULT NOW()
);
ALTER TABLE agent_passwords ENABLE ROW LEVEL SECURITY;
CREATE POLICY "no_public_read" ON agent_passwords FOR ALL TO anon USING (false);

-- Abonnés pass mensuel
CREATE TABLE IF NOT EXISTS subscribers (
  id                    UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
  email                 TEXT        UNIQUE NOT NULL,
  pseudo                TEXT,
  stripe_customer_id    TEXT,
  stripe_subscription_id TEXT,
  status                TEXT        DEFAULT 'active',  -- active, pending, payment_failed, cancelled
  expires_at            TIMESTAMPTZ,
  cancel_at_period_end  BOOLEAN     DEFAULT FALSE,
  password_hash         TEXT,
  password_salt         TEXT,
  created_at            TIMESTAMPTZ DEFAULT NOW()
);
ALTER TABLE subscribers ENABLE ROW LEVEL SECURITY;
CREATE POLICY "no_public_read" ON subscribers FOR ALL TO anon USING (false);

-- Demandes d'agent (notifications visiteur)
CREATE TABLE IF NOT EXISTS agent_requests (
  id                UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
  email             TEXT        NOT NULL,
  push_subscription TEXT,
  created_at        TIMESTAMPTZ DEFAULT NOW(),
  notified_at       TIMESTAMPTZ
);
ALTER TABLE agent_requests ENABLE ROW LEVEL SECURITY;
CREATE POLICY "no_public_read" ON agent_requests FOR ALL TO anon USING (false);

-- Chat de groupe — accès membres
CREATE TABLE IF NOT EXISTS group_access (
  id                 UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
  room_id            TEXT        NOT NULL,
  pseudo             TEXT        NOT NULL,
  is_agent           BOOLEAN     DEFAULT FALSE,
  free_until         TIMESTAMPTZ,
  paid_until         TIMESTAMPTZ,
  payment_intent_id  TEXT,
  created_at         TIMESTAMPTZ DEFAULT NOW(),
  UNIQUE(room_id, pseudo)
);
CREATE INDEX IF NOT EXISTS idx_group_access_room ON group_access (room_id);
ALTER TABLE group_access ENABLE ROW LEVEL SECURITY;
DO $$ BEGIN CREATE POLICY "no_public_read" ON group_access FOR ALL TO anon USING (false); EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- Chat de groupe — messages
CREATE TABLE IF NOT EXISTS group_messages (
  id          UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
  room_id     TEXT        NOT NULL,
  author      TEXT        NOT NULL,
  content     TEXT        NOT NULL,
  is_private  BOOLEAN     DEFAULT FALSE,
  recipient   TEXT,
  is_question BOOLEAN     DEFAULT FALSE,
  is_agent    BOOLEAN     DEFAULT FALSE,
  created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_group_messages_room ON group_messages (room_id, created_at);
ALTER TABLE group_messages ENABLE ROW LEVEL SECURITY;
DO $$ BEGIN CREATE POLICY "no_public_read" ON group_messages FOR ALL TO anon USING (false); EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- Suggestions utilisateurs
CREATE TABLE IF NOT EXISTS suggestions (
  id          UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
  content     TEXT        NOT NULL,
  payment_id  TEXT,
  created_at  TIMESTAMPTZ DEFAULT NOW()
);
ALTER TABLE suggestions ENABLE ROW LEVEL SECURITY;
DO $$ BEGIN CREATE POLICY "no_public_read" ON suggestions FOR ALL TO anon USING (false); EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- Statistiques de visites (journal IP/géoloc — conservé 30 jours max)
CREATE TABLE IF NOT EXISTS visits (
  id          UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
  visitor_id  TEXT,
  is_new      BOOLEAN     DEFAULT FALSE,
  ip_address  TEXT,
  country     TEXT,
  city        TEXT,
  region      TEXT,
  visited_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_visits_visitor ON visits (visitor_id);
CREATE INDEX IF NOT EXISTS idx_visits_date    ON visits (visited_at);
ALTER TABLE visits ENABLE ROW LEVEL SECURITY;
DO $$ BEGIN CREATE POLICY "no_public_read" ON visits FOR ALL TO anon USING (false); EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- Compteurs globaux du site
CREATE TABLE IF NOT EXISTS site_stats (
  id              INTEGER     PRIMARY KEY DEFAULT 1,
  total_visits    INTEGER     DEFAULT 0,
  unique_visitors INTEGER     DEFAULT 0,
  total_chats     INTEGER     DEFAULT 0,
  updated_at      TIMESTAMPTZ DEFAULT NOW()
);
ALTER TABLE site_stats ENABLE ROW LEVEL SECURITY;
DO $$ BEGIN CREATE POLICY "no_public_read" ON site_stats FOR ALL TO anon USING (false); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
INSERT INTO site_stats (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

-- Chats démarrés (tracking léger pour les statistiques)
CREATE TABLE IF NOT EXISTS chats (
  id          UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
  visitor_id  TEXT,
  formule     TEXT,
  created_at  TIMESTAMPTZ DEFAULT NOW()
);
ALTER TABLE chats ENABLE ROW LEVEL SECURITY;
DO $$ BEGIN CREATE POLICY "no_public_read" ON chats FOR ALL TO anon USING (false); EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- ============================================
-- Migrations (colonnes ajoutées après la création initiale)
-- ============================================
ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS visitor_ip TEXT;
ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS extension_pending JSONB;
ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS transfer_session_id TEXT;
ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS closed_at TIMESTAMPTZ;
ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS response_deadline TIMESTAMPTZ;
ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS pre_topic TEXT;

-- Colonnes sessions (fidélité, attribution agent, signalement lent)
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS agent_name TEXT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS agent_email TEXT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMPTZ;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS visitor_id TEXT;

-- Colonne group_access (traçabilité paiement groupe)
ALTER TABLE group_access ADD COLUMN IF NOT EXISTS payment_intent_id TEXT;

-- ============================================
-- Push Subscriptions (notifications PWA agent)
-- ============================================
CREATE TABLE IF NOT EXISTS push_subscriptions (
  id           UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
  agent_email  TEXT        NOT NULL,
  endpoint     TEXT        NOT NULL UNIQUE,
  subscription JSONB       NOT NULL,
  updated_at   TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_push_subs_email ON push_subscriptions (agent_email);
ALTER TABLE push_subscriptions ENABLE ROW LEVEL SECURITY;
CREATE POLICY "no_public_read" ON push_subscriptions FOR ALL TO anon USING (false);
