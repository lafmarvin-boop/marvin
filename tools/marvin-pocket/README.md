# Marvin Pocket

PWA (Progressive Web App) qui transforme ton téléphone en façade mobile pour Marvin.

## Fonctions (v1)

- **Recap** : dernier `RESUME_*.md` + stats du graphe (nœuds, arêtes, nœuds centraux).
- **Capture** : note vocale (Web Speech API) ou texte, sauvegardée dans `raw/conversations/`. Sera re-graphifiée au prochain run.
- **Ask Marvin** : question à Claude avec ton contexte Marvin auto-injecté en system prompt. Nécessite `ANTHROPIC_API_KEY` côté serveur.
- **Search** : full-text dans les 82 conversations, snippets surlignés.

## Architecture

```
Téléphone Android (Chrome)
        │
        │ HTTPS via Tailscale (ou HTTP local sur LAN)
        ▼
  graphify-context-server.py  ← étendu avec /api/*
        │
        ├── raw/conversations/*.md   (lecture + écriture pour capture)
        ├── graphify-out/graph.json  (lecture pour stats)
        └── api.anthropic.com        (proxy /api/ask)
```

## Installation

### 1. Lancer le serveur étendu

Le service systemd `tools/graphify-server.service` continue de marcher tel quel — la PWA et les endpoints `/api/*` sont servis sur le même port `7842`.

Pour activer **Ask Marvin**, ajoute la clé API au service :

```bash
sudo systemctl edit graphify-server
```

```ini
[Service]
Environment="ANTHROPIC_API_KEY=sk-ant-..."
Environment="ANTHROPIC_MODEL=claude-sonnet-4-6"
Environment="MARVIN_HOST=0.0.0.0"
```

`MARVIN_HOST=0.0.0.0` n'est nécessaire que pour exposer sur ton LAN ou Tailscale. Garde `127.0.0.1` (défaut) sinon.

```bash
sudo systemctl restart graphify-server
curl http://127.0.0.1:7842/api/health
```

### 2. Exposer le serveur au téléphone

**Option A — même Wi-Fi (le plus simple)**

```bash
# Trouve l'IP de la machine sur le LAN
ip -4 addr show | grep inet
```

Puis sur le tel : `http://<IP-LAN>:7842/`

**Option B — Tailscale (accessible partout)**

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
sudo tailscale ip -4   # → IP 100.x.y.z
```

Installe Tailscale sur le tel (Play Store), connecte-toi au même compte. Sur le tel : `http://100.x.y.z:7842/`.

### 3. Installer la PWA sur l'écran d'accueil

1. Ouvre l'URL dans **Chrome Android**.
2. Menu (⋮) → **Ajouter à l'écran d'accueil** → confirme.
3. Icône Marvin disponible comme une vraie app, plein écran, fonctionne offline pour la coquille (les API restent en ligne).

## Endpoints

| Méthode | Path | Réponse |
|---|---|---|
| GET | `/` | PWA |
| GET | `/api/health` | `{ok, ask_enabled, model}` |
| GET | `/api/recap` | `{resume, graph, conversation_count}` |
| GET | `/api/search?q=...&limit=20` | `{query, results[]}` |
| POST | `/api/capture` body: `{text, title?}` | `{ok, file, path}` |
| POST | `/api/ask` body: `{question}` | `{ok, answer, model}` |
| GET | `/context` | (legacy) texte pour Tampermonkey |
| GET | `/sessions` | (legacy) texte pour `/reprise-de-session` |

## Limites connues

- **Web Speech API** : marche bien sur Chrome Android (envoie l'audio à Google), pas dispo sur Firefox mobile. La transcription se fait côté Google, pas chez toi — si tu veux du 100% local, il faudra passer à Whisper côté serveur (todo v2).
- **Pas d'auth** : le serveur est ouvert à tout ce qui peut joindre le port. C'est OK derrière Tailscale (tunnel privé). À ne **pas** exposer directement sur Internet sans ajouter un token.
- **`/api/ask`** consomme ta clé Anthropic à chaque question. Pas de limite côté serveur.

## v2 idées

- Push d'un nouveau resume après chaque capture
- Vue graphe interactive (réutiliser `graphify-out/graph.html`)
- Whisper local pour transcription privée
- Auth par token simple (`?key=...`)
- Widget Android via Trusted Web Activity ou Bubblewrap
