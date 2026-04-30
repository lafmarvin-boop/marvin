#!/usr/bin/env python3
"""
Serves graphify context on http://localhost:7842/context
Used by the Tampermonkey userscript on claude.ai.
"""
from http.server import HTTPServer, BaseHTTPRequestHandler
from pathlib import Path
import json, glob, os

GRAPHIFY_ROOT = Path("/home/user/marvin")
PORT = 7842


def build_context() -> str:
    parts = []

    # 1. Latest session resume
    resumes = sorted(GRAPHIFY_ROOT.glob("raw/conversations/RESUME_*.md"), reverse=True)
    if resumes:
        lines = resumes[0].read_text(encoding="utf-8").splitlines()
        parts.append("\n".join(lines[:60]))

    # 2. Graph stats
    graph_json = GRAPHIFY_ROOT / "graphify-out" / "graph.json"
    if graph_json.exists():
        g = json.loads(graph_json.read_text())
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

        parts.append(
            f"\n---\n**Graphe Marvin** : {len(nodes)} nœuds · {len(links)} arêtes\n"
            f"**Nœuds centraux** : {gods}"
        )

    return "\n\n".join(parts) if parts else ""


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/context":
            self.send_response(404)
            self.end_headers()
            return

        context = build_context()
        body = context.encode("utf-8")

        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass  # silent


if __name__ == "__main__":
    print(f"[graphify-server] listening on http://localhost:{PORT}/context")
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
