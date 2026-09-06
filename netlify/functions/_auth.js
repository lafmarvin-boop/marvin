// ─────────────────────────────────────────────────────────────────────────────
// Jetons de session signés — remplacent le stockage du mot de passe en clair
//
// Avant : espace.html et agent-app.html gardaient le mot de passe admin, abonné
// ou écoutant en clair dans localStorage pour pouvoir se reconnecter tout seuls.
// Une faille XSS n'importe où sur le site suffisait alors à voler l'identifiant
// définitif. Désormais le serveur délivre au login un jeton signé (HMAC-SHA256),
// à durée limitée, que le navigateur stocke à la place : volé, il expire, et il
// ne permet pas de changer le mot de passe.
//
// Le secret de signature ne quitte jamais le serveur. Aucune table n'est
// nécessaire : la validité est portée par la signature et la date d'expiration.
// ─────────────────────────────────────────────────────────────────────────────

const crypto = require('crypto');

// AUTH_SECRET si l'admin en définit un ; sinon la clé de service Supabase, toujours
// présente côté serveur et jamais exposée au navigateur.
const SECRET = process.env.AUTH_SECRET || process.env.SUPABASE_SERVICE_KEY || '';

const TTL = {
  admin: 24 * 60 * 60 * 1000,        // espace admin : 24 h
  subscriber: 24 * 60 * 60 * 1000,   // espace abonné : 24 h
  agent: 30 * 24 * 60 * 60 * 1000    // application écoutant : 30 j (usage quotidien)
};

function sign(body) {
  return crypto.createHmac('sha256', SECRET).update(body).digest('base64url');
}

// Émet un jeton pour un sujet (email) et un rôle donné
function issueToken(subject, role) {
  if (!SECRET) return null;
  const payload = {
    sub: String(subject || '').toLowerCase().trim(),
    role,
    exp: Date.now() + (TTL[role] || TTL.admin)
  };
  const body = Buffer.from(JSON.stringify(payload)).toString('base64url');
  return `${body}.${sign(body)}`;
}

// Vérifie un jeton. Renvoie le contenu ({sub, role, exp}) ou null.
// `expected.role` (chaîne ou liste) et `expected.sub` exigent un rôle et un compte précis.
function verifyToken(token, expected = {}) {
  if (!SECRET || !token || typeof token !== 'string') return null;
  const dot = token.lastIndexOf('.');
  if (dot <= 0) return null;
  const body = token.slice(0, dot);
  const sig = token.slice(dot + 1);
  const good = sign(body);
  // Comparaison à temps constant, après égalisation de longueur
  if (sig.length !== good.length) return null;
  if (!crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(good))) return null;

  let payload;
  try { payload = JSON.parse(Buffer.from(body, 'base64url').toString('utf8')); } catch { return null; }
  if (!payload || typeof payload.exp !== 'number' || Date.now() > payload.exp) return null;
  if (expected.role) {
    const roles = Array.isArray(expected.role) ? expected.role : [expected.role];
    if (!roles.includes(payload.role)) return null;
  }
  if (expected.sub && payload.sub !== String(expected.sub).toLowerCase().trim()) return null;
  return payload;
}

module.exports = { issueToken, verifyToken, TTL };
