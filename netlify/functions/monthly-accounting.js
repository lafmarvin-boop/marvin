// ─────────────────────────────────────────────────────────────────────────────
// Clôture comptable mensuelle — Parlons
//
// Déclenchée automatiquement par Netlify (schedule dans netlify.toml, le 26 de
// chaque mois) ou manuellement depuis espace.html (identifiants admin).
//
// Pour le mois précédent (calendaire, heure de Paris) :
//   1. lit les sessions, profils agents, abonnés (Supabase) et les encaissements
//      réels (Stripe : paiements à l'unité, factures d'abonnement, remboursements)
//   2. calcule les honoraires de chaque écoutant (barème contrat art. 7 : 0,50 /
//      1,50 / 2,50 € ; sessions GRATUIT = 0 ; Pass mensuel réparti au prorata
//      entre agents éligibles)
//   3. contrôle tout comme un comptable : montants incohérents, sessions non
//      attribuées, écarts Stripe ↔ base, remboursements, profils incomplets,
//      SIRET/IBAN invalides, seuil micro-entrepreneur
//   4. génère les PDF : facture + relevé URSSAF par agent, récapitulatif +
//      rapport de contrôle pour l'administrateur
//   5. envoie le tout par email (Resend) : chaque agent reçoit ses documents,
//      l'administrateur reçoit le récapitulatif, le rapport et toutes les pièces
//
// Les écoutants sont des auto-entrepreneurs (pas de bulletin de paie) : les
// documents légaux sont la facture (préparée pour leur compte dans le cadre du
// mandat) et le relevé de chiffre d'affaires à déclarer à l'URSSAF.
// ─────────────────────────────────────────────────────────────────────────────

const { PDFDocument, StandardFonts, rgb } = require('pdf-lib');

const SB_URL      = process.env.SUPABASE_URL;
const SB_KEY      = process.env.SUPABASE_SERVICE_KEY;
const ADMIN_EMAIL = (process.env.ADMIN_EMAIL || '').toLowerCase();
const ADMIN_PWD   = process.env.ADMIN_PASSWORD;
const RESEND_KEY  = process.env.RESEND_API_KEY;
const FROM_EMAIL  = process.env.FROM_EMAIL || 'Parlons <noreply@parlonsecoute.fr>';
const STRIPE_KEY  = process.env.STRIPE_SECRET_KEY;

const AI_EMAIL = 'claude@parlonsecoute.fr'; // Max, assistant d'écoute IA (ai-reply.js) — jamais rémunéré

const PARLONS = {
  name: 'PARLONS',
  siret: '105 179 360 000 18',
  address: '112 route Bois de Lion, 33240 Peujard',
  email: 'contact.parlons.ecoute@gmail.com',
  site: 'parlonsecoute.fr'
};

// Barème contrat de prestation — article 7
const AGENT_RATES   = { '10 min': 0.50, '30 min': 1.50, '1 heure': 2.50 };
const CLIENT_PRICES = { '10 min': 1.00, '30 min': 3.00, '1 heure': 5.00 };
const PASS_PRICE    = 15.00;
const PASS_COMMISSION   = Math.min(1, Math.max(0, parseFloat(process.env.PASS_COMMISSION_PCT || '50') / 100));
const PASS_MIN_SESSIONS = parseInt(process.env.PASS_MIN_SESSIONS || '10', 10);
const LOYALTY_DISCOUNT  = 0.10;
// Seuil de chiffre d'affaires micro-entrepreneur (prestations de services BNC)
const MICRO_THRESHOLD = parseFloat(process.env.MICRO_THRESHOLD || '77700');

