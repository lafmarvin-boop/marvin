const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;

const headers = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*'
};

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers, body: 'Method Not Allowed' };
  if (!SB_URL || !SB_KEY) return { statusCode: 500, headers, body: JSON.stringify({ error: 'Supabase non configuré' }) };

  let payload;
  try { payload = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers, body: 'Invalid JSON' }; }

  // Crisp envoie les événements de disponibilité opérateur
  const eventName = payload.event || payload.type;
  if (!eventName || !eventName.includes('availability')) {
    return { statusCode: 200, headers, body: JSON.stringify({ ignored: true, event: eventName }) };
  }

  // Type de disponibilité : online, away, offline
  const availType = payload.data?.availability?.type ?? payload.availability?.type;
  const online = availType === 'online';

  try {
    await fetch(`${SB_URL}/rest/v1/agents`, {
      method: 'POST',
      headers: {
        apikey: SB_KEY,
        Authorization: `Bearer ${SB_KEY}`,
        'Content-Type': 'application/json',
        Prefer: 'resolution=merge-duplicates'
      },
      body: JSON.stringify({ pseudo: 'parlons_admin', nom: 'Admin', actif: online })
    });
    return { statusCode: 200, headers, body: JSON.stringify({ online, event: eventName }) };
  } catch (e) {
    return { statusCode: 500, headers, body: JSON.stringify({ error: e.message }) };
  }
};
