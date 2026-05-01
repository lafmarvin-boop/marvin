#!/usr/bin/env python3
"""
Serves graphify context on http://localhost:7842
- /context  : dernier résumé + stats graphe (injecté au démarrage)
- /sessions : toutes les sessions RESUME_*.md concaténées (pour /reprise-de-session)
"""
from http.server import HTTPServer, BaseHTTPRequestHandler
from pathlib import Path
import json

GRAPHIFY_ROOT = Path("/home/user/marvin")
PORT = 7842


def graph_stats() -> str:
    graph_json = GRAPHIFY_ROOT / "graphify-out" / "graph.json"
    if not graph_json.exists():
        return ""
    try:
        g = json.loads(graph_json.read_text())
    except (OSError, json.JSONDecodeError):
        return ""
    nodes = g.get("nodes", [])
    links = g.get("links", [])
    degree = {}
    for link in links:
        for key in ("_src", "_tgt"):
            nid = link.get(key, "")
            if nid:
                degree[nid] = degree.get(nid, 0) + 1
    node_map = {n["id"]: n.get("label", n["id"]) for n in nodes}
    top = sorted(degree.items(), key=lambda x: -x[1])[:5]
    gods = ", ".join(node_map.get(nid, nid) for nid, _ in top)
    return f"**Graphe Marvin** : {len(nodes)} nœuds · {len(links)} arêtes\n**Nœuds centraux** : {gods}"


def build_context() -> str:
    parts = []
    resumes = sorted(GRAPHIFY_ROOT.glob("raw/conversations/RESUME_*.md"), reverse=True)
    if resumes:
        lines = resumes[0].read_text(encoding="utf-8").splitlines()
        parts.append("\n".join(lines[:60]))
    stats = graph_stats()
    if stats:
        parts.append(f"---\n{stats}")
    return "\n\n".join(parts)


def build_sessions() -> str:
    resumes = sorted(GRAPHIFY_ROOT.glob("raw/conversations/RESUME_*.md"), reverse=True)
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


class Handler(BaseHTTPRequestHandler):
    routes = {
        "/context": build_context,
        "/sessions": build_sessions,
    }

    def do_GET(self):
        fn = self.routes.get(self.path)
        if fn is None:
            self.send_response(404)
            self.end_headers()
            return
        try:
            body = fn().encode("utf-8")
        except Exception as exc:
            err = f"[graphify-server] {exc}".encode("utf-8")
            self.send_response(500)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(err)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(err)
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"[graphify-server] listening on http://localhost:{PORT}")
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