const CORS = { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'Content-Type' };
const H = () => ({ apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` });

// ── Utilitaires ──────────────────────────────────────────────────────────────
async function sbGet(path) {
  const res = await fetch(`${SB_URL}/rest/v1/${path}`, { headers: H() });
  if (!res.ok) throw new Error(`Supabase ${res.status} sur ${path.split('?')[0]}`);
  const d = await res.json();
  return Array.isArray(d) ? d : [];
}

const eur = n => `${(Math.round((n || 0) * 100) / 100).toFixed(2).replace('.', ',')} €`;
const pad2 = n => String(n).padStart(2, '0');
const MONTHS_FR = ['janvier', 'février', 'mars', 'avril', 'mai', 'juin', 'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre'];

// Décalage Europe/Paris (minutes) pour un instant donné, sans dépendance externe
function parisOffsetMinutes(date) {
  const fmt = new Intl.DateTimeFormat('fr-FR', { timeZone: 'Europe/Paris', timeZoneName: 'longOffset' });
  const part = fmt.formatToParts(date).find(p => p.type === 'timeZoneName')?.value || 'GMT+01:00';
  const m = /GMT([+-])(\d{2}):(\d{2})/.exec(part);
  if (!m) return 60;
  return (m[1] === '-' ? -1 : 1) * (parseInt(m[2]) * 60 + parseInt(m[3]));
}
// Minuit (heure de Paris) du 1er du mois → Date UTC
function parisMonthStart(year, month0) {
  const guess = new Date(Date.UTC(year, month0, 1, 0, 0, 0));
  return new Date(guess.getTime() - parisOffsetMinutes(guess) * 60000);
}
function fmtDateFR(iso) {
  return new Date(iso).toLocaleDateString('fr-FR', { timeZone: 'Europe/Paris', day: '2-digit', month: '2-digit', year: 'numeric' });
}

// Mois cible : 'YYYY-MM' explicite, sinon le mois précédent (heure de Paris)
function resolvePeriod(monthStr) {
  let year, month0;
  if (monthStr && /^\d{4}-\d{2}$/.test(monthStr)) {
    year = parseInt(monthStr.slice(0, 4)); month0 = parseInt(monthStr.slice(5, 7)) - 1;
  } else {
    const now = new Date();
    const nowParis = new Date(now.getTime() + parisOffsetMinutes(now) * 60000);
    year = nowParis.getUTCFullYear(); month0 = nowParis.getUTCMonth() - 1;
    if (month0 < 0) { month0 = 11; year -= 1; }
  }
  const start = parisMonthStart(year, month0);
  const end   = parisMonthStart(month0 === 11 ? year + 1 : year, (month0 + 1) % 12);
  const yearStart = parisMonthStart(year, 0);
  const quarter = Math.floor(month0 / 3);
  const quarterStart = parisMonthStart(year, quarter * 3);
  return { year, month0, start, end, yearStart, quarterStart, quarter, label: `${MONTHS_FR[month0]} ${year}`, key: `${year}-${pad2(month0 + 1)}` };
}

// Classement d'une formule
function classify(formule) {
  const f = formule || '';
  if (f.includes('GRATUIT')) return { kind: 'free', rate: 0, clientPrice: 0 };
  if (f.includes('Pass mensuel')) return { kind: 'pass', rate: null, clientPrice: null };
  for (const k of Object.keys(AGENT_RATES)) if (f.includes(k)) return { kind: 'fixed', rate: AGENT_RATES[k], clientPrice: CLIENT_PRICES[k], plan: k };
  return { kind: 'unknown', rate: 0, clientPrice: null };
}

const validSiret = s => /^\d{14}$/.test(String(s || '').replace(/\s/g, ''));
const validIbanFR = s => /^FR\d{2}[A-Z0-9]{23}$/.test(String(s || '').replace(/\s/g, '').toUpperCase());
const agentKey = e => (e || '').toLowerCase().trim();

// ── Collecte ─────────────────────────────────────────────────────────────────
async function collect(period) {
  const iso = d => encodeURIComponent(d.toISOString());
  const [sessions, yearSessions, profiles, registered, subscribers] = await Promise.all([
    sbGet(`sessions?statut=in.(paid,ended,active,refunded)&started_at=gte.${iso(period.start)}&started_at=lt.${iso(period.end)}&select=id,client_pseudo,formule,montant,stripe_payment_id,statut,started_at,agent_email,agent_name&order=started_at.asc&limit=5000`),
    sbGet(`sessions?statut=in.(paid,ended)&started_at=gte.${iso(period.yearStart)}&started_at=lt.${iso(period.start)}&select=formule,agent_email,started_at&limit=20000`),
    sbGet('agent_profiles?select=email,pseudo,prenom,nom,adresse,code_postal,ville,siret,iban,notify_email'),
    sbGet('agent_passwords?select=email'),
    sbGet('subscribers?select=email,status,expires_at,created_at,stripe_subscription_id')
  ]);

  let stripe = null, stripeError = null;
  if (STRIPE_KEY) {
    try {
      const Stripe = require('stripe');
      const client = new Stripe(STRIPE_KEY);
      const range = { gte: Math.floor(period.start.getTime() / 1000), lt: Math.floor(period.end.getTime() / 1000) };
      const oneTime = [], invoices = [], refunds = [];
      for await (const pi of client.paymentIntents.list({ created: range, limit: 100 })) {
        if (pi.status === 'succeeded') oneTime.push({ id: pi.id, amount: pi.amount / 100, formule: pi.metadata?.formule || '', pseudo: pi.metadata?.pseudo || '' });
      }
      for await (const inv of client.invoices.list({ created: range, status: 'paid', limit: 100 })) {
        invoices.push({ id: inv.id, amount: (inv.amount_paid || 0) / 100, email: inv.customer_email || '' });
      }
      for await (const r of client.refunds.list({ created: range, limit: 100 })) {
        if (r.status === 'succeeded') refunds.push({ id: r.id, amount: r.amount / 100, paymentIntent: r.payment_intent });
      }
      stripe = { oneTime, invoices, refunds };
    } catch (e) { stripeError = e.message; }
  }
  return { sessions, yearSessions, profiles, registered, subscribers, stripe, stripeError };
}

// ── Calcul + contrôles ───────────────────────────────────────────────────────
function compute(period, data) {
  const { sessions, yearSessions, profiles, registered, subscribers, stripe, stripeError } = data;
  const anomalies = [];
  const add = (level, code, message) => anomalies.push({ level, code, message });

  const profileByEmail = {};
  profiles.forEach(p => { if (p.email) profileByEmail[agentKey(p.email)] = p; });

  // Agents = enregistrés (mot de passe) ∪ profils ∪ ayant des sessions ∪ admin — hors Max (IA)
  const agentEmails = new Set();
  registered.forEach(r => r.email && agentEmails.add(agentKey(r.email)));
  profiles.forEach(p => p.email && agentEmails.add(agentKey(p.email)));
  sessions.forEach(s => s.agent_email && agentEmails.add(agentKey(s.agent_email)));
  if (ADMIN_EMAIL) agentEmails.add(ADMIN_EMAIL);
  agentEmails.delete(AI_EMAIL);
  const ai = { sessions: 0, revenue: 0 };

  const agents = {};
  agentEmails.forEach(email => {
    const p = profileByEmail[email] || {};
    const displayName = (p.prenom || p.nom) ? `${p.prenom || ''} ${p.nom || ''}`.trim() : (p.pseudo || email.split('@')[0]);
    agents[email] = { email, profile: p, displayName, sessions: [], fixed: 0, clientRevenue: 0, passSessions: 0, freeSessions: 0, passShare: 0, total: 0, ytdBefore: 0, quarterBefore: 0, refunded: [] };
  });

  // Sessions du mois
  const payable = [];
  sessions.forEach(s => {
    const c = classify(s.formule);
    const email = agentKey(s.agent_email);
    if (s.statut === 'refunded') {
      if (email && agents[email]) agents[email].refunded.push(s);
      add('info', 'REMBOURSEE', `Session remboursée exclue des honoraires : ${fmtDateFR(s.started_at)} · ${s.client_pseudo || 'Anonyme'} · ${s.formule || '—'} (${eur(s.montant)})${email ? ' · agent ' + email : ''}`);
      return;
    }
    if (c.kind === 'unknown') add('alerte', 'FORMULE_INCONNUE', `Formule non reconnue « ${s.formule || '(vide)'} » le ${fmtDateFR(s.started_at)} (${s.client_pseudo || 'Anonyme'}) : honoraires comptés à 0 €, à vérifier.`);
    if (email === AI_EMAIL) {
      // Session prise en charge par Max (assistant IA) : encaissée, aucun honoraire à verser
      ai.sessions++; if (c.kind === 'fixed') ai.revenue += parseFloat(s.montant || 0);
      payable.push(s);
      return;
    }
    if (!email) {
      if (c.kind !== 'free') add('erreur', 'NON_ATTRIBUEE', `Session payée sans écoutant attribué : ${fmtDateFR(s.started_at)} · ${s.client_pseudo || 'Anonyme'} · ${s.formule || '—'} · ${eur(s.montant)} — aucun honoraire ne sera versé.`);
      return;
    }
    if (!agents[email]) return;
    const a = agents[email];
    if (c.kind === 'fixed') {
      const m = parseFloat(s.montant || 0);
      const expected = c.clientPrice, discounted = Math.round(expected * (1 - LOYALTY_DISCOUNT) * 100) / 100;
      if (Math.abs(m - expected) > 0.011 && Math.abs(m - discounted) > 0.011)
        add('alerte', 'MONTANT_INATTENDU', `Montant encaissé ${eur(m)} pour « ${s.formule} » le ${fmtDateFR(s.started_at)} (attendu ${eur(expected)} ou ${eur(discounted)} avec fidélité) — agent ${a.displayName}.`);
      if (!s.stripe_payment_id) add('alerte', 'SANS_PAIEMENT_STRIPE', `Session « ${s.formule} » du ${fmtDateFR(s.started_at)} (${a.displayName}) sans identifiant de paiement Stripe.`);
      a.fixed += c.rate; a.clientRevenue += m;
    } else if (c.kind === 'pass') a.passSessions++;
    else if (c.kind === 'free') a.freeSessions++;
    a.sessions.push({ ...s, cls: c });
    payable.push(s);
  });

  // Doublons (même agent, même pseudo, même minute)
  const seen = new Map();
  payable.forEach(s => {
    const k = `${agentKey(s.agent_email)}|${s.client_pseudo}|${(s.started_at || '').slice(0, 16)}`;
    if (seen.has(k)) add('alerte', 'DOUBLON', `Deux sessions identiques (${s.client_pseudo || 'Anonyme'}, ${fmtDateFR(s.started_at)}, ${s.formule}) pour ${agentKey(s.agent_email)} — vérifier qu'il ne s'agit pas d'une double insertion.`);
    seen.set(k, true);
  });

  // Cumuls annuels / trimestriels avant ce mois (pour le relevé URSSAF)
  yearSessions.forEach(s => {
    const email = agentKey(s.agent_email); const a = agents[email]; if (!a) return;
    const c = classify(s.formule); const r = c.kind === 'fixed' ? c.rate : 0;
    a.ytdBefore += r;
    if (new Date(s.started_at) >= period.quarterStart) a.quarterBefore += r;
  });

  // Pass mensuel : pool = encaissements abonnements (Stripe) × (1 − commission)
  let passRevenue = 0, passRevenueSource = 'Stripe (factures d\'abonnement payées)';
  if (stripe) passRevenue = stripe.invoices.reduce((s, i) => s + i.amount, 0);
  else {
    const active = subscribers.filter(s => s.status === 'active' && (!s.expires_at || new Date(s.expires_at) >= period.start) && (!s.created_at || new Date(s.created_at) < period.end));
    passRevenue = active.length * PASS_PRICE; passRevenueSource = `estimation : ${active.length} abonné(s) actif(s) × ${eur(PASS_PRICE)} (Stripe indisponible)`;
    if (active.length) add('alerte', 'PASS_ESTIME', `Encaissements Pass mensuel estimés (${passRevenueSource}) faute d'accès Stripe — à confirmer.`);
  }
  const passPool = Math.round(passRevenue * (1 - PASS_COMMISSION) * 100) / 100;
  // Règle : le solde est partagé À PARTS ÉGALES entre tous les écoutants ayant réalisé
  // au moins PASS_MIN_SESSIONS sessions dans le mois (tous types confondus).
  const eligible = Object.values(agents).filter(a => a.sessions.length >= PASS_MIN_SESSIONS);
  const totalPassSessions = Object.values(agents).reduce((s, a) => s + a.passSessions, 0);
  if (passPool > 0 && eligible.length) {
    const each = Math.floor(passPool * 100 / eligible.length) / 100;
    eligible.forEach(a => { a.passShare = each; });
    // Les centimes restants vont au premier éligible pour que la somme tombe juste
    const rest = Math.round((passPool - each * eligible.length) * 100) / 100;
    if (rest > 0) eligible[0].passShare = Math.round((eligible[0].passShare + rest) * 100) / 100;
  }
  if (passPool > 0 && !eligible.length)
    add('alerte', 'PASS_NON_REPARTI', `Solde abonnements de ${eur(passPool)} ce mois mais aucun écoutant n'atteint ${PASS_MIN_SESSIONS} sessions : rien n'est réparti (règle contrat art. 7).`);
  if (totalPassSessions > 0 && passRevenue === 0)
    add('alerte', 'PASS_SANS_ENCAISSEMENT', `${totalPassSessions} session(s) Pass mensuel effectuées mais aucun encaissement d'abonnement constaté sur la période.`);
  Object.values(agents).forEach(a => { a.total = Math.round((a.fixed + a.passShare) * 100) / 100; });
  const eligibleCount = eligible.length;

  // Profils
  Object.values(agents).forEach(a => {
    if (!a.sessions.length) return;
    const p = a.profile;
    const missing = [];
    if (!p.prenom) missing.push('prénom'); if (!p.nom) missing.push('nom'); if (!p.adresse || !p.code_postal || !p.ville) missing.push('adresse');
    if (!p.siret) missing.push('SIRET'); if (!p.iban) missing.push('IBAN');
    if (!p.email) add('erreur', 'PROFIL_ABSENT', `${a.email} a ${a.sessions.length} session(s) mais aucun profil auto-entrepreneur : facture impossible à établir correctement.`);
    else if (missing.length) add(a.total > 0 ? 'erreur' : 'alerte', 'PROFIL_INCOMPLET', `${a.displayName} (${a.email}) : ${missing.join(', ')} manquant(s) sur le profil — facture ${a.total > 0 ? 'non conforme' : 'incomplète'}.`);
    if (p.siret && !validSiret(p.siret)) add('erreur', 'SIRET_INVALIDE', `${a.displayName} : SIRET « ${p.siret} » invalide (14 chiffres attendus).`);
    if (p.iban && !validIbanFR(p.iban)) add('erreur', 'IBAN_INVALIDE', `${a.displayName} : IBAN « ${p.iban} » ne ressemble pas à un IBAN français valide — virement impossible.`);
    const ytd = a.ytdBefore + a.total;
    if (ytd >= MICRO_THRESHOLD) add('erreur', 'SEUIL_MICRO', `${a.displayName} : chiffre d'affaires ${period.year} via Parlons = ${eur(ytd)}, au-delà du seuil micro-entrepreneur (${eur(MICRO_THRESHOLD)}).`);
    else if (ytd >= MICRO_THRESHOLD * 0.8) add('alerte', 'SEUIL_MICRO_PROCHE', `${a.displayName} : chiffre d'affaires ${period.year} via Parlons = ${eur(ytd)}, soit plus de 80 % du seuil micro-entrepreneur.`);
  });

  // Rapprochement Stripe ↔ base
  // - encaissé brut en base : toutes les sessions à l'unité, attribuées ou non, remboursées comprises (Stripe compte aussi le brut)
  // - encaissé net (chiffre d'affaires) : hors remboursées
  const dbFixedAll = sessions.filter(s => classify(s.formule).kind === 'fixed');
  const dbOneTime = dbFixedAll.filter(s => s.statut !== 'refunded');
  const dbOneTimeSum = dbOneTime.reduce((s, x) => s + parseFloat(x.montant || 0), 0);
  const dbGrossSum = dbFixedAll.reduce((s, x) => s + parseFloat(x.montant || 0), 0);
  let reconciliation = { available: false, error: stripeError };
  if (stripe) {
    const stripeSum = stripe.oneTime.reduce((s, p) => s + p.amount, 0);
    const dbIds = new Set(dbFixedAll.map(s => s.stripe_payment_id).filter(Boolean));
    const stripeIds = new Set(stripe.oneTime.map(p => p.id));
    const inStripeNotDb = stripe.oneTime.filter(p => !dbIds.has(p.id));
    const inDbNotStripe = dbFixedAll.filter(s => s.stripe_payment_id && !stripeIds.has(s.stripe_payment_id));
    const refundsSum = stripe.refunds.reduce((s, r) => s + r.amount, 0);
    reconciliation = { available: true, stripeSum, dbSum: dbGrossSum, diff: Math.round((stripeSum - dbGrossSum) * 100) / 100, inStripeNotDb, inDbNotStripe, refundsSum, refundsCount: stripe.refunds.length, invoicesSum: passRevenue, invoicesCount: stripe.invoices.length };
    inStripeNotDb.forEach(p => add('erreur', 'STRIPE_SANS_SESSION', `Paiement Stripe ${p.id} (${eur(p.amount)}, ${p.formule || '?'}, ${p.pseudo || '?'}) encaissé mais aucune session correspondante en base : un écoutant a peut-être travaillé sans être payé, ou le client n'a pas été servi.`));
    inDbNotStripe.forEach(s => add('alerte', 'SESSION_SANS_STRIPE', `Session ${fmtDateFR(s.started_at)} (${s.client_pseudo || 'Anonyme'}, ${s.formule}) référence le paiement ${s.stripe_payment_id} introuvable parmi les paiements réussis de la période.`));
    if (Math.abs(reconciliation.diff) > 0.011 && !inStripeNotDb.length && !inDbNotStripe.length)
      add('alerte', 'ECART_STRIPE', `Écart de ${eur(reconciliation.diff)} entre les encaissements Stripe (${eur(stripeSum)}) et les sessions en base (${eur(dbGrossSum)}).`);
    stripe.refunds.forEach(r => {
      const s = dbOneTime.find(x => x.stripe_payment_id === r.paymentIntent);
      if (s && s.statut !== 'refunded') add('erreur', 'REMBOURSEMENT_NON_REPERCUTE', `Remboursement Stripe ${r.id} (${eur(r.amount)}) sur la session du ${fmtDateFR(s.started_at)} (${s.client_pseudo || 'Anonyme'}) alors qu'elle est comptée en honoraires pour ${agentKey(s.agent_email) || 'personne'}.`);
    });
  } else add('alerte', 'STRIPE_INDISPONIBLE', `Rapprochement Stripe impossible${stripeError ? ' : ' + stripeError : ' (clé absente)'} — les encaissements n'ont pas pu être vérifiés.`);

  // Totaux
  const agentList = Object.values(agents).sort((a, b) => b.total - a.total || a.displayName.localeCompare(b.displayName));
  const totals = {
    sessions: payable.length,
    freeSessions: agentList.reduce((s, a) => s + a.freeSessions, 0),
    passSessions: totalPassSessions,
    clientOneTime: dbOneTimeSum,
    passRevenue, passPool, passCommission: Math.round((passRevenue - passPool) * 100) / 100, passRevenueSource,
    passEligible: eligibleCount,
    aiSessions: ai.sessions, aiRevenue: Math.round(ai.revenue * 100) / 100,
    agentFixed: agentList.reduce((s, a) => s + a.fixed, 0),
    agentPass: agentList.reduce((s, a) => s + a.passShare, 0),
    agentTotal: agentList.reduce((s, a) => s + a.total, 0)
  };
  totals.revenue = Math.round((totals.clientOneTime + totals.passRevenue) * 100) / 100;
  totals.margin = Math.round((totals.revenue - totals.agentTotal) * 100) / 100;

  const errors = anomalies.filter(x => x.level === 'erreur').length, alerts = anomalies.filter(x => x.level === 'alerte').length;
  const verdict = errors ? `🔴 ${errors} erreur(s) à corriger` : alerts ? `🟠 ${alerts} point(s) à vérifier` : '🟢 tout est cohérent';
  return { period, agents: agentList, totals, anomalies, reconciliation, verdict, errors, alerts };
}

