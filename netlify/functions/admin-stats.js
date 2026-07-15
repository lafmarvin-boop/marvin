const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;
const ADMIN_PWD = process.env.ADMIN_PASSWORD;
const ADMIN_EMAIL = (process.env.ADMIN_EMAIL || '').toLowerCase();
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

    // ── Action : ajouter un agent ──
    if (body.action === 'add_agent') {
      const agentEmail = (body.agentEmail || '').toLowerCase().trim();
      const agentPassword = body.agentPassword || '';
      const agentPrenom = (body.agentPrenom || '').trim();
      const agentNom = (body.agentNom || '').trim();
      if (!agentEmail || !agentEmail.includes('@'))
        return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Email invalide' }) };
      if (!agentPassword || agentPassword.length < 8)
        return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Mot de passe trop court (8 car. min.)' }) };
      if (!agentPrenom || !agentNom)
        return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Prénom et nom requis.' }) };

      const salt = crypto.randomBytes(32).toString('hex');
      const hash = crypto.pbkdf2Sync(agentPassword, salt, 100000, 64, 'sha512').toString('hex');

      await Promise.all([
        fetch(`${SB_URL}/rest/v1/agent_passwords`, {
          method: 'POST',
          headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, 'Content-Type': 'application/json', Prefer: 'resolution=merge-duplicates' },
          body: JSON.stringify({ email: agentEmail, password_hash: hash, password_salt: salt })
        }),
        fetch(`${SB_URL}/rest/v1/agent_profiles`, {
          method: 'POST',
          headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, 'Content-Type': 'application/json', Prefer: 'resolution=merge-duplicates' },
          body: JSON.stringify({ email: agentEmail, prenom: agentPrenom, nom: agentNom, updated_at: new Date().toISOString() })
        })
      ]);

      return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };
    }

    // ── Action : retirer un agent ──
    if (body.action === 'remove_agent') {
      const agentEmail = (body.agentEmail || '').toLowerCase().trim();
      if (!agentEmail) return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Email manquant' }) };

      // Supprimer de Supabase
      await Promise.all([
        fetch(`${SB_URL}/rest/v1/agent_passwords?email=eq.${encodeURIComponent(agentEmail)}`, {
          method: 'DELETE',
          headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, Prefer: 'return=minimal' }
        }),
        fetch(`${SB_URL}/rest/v1/agent_profiles?email=eq.${encodeURIComponent(agentEmail)}`, {
          method: 'DELETE',
          headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, Prefer: 'return=minimal' }
        })
      ]);

      return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };
    }

    // ── Action : présence seule (léger, pour refresh périodique) ──
    if (body.action === 'presence') {
      const [rows, activeSessions] = await Promise.all([
        sbGet('agent_presence?select=agent_email,status,last_seen&limit=100'),
        sbGet('chat_sessions?status=eq.active&select=agent_email&limit=200')
      ]);
      const chatCount = {};
      activeSessions.forEach(s => {
        if (s.agent_email) chatCount[s.agent_email.toLowerCase()] = (chatCount[s.agent_email.toLowerCase()] || 0) + 1;
      });
      const presence = rows.map(r => ({ ...r, activeChatCount: chatCount[(r.agent_email || '').toLowerCase()] || 0 }));
      return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, presence }) };
    }

    const now = new Date();
    const startOfYear = new Date(now.getFullYear(), 0, 1);

    const [sessions, subscribers, groupMsgs, groupAccess, suggestions, siteStatsRows, visitsRows, chatsRows, ipLogs, agentPresenceRows, chatRatings] = await Promise.all([
      sbGet('sessions?statut=in.(paid,ended)&select=formule,montant,client_pseudo,started_at,stripe_payment_id,agent_name,agent_email,resolved_at,rating,rating_comment&order=started_at.desc&limit=500'),
      sbGet('subscribers?select=*&order=created_at.desc&limit=200'),
      sbGet('group_messages?select=room_id,created_at,author&order=created_at.desc&limit=2000'),
      sbGet('group_access?select=room_id,pseudo,free_until,paid_until,is_agent&order=created_at.desc&limit=300'),
      sbGet('suggestions?select=*&order=created_at.desc&limit=100'),
      sbGet('site_stats?id=eq.1&select=total_visits,unique_visitors,total_chats'),
      sbGet(`visits?visited_at=gte.${encodeURIComponent(startOfYear.toISOString())}&select=visitor_id,visited_at&order=visited_at.desc&limit=50000`),
      sbGet(`chats?created_at=gte.${encodeURIComponent(startOfYear.toISOString())}&select=created_at&order=created_at.desc&limit=10000`),
      sbGet('visits?select=ip_address,country,city,region,visited_at,visitor_id,is_new&order=visited_at.desc&limit=200'),
      sbGet('agent_presence?select=agent_email,status,last_seen&limit=100'),
      sbGet('chat_sessions?rating=not.is.null&stripe_payment_id=is.null&agent_email=not.is.null&select=agent_email,pre_name,session_label,assigned_at,rating,rating_comment&order=assigned_at.desc&limit=500')
    ]);
    const presenceByEmail = {};
    agentPresenceRows.forEach(p => { if (p.agent_email) presenceByEmail[p.agent_email.toLowerCase()] = p; });
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
      return rows.filter(v => new Date(v.created_at) >= from).length;
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
    // Fusionner les avis des sessions gratuites/abonnement (stockés uniquement dans chat_sessions)
    chatRatings.forEach(cs => {
      if (!cs.agent_email || !cs.rating) return;
      const agentEntry = Object.values(byAgent).find(a => (a.email || '').toLowerCase() === cs.agent_email.toLowerCase());
      if (!agentEntry) return;
      agentEntry.ratingSum = (agentEntry.ratingSum || 0) + cs.rating;
      agentEntry.ratingCount++;
      agentEntry.reviews.push({ stars: cs.rating, comment: cs.rating_comment || null, pseudo: cs.pre_name, formule: cs.session_label, date: cs.assigned_at });
    });

    // Calculer note moyenne et nettoyer
    Object.values(byAgent).forEach(a => {
      a.avgRating = a.ratingCount > 0 ? Math.round(((a.ratingSum || 0) / a.ratingCount) * 10) / 10 : null;
      delete a.ratingSum;
    });

    // Agents enregistrés (source de vérité : agent_passwords + profils)
    const [agentPwdRows, agentProfileRows] = await Promise.all([
      sbGet('agent_passwords?select=email&limit=100'),
      sbGet('agent_profiles?select=email,prenom,nom&limit=100')
    ]);
    const profileMap = {};
    agentProfileRows.forEach(p => { if (p.email) profileMap[p.email.toLowerCase()] = p; });
    // Inclure l'admin comme agent (même s'il n'est pas dans agent_passwords)
    const adminEmailLower = ADMIN_EMAIL;
    const allAgentEmails = [
      ...agentPwdRows.map(r => r.email).filter(Boolean),
      ...(adminEmailLower && !agentPwdRows.some(r => (r.email||'').toLowerCase() === adminEmailLower) ? [adminEmailLower] : [])
    ];
    allAgentEmails.forEach(emailEntry => {
      const alreadyIn = Object.values(byAgent).some(a => (a.email || '').toLowerCase() === emailEntry.toLowerCase());
      if (alreadyIn) return;
      const profile = profileMap[emailEntry.toLowerCase()];
      const name = profile && profile.prenom ? `${profile.prenom} ${profile.nom || ''}`.trim() : emailEntry.split('@')[0];
      byAgent[name] = {
        name, email: emailEntry,
        sessions: 0, revenue: 0, plans: {},
        ratingCount: 0, avgRating: null, reviews: []
      };
    });

    // Enrichir les agents avec leur profil (prenom + nom) et présence si disponible
    const allProfileRows = await sbGet('agent_profiles?select=email,prenom,nom&limit=100');
    const profileByEmail = {};
    allProfileRows.forEach(p => { if (p.email) profileByEmail[p.email.toLowerCase()] = p; });
    Object.values(byAgent).forEach(a => {
      if (!a.email) return;
      const p = profileByEmail[a.email.toLowerCase()];
      if (p && p.prenom) a.displayName = `${p.prenom} ${p.nom || ''}`.trim();
      const pres = presenceByEmail[a.email.toLowerCase()];
      a.presenceStatus = pres ? pres.status : null;
      a.presenceLastSeen = pres ? pres.last_seen : null;
    });

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
          city: v.city || null,
          region: v.region || null,
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
    ? await sbGet(`sessions?statut=in.(paid,ended)&client_pseudo=eq.${encodeURIComponent(sub.pseudo)}&select=formule,started_at,rating,rating_comment,agent_name&order=started_at.desc&limit=50`)
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

