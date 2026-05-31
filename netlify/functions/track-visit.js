const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
};

async function getGeoData(ip) {
  if (!ip || ip === '127.0.0.1' || ip === '::1' || ip.startsWith('192.168') || ip.startsWith('10.') || ip.startsWith('fe80')) return {};
  try {
    const res = await fetch(`http://ip-api.com/json/${encodeURIComponent(ip)}?fields=status,city,regionName,isp&lang=fr`, {
      signal: AbortSignal.timeout(3500)
    });
    if (!res.ok) return {};
    const d = await res.json();
    if (d.status !== 'success') return {};
    const city = d.city || null;
    const region = d.regionName || null;
    // Si pas de ville (mobile/IPv6), afficher l'opérateur FAI
    const isp = (!city && d.isp) ? d.isp : null;
    return { city: city || isp, region };
  } catch { return {}; }
}

const BOT_UA = /bot|crawl|spider|slurp|mediapartners|googlebot|bingbot|yandex|baidu|duckduck|netlify|checkbot|monitor|pingdom|uptimerobot|statuscake|curl|wget|python|axios|go-http|java\/|ruby|scrapy|phantomjs|headless|selenium|puppeteer|playwright/i;
const DATACENTER = /^(3\.|52\.|54\.|18\.|34\.|35\.|44\.|13\.|15\.|100\.|172\.(1[6-9]|2[0-9]|3[01])\.)/.source;
const DC_RE = new RegExp(DATACENTER);

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  // Ignorer les bots et crawlers connus
  const ua = event.headers['user-agent'] || '';
  if (BOT_UA.test(ua)) return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Bad Request' }; }

  if (!SB_URL || !SB_KEY) return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };

  const { visitorId, isNew } = body;
  if (!visitorId || typeof visitorId !== 'string' || visitorId.length > 64)
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Invalid visitor ID' }) };

  const ip = event.headers['x-nf-client-connection-ip']
    || (event.headers['x-forwarded-for'] || '').split(',')[0].trim()
    || null;
  const country = (event.headers['x-country'] || '').toUpperCase() || null;

  // Ignorer les IP de datacenter AWS/GCP/Azure qui arrivent toujours comme "nouveau" (pas de localStorage)
  if (ip && DC_RE.test(ip) && isNew) return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };

  try {
    // Géolocalisation ville/région (ipwho.is, gratuit, sans clé)
    const geo = await getGeoData(ip);

    // Vérifier côté serveur si ce visitorId a déjà été vu (plus fiable que isNew client)
    const checkRes = await fetch(
      `${SB_URL}/rest/v1/visits?visitor_id=eq.${encodeURIComponent(visitorId)}&select=id&limit=1`,
      { headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` } }
    );
    const existing = await checkRes.json();
    const isActuallyNew = !Array.isArray(existing) || existing.length === 0;

    // Enregistrer la visite (conservation 30 jours max — intérêt légitime RGPD)
    await fetch(`${SB_URL}/rest/v1/visits`, {
      method: 'POST',
      headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, 'Content-Type': 'application/json', Prefer: 'return=minimal' },
      body: JSON.stringify({ visitor_id: visitorId, is_new: isActuallyNew, ip_address: ip, country, city: geo.city, region: geo.region })
    });

    // Nettoyage automatique > 30 jours (fire-and-forget)
    const cutoff = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString();
    fetch(`${SB_URL}/rest/v1/visits?visited_at=lt.${encodeURIComponent(cutoff)}`, {
      method: 'DELETE',
      headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, Prefer: 'return=minimal' }
    }).catch(e => console.error('cleanup:', e.message));

    // Compteurs globaux
    const res = await fetch(`${SB_URL}/rest/v1/site_stats?id=eq.1&select=total_visits,unique_visitors`, {
      headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` }
    });
    const rows = await res.json();
    const s = rows[0] || { total_visits: 0, unique_visitors: 0 };
    await fetch(`${SB_URL}/rest/v1/site_stats?id=eq.1`, {
      method: 'PATCH',
      headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, 'Content-Type': 'application/json', Prefer: 'return=minimal' },
      body: JSON.stringify({
        total_visits: (s.total_visits || 0) + 1,
        unique_visitors: (s.unique_visitors || 0) + (isActuallyNew ? 1 : 0),
        updated_at: new Date().toISOString()
      })
    });
  } catch(e) { console.error('track-visit:', e.message); }

  return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };
};