// ── PDF (pdf-lib, A4, coordonnées depuis le haut) ────────────────────────────
const C = { tc: rgb(0.77, 0.44, 0.29), dark: rgb(0.17, 0.12, 0.08), mid: rgb(0.39, 0.39, 0.39), light: rgb(0.63, 0.63, 0.63), blue: rgb(0.15, 0.39, 0.92), fill: rgb(0.98, 0.96, 0.94), fillBlue: rgb(0.9, 0.94, 1), red: rgb(0.75, 0.15, 0.15), orange: rgb(0.85, 0.5, 0.1), green: rgb(0.1, 0.55, 0.3), rule: rgb(0.91, 0.84, 0.69), white: rgb(1, 1, 1) };
// Police standard (WinAnsi) : on remplace ce qui n'est pas encodable
function safe(s) {
  return String(s ?? '').replace(/[‘’]/g, "'").replace(/[“”]/g, '"').replace(/→/g, '->').replace(/[^\x20-\x7E -ÿ–—…€]/g, '');
}
class Pdf {
  static async create() { const p = new Pdf(); p.doc = await PDFDocument.create(); p.font = await p.doc.embedFont(StandardFonts.Helvetica); p.bold = await p.doc.embedFont(StandardFonts.HelveticaBold); p.italic = await p.doc.embedFont(StandardFonts.HelveticaOblique); p.newPage(); return p; }
  newPage() { this.page = this.doc.addPage([595.28, 841.89]); this.y = 0; }
  text(x, y, s, o = {}) { const f = o.bold ? this.bold : o.italic ? this.italic : this.font; const size = o.size || 9; const str = safe(s); let px = x; if (o.align === 'right') px = x - f.widthOfTextAtSize(str, size); else if (o.align === 'center') px = x - f.widthOfTextAtSize(str, size) / 2; this.page.drawText(str, { x: px, y: 841.89 - y - size * 0.8, size, font: f, color: o.color || C.dark }); }
  // Texte multi-lignes, retourne la hauteur consommée
  para(x, y, s, o = {}) { const f = o.bold ? this.bold : o.italic ? this.italic : this.font; const size = o.size || 9; const maxW = o.width || 515; const lines = []; safe(s).split('\n').forEach(par => { let line = ''; par.split(' ').forEach(w => { const t = line ? line + ' ' + w : w; if (f.widthOfTextAtSize(t, size) > maxW && line) { lines.push(line); line = w; } else line = t; }); lines.push(line); }); lines.forEach((l, i) => this.text(x, y + i * (size + 3), l, o)); return lines.length * (size + 3); }
  line(x1, y1, x2, y2, color = C.rule, w = 0.6) { this.page.drawLine({ start: { x: x1, y: 841.89 - y1 }, end: { x: x2, y: 841.89 - y2 }, thickness: w, color }); }
  rect(x, y, w, h, color) { this.page.drawRectangle({ x, y: 841.89 - y - h, width: w, height: h, color }); }
  ensure(y, need) { if (y + need > 790) { this.newPage(); return 40; } return y; }
  // Tableau simple avec pagination. cols: [{label,w,align}], rows: string[][]
  table(y, cols, rows, o = {}) {
    const x0 = o.x || 40, rowH = o.rowH || 15, head = o.head || C.tc, size = o.size || 8.5;
    const drawHead = yy => { this.rect(x0, yy, cols.reduce((s, c) => s + c.w, 0), rowH, head); let cx = x0; cols.forEach(c => { this.text(c.align === 'right' ? cx + c.w - 4 : cx + 4, yy + 4, c.label, { bold: true, size, color: C.white, align: c.align === 'right' ? 'right' : undefined }); cx += c.w; }); return yy + rowH; };
    y = this.ensure(y, rowH * 3); y = drawHead(y);
    rows.forEach((r, i) => {
      if (y + rowH > 800) { this.newPage(); y = 40; y = drawHead(y); }
      if (i % 2 === 1) this.rect(x0, y, cols.reduce((s, c) => s + c.w, 0), rowH, o.zebra || C.fill);
      let cx = x0; cols.forEach((c, j) => { this.text(c.align === 'right' ? cx + c.w - 4 : cx + 4, y + 4, r[j] ?? '', { size, align: c.align === 'right' ? 'right' : undefined, bold: !!r._bold, color: r._color }); cx += c.w; });
      y += rowH;
    });
    if (o.foot) { this.rect(x0, y, cols.reduce((s, c) => s + c.w, 0), rowH, o.footFill || C.fill); let cx = x0; cols.forEach((c, j) => { this.text(c.align === 'right' ? cx + c.w - 4 : cx + 4, y + 4, o.foot[j] ?? '', { bold: true, size, align: c.align === 'right' ? 'right' : undefined }); cx += c.w; }); y += rowH; }
    return y;
  }
  async bytes() { return Buffer.from(await this.doc.save()); }
}

