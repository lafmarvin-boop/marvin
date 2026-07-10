const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;
const ADMIN_EMAIL = (process.env.ADMIN_EMAIL || '').toLowerCase();
const ADMIN_PWD   = process.env.ADMIN_PASSWORD;
const crypto = require('crypto');

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type'
};

function hashPassword(password, salt) {
  return crypto.pbkdf2Sync(password, salt, 100000, 64, 'sha512').toString('hex');
}

async function sbGet(path) {
  try {
    const res = await fetch(`${SB_URL}/rest/v1/${path}`, {
      headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` }
    });
    const d = await res.json();
    return Array.isArray(d) ? d : [];
  } catch { return []; }
}

async function buildAgentStats(email) {
  const now = new Date();
  const startOfDay   = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const dow = now.getDay();
  const startOfWeek  = new Date(startOfDay);
  startOfWeek.setDate(startOfDay.getDate() - (dow === 0 ? 6 : dow - 1));
  const startOfMonth = new Date(now.getFullYear(), now.getMonth(), 1);

  const [sessions, chatSessions, profileRows] = await Promise.all([
    sbGet(`sessions?statut=in.(paid,ended)&agent_email=eq.${encodeURIComponent(email)}&select=formule,montant,client_pseudo,started_at,rating,rating_comment&order=started_at.desc&limit=500`),
    sbGet(`chat_sessions?agent_email=eq.${encodeURIComponent(email)}&rating=not.is.null&select=session_label,pre_name,assigned_at,rating,rating_comment&order=assigned_at.desc&limit=200`),
    sbGet(`agent_profiles?email=eq.${encodeURIComponent(email)}&select=*&limit=1`)
  ]);

  const profile = profileRows[0] || null;

  // Sessions payantes (revenus + comptage)
  function periodRows(from) {
    return sessions.filter(s => s.started_at && new Date(s.started_at) >= from);
  }
  function periodStats(rows) {
    return {
      sessions: rows.length,
      revenue: Math.round(rows.reduce((s, r) => s + (r.montant || 0), 0) * 100) / 100
    };
  }

  const periods = {
    today:   periodStats(periodRows(startOfDay)),
    week:    periodStats(periodRows(startOfWeek)),
    month:   periodStats(periodRows(startOfMonth)),
    allTime: periodStats(sessions)
  };

  const byPlan = {};
  sessions.forEach(s => {
    const k = s.formule || 'Autre';
    if (!byPlan[k]) byPlan[k] = { count: 0, revenue: 0 };
    byPlan[k].count++;
    byPlan[k].revenue += s.montant || 0;
  });

  // Notes : fusionner sessions payantes + sessions chat (gratuites ou payantes)
  const chatReviews = chatSessions.map(s => ({
    pseudo: s.pre_name,
    formule: s.session_label,
    montant: 0,
    started_at: s.assigned_at,
    rating: s.rating,
    rating_comment: s.rating_comment
  }));

  // Dédupliquer : les sessions payantes ont leur note dans `sessions`, pas dans chatSessions
  const paidRated = sessions.filter(s => s.rating);
  const allRated = [...paidRated, ...chatReviews];
  const avgRating = allRated.length > 0
    ? Math.round((allRated.reduce((s, r) => s + r.rating, 0) / allRated.length) * 10) / 10
    : null;

  const allSessions = [
    ...sessions.map(s => ({ pseudo: s.client_pseudo, formule: s.formule, montant: s.montant, started_at: s.started_at, rating: s.rating, rating_comment: s.rating_comment })),
    ...chatReviews
  ].sort((a, b) => new Date(b.started_at) - new Date(a.started_at));

  return {
    role: 'agent',
    needs_password_setup: false,
    profile,
    periods,
    byPlan,
    avgRating,
    ratingCount: allRated.length,
    sessions: allSessions
  };
}

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Bad Request' }; }

  const email = (body.email || '').toLowerCase().trim();
  const password = body.password || '';
  const action = body.action || 'login';

  if (!email || !email.includes('@'))
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Email invalide' }) };

  // ── Sauvegarder le profil ──
  if (action === 'save_profile') {
    const isAdminSave = ADMIN_EMAIL && email === ADMIN_EMAIL && ADMIN_PWD && password === ADMIN_PWD;
    if (!isAdminSave) {
      const pwdRows = await sbGet(`agent_passwords?email=eq.${encodeURIComponent(email)}&select=password_hash,password_salt&limit=1`);
      if (!pwdRows.length) return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Non authentifié' }) };
      const hash = hashPassword(password, pwdRows[0].password_salt);
      if (hash !== pwdRows[0].password_hash) return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Non authentifié' }) };
    }

    const { pseudo, nom, prenom, adresse, code_postal, ville, siret, iban, notify_email, notify_requests } = body.profile || {};
    await fetch(`${SB_URL}/rest/v1/agent_profiles`, {
      method: 'POST',
      headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, 'Content-Type': 'application/json', Prefer: 'resolution=merge-duplicates' },
      body: JSON.stringify({ email, pseudo, nom, prenom, adresse, code_postal, ville, siret, iban, notify_email: notify_email || null, notify_requests: !!notify_requests, updated_at: new Date().toISOString() })
    });
    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };
  }

  // ── Définir le mot de passe (première connexion) ──
  if (action === 'set_password') {
    if (!password || password.length < 8)
      return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Mot de passe trop court (8 caractères min.)' }) };
    const salt = crypto.randomBytes(32).toString('hex');
    const hash = hashPassword(password, salt);
    await fetch(`${SB_URL}/rest/v1/agent_passwords`, {
      method: 'POST',
      headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, 'Content-Type': 'application/json', Prefer: 'resolution=merge-duplicates' },
      body: JSON.stringify({ email, password_hash: hash, password_salt: salt })
    });
    const stats = await buildAgentStats(email);
    return { statusCode: 200, headers: CORS, body: JSON.stringify(stats) };
  }

  // ── Connexion ──
  // Bypass admin : accepte directement les identifiants admin
  const isAdmin = ADMIN_EMAIL && email === ADMIN_EMAIL && ADMIN_PWD && password === ADMIN_PWD;

  if (!isAdmin) {
    const pwdRows = await sbGet(`agent_passwords?email=eq.${encodeURIComponent(email)}&select=password_hash,password_salt&limit=1`);

    if (!pwdRows.length)
      return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Compte non trouvé. Contactez l\'administrateur.' }) };

    if (!password)
      return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Mot de passe requis' }) };

    const hash = hashPassword(password, pwdRows[0].password_salt);
    if (hash !== pwdRows[0].password_hash)
      return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Mot de passe incorrect' }) };
  }

  const stats = await buildAgentStats(email);
  return { statusCode: 200, headers: CORS, body: JSON.stringify(stats) };
};
