#!/usr/bin/env python3
"""
Marvin context + Pocket server on http://localhost:7842

Existing (used by the Tampermonkey userscript):
  GET  /context   : last RESUME_*.md + graph stats (plain text)
  GET  /sessions  : all RESUME_*.md concatenated (plain text)

Marvin Pocket PWA backend:
  GET  /          : serves the PWA (index.html + static assets)
  GET  /api/recap : latest resume + graph stats (JSON)
  GET  /api/search?q=...&limit=20 : full-text search across conversations
  POST /api/capture {"text": "...", "title": "..."} : save a voice/text note
  POST /api/ask    {"question": "..."} : ask Claude with Marvin context
                   (only available if ANTHROPIC_API_KEY env var is set)

Env vars:
  MARVIN_HOST       : bind address (default 127.0.0.1; use 0.0.0.0 for LAN/Tailscale)
  MARVIN_PORT       : port (default 7842)
  ANTHROPIC_API_KEY : enables /api/ask
  ANTHROPIC_MODEL   : default claude-sonnet-4-6
"""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlparse, parse_qs
from datetime import datetime
import json
import os
import re
import uuid
import urllib.request
import urllib.error

GRAPHIFY_ROOT = Path("/home/user/marvin")
PWA_ROOT = GRAPHIFY_ROOT / "tools" / "marvin-pocket"
CONVERSATIONS_DIR = GRAPHIFY_ROOT / "raw" / "conversations"

HOST = os.environ.get("MARVIN_HOST", "127.0.0.1")
PORT = int(os.environ.get("MARVIN_PORT", "7842"))
ANTHROPIC_API_KEY = os.environ.get("ANTHROPIC_API_KEY", "")
ANTHROPIC_MODEL = os.environ.get("ANTHROPIC_MODEL", "claude-sonnet-4-6")

STATIC_TYPES = {
    ".html": "text/html; charset=utf-8",
    ".js": "application/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".webmanifest": "application/manifest+json",
    ".png": "image/png",
    ".svg": "image/svg+xml",
    ".ico": "image/x-icon",
}


# ---------- shared helpers ----------

def graph_stats() -> str:
    graph_json = GRAPHIFY_ROOT / "graphify-out" / "graph.json"
    if not graph_json.exists():
        return ""
    g = json.loads(graph_json.read_text())
    nodes = g.get("nodes", [])
    links = g.get("links", [])
    degree: dict[str, int] = {}
    for link in links:
        for key in ("_src", "_tgt"):
            nid = link.get(key, "")
            if nid:
                degree[nid] = degree.get(nid, 0) + 1
    node_map = {n["id"]: n.get("label", n["id"]) for n in nodes}
    top = sorted(degree.items(), key=lambda x: -x[1])[:5]
    gods = ", ".join(node_map.get(nid, nid) for nid, _ in top)
    return f"**Graphe Marvin** : {len(nodes)} nœuds · {len(links)} arêtes\n**Nœuds centraux** : {gods}"


def graph_stats_dict() -> dict:
    graph_json = GRAPHIFY_ROOT / "graphify-out" / "graph.json"
    if not graph_json.exists():
        return {"nodes": 0, "edges": 0, "central": []}
    g = json.loads(graph_json.read_text())
    nodes = g.get("nodes", [])
    links = g.get("links", [])
    degree: dict[str, int] = {}
    for link in links:
        for key in ("_src", "_tgt"):
            nid = link.get(key, "")
            if nid:
                degree[nid] = degree.get(nid, 0) + 1
    node_map = {n["id"]: n.get("label", n["id"]) for n in nodes}
    top = sorted(degree.items(), key=lambda x: -x[1])[:5]
    return {
        "nodes": len(nodes),
        "edges": len(links),
        "central": [node_map.get(nid, nid) for nid, _ in top],
    }


def latest_resume_text() -> str:
    resumes = sorted(CONVERSATIONS_DIR.glob("RESUME_*.md"), reverse=True)
    if not resumes:
        return ""
    return resumes[0].read_text(encoding="utf-8")


def build_context() -> str:
    parts = []
    resume = latest_resume_text()
    if resume:
        parts.append("\n".join(resume.splitlines()[:60]))
    stats = graph_stats()
    if stats:
        parts.append(f"---\n{stats}")
    return "\n\n".join(parts)


def build_sessions() -> str:
    resumes = sorted(CONVERSATIONS_DIR.glob("RESUME_*.md"), reverse=True)
    if not resumes:
        return "Aucun résumé de session trouvé."
    parts = [
        "# Mémoire du projet Marvin — toutes les sessions\n"
        "_Contexte injecté via /reprise-de-session_\n"
    ]
    for r in resumes:
        parts.append(r.read_text(encoding="utf-8").strip())
        parts.append("\n---\n")
    stats = graph_stats()
    if stats:
        parts.append(stats)
    return "\n\n".join(parts)


# ---------- API handlers ----------

def api_recap() -> dict:
    resume = latest_resume_text()
    title = ""
    body = resume
    if resume:
        lines = resume.splitlines()
        if lines and lines[0].startswith("#"):
            title = lines[0].lstrip("# ").strip()
            body = "\n".join(lines[1:]).strip()
    return {
        "resume": {"title": title, "body": body[:4000]},
        "graph": graph_stats_dict(),
        "conversation_count": len(list(CONVERSATIONS_DIR.glob("*.md"))),
    }