function agentHeader(pdf, a, y) {
  const p = a.profile;
  pdf.text(40, y, a.displayName, { bold: true, size: 12 });
  const lines = [p.adresse, [p.code_postal, p.ville].filter(Boolean).join(' '), a.email, p.siret ? `SIRET : ${p.siret}` : 'SIRET : non renseigné'].filter(Boolean);
  lines.forEach((l, i) => pdf.text(40, y + 16 + i * 11, l, { size: 8.5, color: C.mid }));
  return y + 16 + lines.length * 11;
}

async function buildInvoice(a, r) {
  const pdf = await Pdf.create(); const { period } = r; const p = a.profile;
  const initials = ((p.prenom || a.email).charAt(0) + (p.nom || '').charAt(0)).toUpperCase();
  const num = `PARL-${period.year}-${pad2(period.month0 + 1)}-${initials}`;
  const invoiceDate = new Date(Date.UTC(period.month0 === 11 ? period.year + 1 : period.year, (period.month0 + 1) % 12, 1)).toLocaleDateString('fr-FR', { timeZone: 'Europe/Paris' });
  agentHeader(pdf, a, 40);
  pdf.text(555, 40, 'FACTURE', { bold: true, size: 14, color: C.tc, align: 'right' });
  pdf.text(555, 60, `N° ${num}`, { size: 9, color: C.mid, align: 'right' });
  pdf.text(555, 72, `Date : ${invoiceDate}`, { size: 9, color: C.mid, align: 'right' });
  pdf.text(555, 84, `Période : ${period.label}`, { size: 9, color: C.mid, align: 'right' });
  pdf.line(40, 112, 555, 112);
  pdf.text(40, 122, 'DESTINATAIRE', { size: 7.5, color: C.light });
  pdf.text(40, 133, PARLONS.name, { bold: true, size: 10 });
  pdf.text(40, 146, PARLONS.address, { size: 8.5, color: C.mid });
  pdf.text(40, 157, `SIRET : ${PARLONS.siret}`, { size: 8.5, color: C.mid });
  pdf.text(40, 178, `Objet : Missions d'écoute — ${period.label}`, { bold: true, size: 10 });
  const rows = a.sessions.map(s => [fmtDateFR(s.started_at), s.client_pseudo || 'Anonyme', s.formule || '—', s.cls.kind === 'pass' ? 'quote-part*' : eur(s.cls.rate)]);
  if (a.passShare > 0) rows.push(Object.assign([`—`, `—`, `Pass mensuel — quote-part (1/${r.totals.passEligible} du solde abonnements)`, eur(a.passShare)], { _bold: true }));
  let y = pdf.table(190, [{ label: 'Date', w: 70 }, { label: 'Pseudo client', w: 110 }, { label: 'Prestation', w: 255 }, { label: 'Honoraires', w: 80, align: 'right' }], rows.length ? rows : [['—', '—', 'Aucune session ce mois', eur(0)]]);
  y = pdf.ensure(y + 12, 120);
  pdf.line(360, y, 555, y, C.tc, 0.8);
  pdf.text(360, y + 8, 'Total HT :', { bold: true, size: 11 }); pdf.text(555, y + 8, eur(a.total), { bold: true, size: 11, align: 'right' });
  pdf.text(360, y + 24, 'TVA non applicable — art. 293 B du CGI', { italic: true, size: 8, color: C.mid });
  let yy = y + 44;
  if (a.passShare > 0) { pdf.text(40, yy, `* Quote-part Pass mensuel : solde abonnements de ${eur(r.totals.passPool)} partagé à parts égales entre ${r.totals.passEligible} écoutant(s) ayant réalisé au moins ${PASS_MIN_SESSIONS} sessions ce mois.`, { size: 8, color: C.mid }); yy += 14; }
  else if (a.passSessions > 0) { pdf.text(40, yy, `* Sessions Pass mensuel : la quote-part du solde abonnements est réservée aux écoutants ayant réalisé au moins ${PASS_MIN_SESSIONS} sessions dans le mois.`, { size: 8, color: C.mid }); yy += 14; }
  pdf.text(40, yy + 6, 'Coordonnées bancaires pour règlement :', { size: 9 });
  pdf.text(40, yy + 19, p.iban || '— IBAN non renseigné sur le profil —', { bold: true, size: 9, color: p.iban ? C.dark : C.red });
  pdf.text(40, yy + 33, 'Règlement par virement sous 10 jours ouvrés à réception (contrat de prestation, art. 7).', { size: 8.5, color: C.mid });
  pdf.para(40, yy + 52, `Document préparé par la plateforme Parlons pour le compte de l'Écoutant dans le cadre du contrat de prestation et du mandat d'encaissement (art. 3bis et 7). L'Écoutant, auto-entrepreneur, reste seul responsable de sa facturation et de ses déclarations sociales et fiscales.`, { italic: true, size: 7.5, color: C.light });
  pdf.text(297, 810, `${a.displayName} · SIRET ${p.siret || '—'} · Facture ${num}`, { italic: true, size: 7.5, color: C.light, align: 'center' });
  return { name: `Facture_Parlons_${(p.nom || a.email.split('@')[0]).replace(/[^a-z0-9]/gi, '_')}_${period.key}.pdf`, bytes: await pdf.bytes(), num };
}

