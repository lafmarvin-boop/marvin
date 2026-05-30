const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;
const ADMIN_PWD = process.env.ADMIN_PASSWORD;
const ADMIN_EMAIL = (process.env.ADMIN_EMAIL || '').toLowerCase();
const CRISP_WEBSITE_ID = process.env.CRISP_WEBSITE_ID || 'fd20e23d-059a-4552-9aae-6df05f653d02';
const CRISP_API_ID = process.env.CRISP_API_IDENTIFIER;
const CRISP_API_KEY = process.env.CRISP_API_KEY || process.env.CRISP_API_TOKEN;
const crypto = require('crypto');

async function getCrispOperators() {
  if (!CRISP_API_ID || !CRISP_API_KEY) return [];
  try {
    const auth = Buffer.from(`${CRISP_API_ID}:${CRISP_API_KEY}`).toString('base64');
    const res = await fetch(
      `https://api.crisp.chat/v1/website/${CRISP_WEBSITE_ID}/operators/list`,
      { headers: { Authorization: `Basic ${auth}`, 'X-Crisp-Tier': 'plugin' } }
    );
    if (!res.ok) return [];
    const json = await res.json();
    return Array.isArray(json.data) ? json.data : [];
  } catch { return []; }
}

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

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Bad Request' }; }

  const email = (body.email || '').toLowerCase().trim();
  const password = body.password || '';

  // ── Admin check ──
  if (ADMIN_EMAIL && email === ADMIN_EMAIL) {
    if (!ADMIN_PWD || password !== ADMIN_PWD)
      return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Identifiants incorrects' }) };

    const now = new Date();
    const startOfYear = new Date(now.getFullYear(), 0, 1);

    const [sessions, subscribers, groupMsgs, groupAccess, suggestions, siteStatsRows, visitsRows, chatsRows, crispOperators, ipLogs] = await Promise.all([
      sbGet('sessions?statut=eq.paid&select=formule,montant,client_pseudo,started_at,stripe_payment_id,agent_name,agent_email,resolved_at,rating,rating_comment&order=started_at.desc&limit=500'),
      sbGet('subscribers?select=*&order=created_at.desc&limit=200'),
      sbGet('group_messages?select=room_id,created_at,author&order=created_at.desc&limit=2000'),
      sbGet('group_access?select=room_id,pseudo,free_until,paid_until,is_agent&order=created_at.desc&limit=300'),
      sbGet('suggestions?select=*&order=created_at.desc&limit=100'),
      sbGet('site_stats?id=eq.1&select=total_visits,unique_visitors,total_chats'),
      sbGet(`visits?visited_at=gte.${encodeURIComponent(startOfYear.toISOString())}&select=visitor_id,visited_at&order=visited_at.desc&limit=50000`),
      sbGet(`chats?started_at=gte.${encodeURIComponent(startOfYear.toISOString())}&select=started_at&order=started_at.desc&limit=10000`),
      getCrispOperators(),
      sbGet('visits?select=ip_address,country,visited_at,visitor_id,is_new&order=visited_at.desc&limit=200')
    ]);
    const siteStats = siteStatsRows[0] || { total_visits: 0, unique_visitors: 0, total_chats: 0 };

    // Calculer stats trafic par période
    const startOfMonth = new Date(now.getFullYear(), now.getMonth(), 1);
    const startOfLastMonth = new Date(now.getFullYear(), now.getMonth() - 1, 1);
    const startOfDay = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const dow = now.getDay();
    const startOfWeek = new Date(startOfDay);
    startOfWeek.setDate(startOfDay.getDate() - (dow === 0 ? 6 : dow - 1));
    const today = now.toISOString().split('T')[0];

    function periodVisits(rows, from) {
      const f = rows.filter(v => new Date(v.visited_at) >= from);
      return { visits: f.length, unique: new Set(f.map(v => v.visitor_id)).size };
    }
    function periodChats(rows, from) {
      return rows.filter(v => new Date(v.started_at) >= from).length;
    }
    const traffic = {
      today:   { ...periodVisits(visitsRows, startOfDay),  chats: periodChats(chatsRows, startOfDay) },
      week:    { ...periodVisits(visitsRows, startOfWeek), chats: periodChats(chatsRows, startOfWeek) },
      month:   { ...periodVisits(visitsRows, startOfMonth),chats: periodChats(chatsRows, startOfMonth) },
      year:    { ...periodVisits(visitsRows, startOfYear), chats: periodChats(chatsRows, startOfYear) },
      allTime: { visits: siteStats.total_visits || 0, unique: siteStats.unique_visitors || 0, chats: siteStats.total_chats || 0 }
    };

    const totalRevenue = sessions.reduce((s, r) => s + (r.montant || 0), 0);
    const monthSessions = sessions.filter(s => s.started_at && new Date(s.started_at) >= startOfMonth);
    const lastMonthSessions = sessions.filter(s => s.started_at && new Date(s.started_at) >= startOfLastMonth && new Date(s.started_at) < startOfMonth);
    const monthRevenue = monthSessions.reduce((s, r) => s + (r.montant || 0), 0);
    const lastMonthRevenue = lastMonthSessions.reduce((s, r) => s + (r.montant || 0), 0);

    const byPlan = {};
    sessions.forEach(s => {
      const k = s.formule || 'Autre';
      if (!byPlan[k]) byPlan[k] = { count: 0, revenue: 0 };
      byPlan[k].count++;
      byPlan[k].revenue += s.montant || 0;
    });

    const activeSubs = subscribers.filter(s => s.status === 'active' && (!s.expires_at || new Date(s.expires_at) > now));
    const todayMsgs = groupMsgs.filter(m => m.created_at && m.created_at.startsWith(today));
    const byRoom = {};
    groupMsgs.forEach(m => { if (m.room_id) byRoom[m.room_id] = (byRoom[m.room_id] || 0) + 1; });
    const activeGroupUsers = groupAccess.filter(a =>
      (a.paid_until && new Date(a.paid_until) > now) || (a.free_until && new Date(a.free_until) > now)
    );

    // Stats par agent avec notes et avis
    const byAgent = {};
    sessions.forEach(s => {
      if (!s.agent_name) return;
      if (!byAgent[s.agent_name]) byAgent[s.agent_name] = {
        name: s.agent_name, email: s.agent_email || null,
        sessions: 0, revenue: 0, plans: {},
        ratings: [], ratingSum: 0, ratingCount: 0, reviews: []
      };
      const a = byAgent[s.agent_name];
      a.sessions++;
      a.revenue += s.montant || 0;
      const p = s.formule || 'Autre';
      a.plans[p] = (a.plans[p] || 0) + 1;
      if (s.rating) {
        a.ratingSum += s.rating;
        a.ratingCount++;
        a.reviews.push({ stars: s.rating, comment: s.rating_comment || null, pseudo: s.client_pseudo, formule: s.formule, date: s.started_at });
      }
    });
    // Calculer note moyenne et nettoyer
    Object.values(byAgent).forEach(a => {
      a.avgRating = a.ratingCount > 0 ? Math.round((a.ratingSum / a.ratingCount) * 10) / 10 : null;
      delete a.ratingSum;
    });

    // Synchroniser avec les opérateurs Crisp (source de vérité)
    if (crispOperators.length > 0) {
      // Construire un set des emails et noms autorisés
      const authorizedEmails = new Set(crispOperators.map(op => (op.email || '').toLowerCase()));
      const authorizedNames = new Set(crispOperators.map(op => op.user?.nickname || op.user?.first_name).filter(Boolean));

      // Supprimer les agents non présents dans Crisp
      Object.keys(byAgent).forEach(name => {
        const a = byAgent[name];
        const emailOk = a.email && authorizedEmails.has(a.email.toLowerCase());
        const nameOk = authorizedNames.has(name);
        if (!emailOk && !nameOk) delete byAgent[name];
      });

      // Ajouter les opérateurs Crisp sans session
      crispOperators.forEach(op => {
        const name = op.user?.nickname || op.user?.first_name || op.email;
        if (!name || byAgent[name]) return;
        byAgent[name] = {
          name, email: op.email || null,
          sessions: 0, revenue: 0, plans: {},
          ratingCount: 0, avgRating: null, reviews: []
        };
      });
    }

    const unassigned = sessions.filter(s => !s.agent_name).length;

    // Répartition par pays (30 derniers jours de logs)
    const countryBreakdown = {};
    ipLogs.forEach(v => {
      if (!v.country) return;
      countryBreakdown[v.country] = (countryBreakdown[v.country] || 0) + 1;
    });

    return {
      statusCode: 200,
      headers: CORS,
      body: JSON.stringify({
        role: 'admin',
        sessions: {
          total: sessions.length,
          monthCount: monthSessions.length,
          lastMonthCount: lastMonthSessions.length,
          totalRevenue: Math.round(totalRevenue * 100) / 100,
          monthRevenue: Math.round(monthRevenue * 100) / 100,
          lastMonthRevenue: Math.round(lastMonthRevenue * 100) / 100,
          byPlan,
          recent: sessions.slice(0, 30),
          list: sessions,
          byAgent,
          unassigned
        },
        suggestions: suggestions.map(s => ({ id: s.id, content: s.content, created_at: s.created_at })),
        subscribers: {
          total: subscribers.length,
          active: activeSubs.length,
          list: subscribers
        },
        group: {
          totalMessages: groupMsgs.length,
          todayMessages: todayMsgs.length,
          byRoom,
          activeUsers: activeGroupUsers.length
        },
        traffic,
        ipLogs: ipLogs.filter(v => v.ip_address).map(v => ({
          ip: v.ip_address,
          country: v.country || '—',
          visited_at: v.visited_at,
          is_new: v.is_new
        })),
        countryBreakdown
      })
    };
  }

  // ── Subscriber check ──
  if (!email || !email.includes('@'))
    return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Email invalide' }) };

  const subs = await sbGet(`subscribers?email=eq.${encodeURIComponent(email)}&select=*&limit=1`);
  if (!subs.length)
    return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Aucun abonnement trouvé pour cet email' }) };

  const sub = subs[0];

  // Vérifier le mot de passe si un hash est défini
  if (sub.password_hash && sub.password_salt) {
    if (!password)
      return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Mot de passe requis' }) };
    const hash = hashPassword(password, sub.password_salt);
    if (hash !== sub.password_hash)
      return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Mot de passe incorrect' }) };
  }

  const now = new Date();
  // 'pending' = paiement confirmé mais webhook pas encore traité — accès autorisé
  const active = (sub.status === 'active' || sub.status === 'pending') && (!sub.expires_at || new Date(sub.expires_at) > now);
  const needsPasswordSetup = !sub.password_hash;

  const subSessions = sub.pseudo
    ? await sbGet(`sessions?statut=eq.paid&client_pseudo=eq.${encodeURIComponent(sub.pseudo)}&select=formule,started_at,rating,rating_comment,agent_name&order=started_at.desc&limit=50`)
    : [];

  return {
    statusCode: 200,
    headers: CORS,
    body: JSON.stringify({
      role: 'subscriber',
      active,
      expires_at: sub.expires_at,
      pseudo: sub.pseudo,
      cancel_at_period_end: sub.cancel_at_period_end || false,
      needs_password_setup: needsPasswordSetup,
      sessions: subSessions
    })
  };
};