def api_search(query: str, limit: int = 20) -> dict:
    if not query or len(query) < 2:
        return {"query": query, "results": []}
    q = query.lower()
    results = []
    for path in sorted(CONVERSATIONS_DIR.glob("*.md"), reverse=True):
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        lower = text.lower()
        idx = lower.find(q)
        if idx == -1:
            continue
        start = max(0, idx - 80)
        end = min(len(text), idx + len(q) + 160)
        snippet = text[start:end].replace("\n", " ").strip()
        if start > 0:
            snippet = "…" + snippet
        if end < len(text):
            snippet = snippet + "…"
        first_line = text.splitlines()[0] if text else ""
        title = first_line.lstrip("# ").strip() or path.stem
        results.append({
            "file": path.name,
            "title": title,
            "snippet": snippet,
            "matches": lower.count(q),
        })
        if len(results) >= limit:
            break
    return {"query": query, "results": results}


def api_capture(payload: dict) -> dict:
    text = (payload.get("text") or "").strip()
    if not text:
        return {"ok": False, "error": "empty text"}
    title = (payload.get("title") or "").strip()
    if not title:
        first = text.splitlines()[0].strip()
        title = (first[:60] + "…") if len(first) > 60 else (first or "Capture")
    now = datetime.now()
    session = uuid.uuid4().hex[:8]
    filename = f"{now:%Y-%m-%d_%H-%M-%S}_{session}.md"
    path = CONVERSATIONS_DIR / filename
    body = (
        f"# {title}\n"
        f"_Session: {uuid.uuid4()} — {now:%Y-%m-%d %H:%M}_\n"
        f"_Source: marvin-pocket capture_\n\n"
        f"## **User**\n{text}\n"
    )
    path.write_text(body, encoding="utf-8")
    return {"ok": True, "file": filename, "path": str(path)}


def api_ask(payload: dict) -> dict:
    if not ANTHROPIC_API_KEY:
        return {"ok": False, "error": "ANTHROPIC_API_KEY not set on server"}
    question = (payload.get("question") or "").strip()
    if not question:
        return {"ok": False, "error": "empty question"}
    system = (
        "Tu es Marvin, l'assistant de mémoire personnel de l'utilisateur. "
        "Tu as accès au contexte ci-dessous, qui résume les sessions précédentes "
        "et la structure du graphe de connaissances du projet. Réponds en français, "
        "concis et concret.\n\n"
        "=== CONTEXTE MARVIN ===\n"
        + build_context()
    )
    body = json.dumps({
        "model": ANTHROPIC_MODEL,
        "max_tokens": 1024,
        "system": system,
        "messages": [{"role": "user", "content": question}],
    }).encode("utf-8")
    req = urllib.request.Request(
        "https://api.anthropic.com/v1/messages",
        data=body,
        method="POST",
        headers={
            "content-type": "application/json",
            "x-api-key": ANTHROPIC_API_KEY,
            "anthropic-version": "2023-06-01",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return {"ok": False, "error": f"HTTP {e.code}", "detail": e.read().decode("utf-8", "ignore")[:500]}
    except urllib.error.URLError as e:
        return {"ok": False, "error": str(e.reason)}
    answer = ""
    for block in data.get("content", []):
        if block.get("type") == "text":
            answer += block.get("text", "")
    return {"ok": True, "answer": answer, "model": data.get("model", ANTHROPIC_MODEL)}


# ---------- HTTP layer ----------

class Handler(BaseHTTPRequestHandler):
    def _write(self, status: int, body: bytes, content_type: str = "text/plain; charset=utf-8") -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "content-type")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, status: int, payload: dict) -> None:
        self._write(status, json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                    "application/json; charset=utf-8")

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        try:
            return json.loads(raw.decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            return {}

    def _serve_static(self, rel_path: str) -> None:
        if not rel_path or rel_path.endswith("/"):
            rel_path = (rel_path or "") + "index.html"
        if ".." in rel_path.split("/"):
            self._write(403, b"forbidden")
            return
        path = (PWA_ROOT / rel_path).resolve()
        try:
            path.relative_to(PWA_ROOT.resolve())
        except ValueError:
            self._write(403, b"forbidden")
            return
        if not path.exists() or not path.is_file():
            self._write(404, b"not found")
            return
        ctype = STATIC_TYPES.get(path.suffix, "application/octet-stream")
        self._write(200, path.read_bytes(), ctype)

    def do_OPTIONS(self):
        self._write(204, b"")

    def do_GET(self):
        url = urlparse(self.path)
        path = url.path
        if path == "/context":
            self._write(200, build_context().encode("utf-8"))
            return
        if path == "/sessions":
            self._write(200, build_sessions().encode("utf-8"))
            return
        if path == "/api/recap":
            self._json(200, api_recap())
            return
        if path == "/api/search":
            qs = parse_qs(url.query)
            q = (qs.get("q", [""])[0] or "").strip()
            try:
                limit = int(qs.get("limit", ["20"])[0])
            except ValueError:
                limit = 20
            self._json(200, api_search(q, limit))
            return
        if path == "/api/health":
            self._json(200, {
                "ok": True,
                "ask_enabled": bool(ANTHROPIC_API_KEY),
                "model": ANTHROPIC_MODEL,
            })
            return
        if path == "/" or not path.startswith("/api/"):
            self._serve_static(path.lstrip("/"))
            return
        self._write(404, b"not found")

    def do_POST(self):
        url = urlparse(self.path)
        if url.path == "/api/capture":
            self._json(200, api_capture(self._read_json()))
            return
        if url.path == "/api/ask":
            self._json(200, api_ask(self._read_json()))
            return
        self._write(404, b"not found")

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"[marvin-server] listening on http://{HOST}:{PORT} (ask_enabled={bool(ANTHROPIC_API_KEY)})")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