async function buildUrssaf(a, r) {
  const pdf = await Pdf.create(); const { period } = r; const p = a.profile;
  pdf.text(40, 40, 'RELEVÉ URSSAF', { bold: true, size: 18, color: C.blue });
  pdf.text(40, 64, `Chiffre d'affaires à déclarer — ${period.label}`, { size: 10, color: C.mid });
  pdf.line(40, 80, 555, 80, C.fillBlue, 1);
  let y = agentHeader(pdf, a, 92);
  pdf.text(40, y + 6, `Donneur d'ordre : ${PARLONS.name} · SIRET ${PARLONS.siret}`, { size: 8, color: C.light });
  pdf.text(40, y + 17, `Généré le : ${new Date().toLocaleDateString('fr-FR', { timeZone: 'Europe/Paris' })}`, { size: 8, color: C.light });
  y += 36;
  const byPlan = {};
  a.sessions.forEach(s => { const k = s.cls.kind === 'pass' ? 'Pass mensuel (quote-part)' : s.cls.kind === 'free' ? 'Première conversation offerte' : s.cls.plan || s.formule; if (!byPlan[k]) byPlan[k] = { n: 0, ca: 0 }; byPlan[k].n++; byPlan[k].ca += s.cls.kind === 'fixed' ? s.cls.rate : 0; });
  if (a.passShare > 0) byPlan['Pass mensuel (quote-part)'].ca = a.passShare;
  const rows = Object.entries(byPlan).map(([k, v]) => [k, String(v.n), eur(v.ca)]);
  y = pdf.table(y, [{ label: 'Prestation', w: 315 }, { label: 'Sessions', w: 100, align: 'right' }, { label: 'Chiffre d\'affaires', w: 100, align: 'right' }], rows.length ? rows : [['Aucune session ce mois', '0', eur(0)]], { head: C.blue, zebra: C.fillBlue, foot: [`TOTAL ${period.label.toUpperCase()}`, String(a.sessions.length), eur(a.total)], footFill: C.fillBlue });
  y += 14;
  const quarterTotal = a.quarterBefore + a.total, ytd = a.ytdBefore + a.total;
  pdf.rect(40, y, 515, 58, C.fillBlue);
  pdf.text(297, y + 12, `CA du mois à déclarer : ${eur(a.total)}`, { bold: true, size: 13, color: C.blue, align: 'center' });
  pdf.text(297, y + 32, `Cumul trimestre T${period.quarter + 1} ${period.year} : ${eur(quarterTotal)}   ·   Cumul année ${period.year} : ${eur(ytd)}`, { size: 9, color: C.blue, align: 'center' });
  pdf.text(297, y + 46, 'Montant à reporter dans votre déclaration URSSAF auto-entrepreneur (prestations de services / BNC)', { size: 8, color: C.mid, align: 'center' });
  y += 72;
  const notes = [
    'TVA non applicable — article 293 B du CGI (franchise en base de TVA).',
    'Ce relevé récapitule les honoraires perçus via la plateforme Parlons ; conservez votre facture mensuelle comme justificatif.',
    `Déclaration : autoentrepreneur.urssaf.fr -> Mon compte -> Déclarer mon CA, avant la fin du mois suivant (déclaration mensuelle) ou du trimestre (déclaration trimestrielle).`,
    `Seuil micro-entrepreneur ${period.year} (services) : ${eur(MICRO_THRESHOLD)} — votre cumul via Parlons : ${eur(ytd)}${ytd >= MICRO_THRESHOLD * 0.8 ? ' — ATTENTION, seuil proche ou dépassé' : ''}.`
  ];
  notes.forEach(n => { y += pdf.para(40, y, '• ' + n, { italic: true, size: 8, color: C.mid }) + 2; });
  pdf.text(297, 810, `${a.displayName} · SIRET ${p.siret || '—'} · Relevé URSSAF ${period.label}`, { italic: true, size: 7.5, color: C.light, align: 'center' });
  return { name: `Releve_URSSAF_Parlons_${(p.nom || a.email.split('@')[0]).replace(/[^a-z0-9]/gi, '_')}_${period.key}.pdf`, bytes: await pdf.bytes() };
}

