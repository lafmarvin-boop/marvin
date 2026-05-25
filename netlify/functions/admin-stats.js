const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;
const ADMIN_PWD = process.env.ADMIN_PASSWORD || 'Parlons2026!';
const ADMIN_EMAIL = (process.env.ADMIN_EMAIL || 'lafmarvin@gmail.com').toLowerCase();

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type'
};

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
  if (email === ADMIN_EMAIL) {
    if (password !== ADMIN_PWD)
      return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Mot de passe incorrect' }) };

    const [sessions, subscribers, groupMsgs, groupAccess] = await Promise.all([
      sbGet('sessions?statut=eq.paid&select=formule,montant,client_pseudo,started_at,stripe_payment_id&order=started_at.desc&limit=500'),
      sbGet('subscribers?select=*&order=created_at.desc&limit=200'),
      sbGet('group_messages?select=room_id,created_at,author&order=created_at.desc&limit=2000'),
      sbGet('group_access?select=room_id,pseudo,free_until,paid_until,is_agent&order=created_at.desc&limit=300')
    ]);

    const now = new Date();
    const startOfMonth = new Date(now.getFullYear(), now.getMonth(), 1);
    const startOfLastMonth = new Date(now.getFullYear(), now.getMonth() - 1, 1);
    const today = now.toISOString().split('T')[0];

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
          recent: sessions.slice(0, 30)
        },
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
        }
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
  const now = new Date();
  const active = sub.status === 'active' && (!sub.expires_at || new Date(sub.expires_at) > now);

  return {
    statusCode: 200,
    headers: CORS,
    body: JSON.stringify({
      role: 'subscriber',
      active,
      expires_at: sub.expires_at,
      pseudo: sub.pseudo,
      cancel_at_period_end: sub.cancel_at_period_end || false
    })
  };
};