async function buildAdminReport(r, invoiceNums) {
  const pdf = await Pdf.create(); const { period, totals, agents, anomalies, reconciliation } = r;
  pdf.text(40, 40, PARLONS.name, { bold: true, size: 18, color: C.tc });
  pdf.text(40, 62, `SIRET : ${PARLONS.siret} · ${PARLONS.site}`, { size: 8.5, color: C.mid });
  pdf.line(40, 78, 555, 78);
  pdf.text(40, 90, `Clôture comptable — ${period.label}`, { bold: true, size: 13 });
  pdf.text(40, 108, `Généré le ${new Date().toLocaleDateString('fr-FR', { timeZone: 'Europe/Paris' })} · Verdict : ${r.verdict.replace(/^[^\s]+\s/, '')}`, { size: 9.5, color: r.errors ? C.red : r.alerts ? C.orange : C.green, bold: true });
  pdf.text(40, 122, 'Usage : comptabilité, virements écoutants, déclaration URSSAF de Parlons.', { size: 8.5, color: C.mid });

  // 1. Honoraires par écoutant
  pdf.text(40, 142, '1. Honoraires par écoutant', { bold: true, size: 11 });
  const rows = agents.filter(a => a.sessions.length || a.total).map(a => {
    const p = a.profile; const ok = p.prenom && p.nom && p.siret && validSiret(p.siret) && p.iban && validIbanFR(p.iban) && p.adresse;
    return [a.displayName, String(a.sessions.length), String(a.freeSessions), String(a.passSessions), eur(a.fixed), eur(a.passShare), eur(a.total), ok ? 'complet' : 'INCOMPLET', invoiceNums[a.email] || '—'];
  });
  let y = pdf.table(156, [{ label: 'Écoutant', w: 96 }, { label: 'Sess.', w: 34, align: 'right' }, { label: 'Offertes', w: 42, align: 'right' }, { label: 'Pass', w: 32, align: 'right' }, { label: 'Fixes', w: 54, align: 'right' }, { label: 'Quote-part', w: 58, align: 'right' }, { label: 'Total', w: 58, align: 'right' }, { label: 'Profil', w: 58 }, { label: 'Facture', w: 83 }], rows.length ? rows : [['Aucune session ce mois', '0', '0', '0', eur(0), eur(0), eur(0), '', '']], { size: 8, foot: ['TOTAL', String(totals.sessions), String(totals.freeSessions), String(totals.passSessions), eur(totals.agentFixed), eur(totals.agentPass), eur(totals.agentTotal), '', ''] });

  // 2. Encaissements et marge
  y = pdf.ensure(y + 18, 160);
  pdf.text(40, y, '2. Encaissements et marge Parlons', { bold: true, size: 11 }); y += 14;
  const rec = reconciliation;
  const money = [
    ['Sessions à l\'unité (base de données)', eur(totals.clientOneTime)],
    ['Sessions à l\'unité (Stripe, paiements réussis)', rec.available ? eur(rec.stripeSum) : 'non vérifié'],
    ['Écart Stripe / base', rec.available ? eur(rec.diff) : '—'],
    ['Abonnements Pass mensuel encaissés', `${eur(totals.passRevenue)}${rec.available ? ` (${rec.invoicesCount} facture(s))` : ''}`],
    ['Remboursements Stripe sur la période', rec.available ? `${eur(rec.refundsSum)} (${rec.refundsCount})` : 'non vérifié'],
    ['Chiffre d\'affaires total encaissé', eur(totals.revenue)],
    ['Sessions prises en charge par Max (IA) — sans honoraires', `${totals.aiSessions} session(s) · ${eur(totals.aiRevenue)}`],
    ['Honoraires écoutants (à virer)', eur(totals.agentTotal)],
    ['Commission Parlons sur abonnements', `${eur(totals.passCommission)} (${Math.round(PASS_COMMISSION * 100)} %)`],
    Object.assign(['Marge brute Parlons (CA encaissé moins honoraires)', eur(totals.margin)], { _bold: true })
  ];
  y = pdf.table(y, [{ label: 'Poste', w: 365 }, { label: 'Montant', w: 150, align: 'right' }], money, { size: 8.5 });
  y += 6; y += pdf.para(40, y, `Source abonnements : ${totals.passRevenueSource}. Règle Pass mensuel (contrat art. 7) : solde après commission partagé à parts égales entre les ${totals.passEligible} écoutant(s) ayant réalisé au moins ${PASS_MIN_SESSIONS} sessions dans le mois. Commission abonnement paramétrable (PASS_COMMISSION_PCT).`, { italic: true, size: 7.5, color: C.mid });

  // 3. Contrôles
  y = pdf.ensure(y + 16, 60);
  pdf.text(40, y, `3. Rapport de contrôle — ${anomalies.length ? `${r.errors} erreur(s), ${r.alerts} alerte(s), ${anomalies.length - r.errors - r.alerts} info(s)` : 'aucune anomalie détectée'}`, { bold: true, size: 11 }); y += 16;
  const order = { erreur: 0, alerte: 1, info: 2 };
  [...anomalies].sort((a, b) => order[a.level] - order[b.level]).forEach(an => {
    y = pdf.ensure(y, 30);
    const col = an.level === 'erreur' ? C.red : an.level === 'alerte' ? C.orange : C.mid;
    pdf.text(40, y, an.level.toUpperCase(), { bold: true, size: 7.5, color: col });
    y += pdf.para(92, y, `[${an.code}] ${an.message}`, { size: 8.5, width: 463 }) + 4;
  });
  if (!anomalies.length) { pdf.text(40, y, 'Montants, attributions, encaissements Stripe et profils sont cohérents.', { size: 9, color: C.green }); y += 14; }

  // 4. Vérifié
  y = pdf.ensure(y + 10, 90);
  pdf.text(40, y, '4. Contrôles effectués', { bold: true, size: 11 }); y += 14;
  ['Barème appliqué : 10 min = 0,50 € · 30 min = 1,50 € · 1 heure = 2,50 € · première conversation offerte = 0 € (contrat art. 7).',
   'Chaque session payée est rattachée à un écoutant ; montants encaissés comparés au tarif (fidélité −10 % acceptée).',
   'Rapprochement session par session avec les paiements Stripe réussis ; remboursements recherchés et exclus des honoraires.',
   'Profils auto-entrepreneur : identité, adresse, SIRET (14 chiffres), IBAN français ; cumul annuel comparé au seuil micro-entrepreneur.',
   'Détection des doublons (même écoutant, même client, même minute).'].forEach(t => { y += pdf.para(40, y, '• ' + t, { size: 8.5, color: C.mid }) + 1; });
  pdf.text(297, 810, `${PARLONS.name} · SIRET ${PARLONS.siret} · Clôture ${period.label}`, { italic: true, size: 7.5, color: C.light, align: 'center' });
  return { name: `Parlons_cloture_${period.key}.pdf`, bytes: await pdf.bytes() };
}

// ── Emails (Resend) ──────────────────────────────────────────────────────────
async function sendEmail({ to, subject, html, attachments }) {
  if (!RESEND_KEY) return { ok: false, skipped: true };
  const res = await fetch('https://api.resend.com/emails', {
    method: 'POST', headers: { Authorization: `Bearer ${RESEND_KEY}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ from: FROM_EMAIL, to, subject, html, attachments: attachments.map(a => ({ filename: a.name, content: a.bytes.toString('base64') })) })
  });
  const body = await res.text();
  if (!res.ok) throw new Error(`Resend ${res.status} : ${body.slice(0, 200)}`);
  return { ok: true };
}
const esc = s => String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

function agentEmailHtml(a, r) {
  const { period } = r; const p = a.profile;
  return `<div style="font-family:sans-serif;max-width:600px;color:#2C1F14">
<p>Bonjour ${esc(p.prenom || a.displayName)},</p>
<p>Voici vos documents comptables Parlons pour <strong>${esc(period.label)}</strong> :</p>
<ul><li><strong>Facture</strong> préparée pour votre compte — ${a.sessions.length} session(s), total <strong>${esc(eur(a.total))}</strong> HT (TVA non applicable, art. 293 B du CGI)</li>
<li><strong>Relevé URSSAF</strong> — chiffre d'affaires du mois à déclarer : <strong>${esc(eur(a.total))}</strong></li></ul>
<p>Le règlement est effectué par virement sous 10 jours ouvrés${p.iban ? '' : ' — <strong style="color:#b91c1c">votre IBAN n\'est pas renseigné</strong> : complétez votre profil auto-entrepreneur dans votre espace pour être payé(e)'}.</p>
<p>Pensez à déclarer ce montant sur <a href="https://autoentrepreneur.urssaf.fr">autoentrepreneur.urssaf.fr</a> avant la fin du mois.</p>
<p style="font-size:.85em;color:#7A6560">Vous restez responsable de votre facturation et de vos déclarations ; ces documents sont établis à partir des sessions enregistrées sur la plateforme. Une question ? Répondez simplement à cet email.</p>
<p>L'équipe Parlons</p></div>`;
}
function adminEmailHtml(r, sent) {
  const { period, totals, anomalies, agents } = r;
  const li = anomalies.map(a => `<li><strong style="color:${a.level === 'erreur' ? '#b91c1c' : a.level === 'alerte' ? '#c2410c' : '#555'}">${a.level.toUpperCase()}</strong> [${esc(a.code)}] ${esc(a.message)}</li>`).join('');
  const rows = agents.filter(a => a.sessions.length || a.total).map(a => `<tr><td>${esc(a.displayName)}</td><td align="right">${a.sessions.length}</td><td align="right">${esc(eur(a.total))}</td><td>${esc(sent[a.email] || '—')}</td></tr>`).join('');
  return `<div style="font-family:sans-serif;max-width:700px;color:#2C1F14">
<h2 style="margin:0 0 .3em">Clôture comptable — ${esc(period.label)}</h2>
<p style="font-size:1.1em"><strong>${esc(r.verdict)}</strong></p>
<table cellpadding="6" style="border-collapse:collapse;font-size:.92em"><tr style="background:#FBF6EF"><th align="left">Écoutant</th><th>Sessions</th><th>Honoraires</th><th align="left">Envoi</th></tr>${rows || '<tr><td colspan="4">Aucune session ce mois</td></tr>'}</table>
<p><strong>CA encaissé :</strong> ${esc(eur(totals.revenue))} · <strong>Honoraires à virer :</strong> ${esc(eur(totals.agentTotal))} · <strong>Marge brute :</strong> ${esc(eur(totals.margin))}</p>
${anomalies.length ? `<h3>Contrôles (${r.errors} erreur(s), ${r.alerts} alerte(s))</h3><ul style="font-size:.9em">${li}</ul>` : '<p style="color:#15803d">Aucune anomalie : montants, attributions, encaissements Stripe et profils sont cohérents.</p>'}
<p style="font-size:.85em;color:#7A6560">Pièces jointes : récapitulatif + rapport de contrôle, puis la facture et le relevé URSSAF de chaque écoutant. Les écoutants ont reçu leurs propres documents séparément.</p></div>`;
}

// ── Orchestration ────────────────────────────────────────────────────────────
async function runClosing({ month, mode = 'all', dryRun = false }) {
  const period = resolvePeriod(month);
  const data = await collect(period);
  const r = compute(period, data);

  // PDF par agent (uniquement ceux ayant une activité)
  const active = r.agents.filter(a => a.sessions.length || a.total);
  const docs = {}; const invoiceNums = {};
  for (const a of active) { const inv = await buildInvoice(a, r); const ur = await buildUrssaf(a, r); docs[a.email] = [inv, ur]; invoiceNums[a.email] = inv.num; }
  const admin = await buildAdminReport(r, invoiceNums);

  const sent = {}; const sendErrors = [];
  if (!dryRun) {
    if (mode === 'all') {
      await Promise.all(active.map(async a => {
        const to = a.profile.email || a.email;
        try { const res = await sendEmail({ to, subject: `Parlons — vos documents comptables de ${period.label}`, html: agentEmailHtml(a, r), attachments: docs[a.email] }); sent[a.email] = res.skipped ? 'non envoyé (Resend absent)' : `envoyé à ${to}`; }
        catch (e) { sent[a.email] = 'ÉCHEC'; sendErrors.push(`${a.email} : ${e.message}`); }
      }));
    } else active.forEach(a => { sent[a.email] = 'non envoyé (mode admin seul)'; });
    if (ADMIN_EMAIL) {
      try { await sendEmail({ to: ADMIN_EMAIL, subject: `Parlons — clôture ${period.label} : ${r.verdict}${sendErrors.length ? ' · ' + sendErrors.length + ' envoi(s) en échec' : ''}`, html: adminEmailHtml(r, sent) + (sendErrors.length ? `<p style="color:#b91c1c"><strong>Envois en échec :</strong><br>${sendErrors.map(esc).join('<br>')}</p>` : ''), attachments: [admin, ...active.flatMap(a => docs[a.email])] }); }
      catch (e) { sendErrors.push(`admin : ${e.message}`); }
    }
  }
  return {
    ok: true, period: period.key, label: period.label, verdict: r.verdict, dryRun, mode,
    totals: r.totals, agents: active.map(a => ({ email: a.email, name: a.displayName, sessions: a.sessions.length, total: a.total, sent: sent[a.email] || null })),
    anomalies: r.anomalies, reconciliation: r.reconciliation.available ? { stripeSum: r.reconciliation.stripeSum, dbSum: r.reconciliation.dbSum, diff: r.reconciliation.diff, refunds: r.reconciliation.refundsSum } : { available: false, error: r.reconciliation.error },
    sendErrors, documents: [admin.name, ...active.flatMap(a => docs[a.email].map(d => d.name))]
  };
}

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };
  if (!SB_URL || !SB_KEY) return { statusCode: 503, headers: CORS, body: JSON.stringify({ error: 'Service non configuré' }) };

  let body = {};
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Bad Request' }; }

  // Invocation planifiée par Netlify (le 26 de chaque mois) → mois précédent, envoi à tous
  const scheduled = typeof body.next_run === 'string';
  if (!scheduled) {
    // Invocation manuelle : identifiants administrateur obligatoires
    const email = (body.adminEmail || '').toLowerCase().trim();
    if (!ADMIN_EMAIL || !ADMIN_PWD || email !== ADMIN_EMAIL || body.adminPassword !== ADMIN_PWD)
      return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Non autorisé' }) };
  }

  try {
    const result = await runClosing({ month: scheduled ? undefined : body.month, mode: scheduled ? 'all' : (body.mode === 'all' ? 'all' : 'admin'), dryRun: !scheduled && !!body.dryRun });
    console.log(`monthly-accounting ${result.period} : ${result.verdict} · ${result.agents.length} écoutant(s) · ${result.sendErrors.length} échec(s) d'envoi`);
    return { statusCode: 200, headers: CORS, body: JSON.stringify(result) };
  } catch (e) {
    console.error('monthly-accounting:', e);
    if (ADMIN_EMAIL && scheduled) sendEmail({ to: ADMIN_EMAIL, subject: 'Parlons — ÉCHEC de la clôture comptable automatique', html: `<p>La clôture mensuelle a échoué :</p><pre>${esc(e.stack || e.message)}</pre><p>Relancez-la depuis l'espace admin (bouton « Clôture mensuelle ») après correction.</p>`, attachments: [] }).catch(() => {});
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};

// Exporté pour les tests locaux
exports._internal = { resolvePeriod, compute, buildInvoice, buildUrssaf, buildAdminReport, classify };
