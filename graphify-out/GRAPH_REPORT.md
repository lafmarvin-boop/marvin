# Graph Report - .  (2026-04-30)

## Corpus Check
- Corpus is ~0 words - fits in a single context window. You may not need a graph.

## Summary
- 77 nodes · 62 edges · 26 communities detected
- Extraction: 81% EXTRACTED · 19% INFERRED · 0% AMBIGUOUS · INFERRED: 12 edges (avg confidence: 0.83)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_HTTP Auth & Client Models|HTTP Auth & Client Models]]
- [[_COMMUNITY_Graphify CLI & Platform Install|Graphify CLI & Platform Install]]
- [[_COMMUNITY_Multi-Language AST Extraction|Multi-Language AST Extraction]]
- [[_COMMUNITY_Graph Export & Visualization|Graph Export & Visualization]]
- [[_COMMUNITY_Clustering & Community Detection|Clustering & Community Detection]]
- [[_COMMUNITY_Graph Build & Core Pipeline|Graph Build & Core Pipeline]]
- [[_COMMUNITY_Export (HTMLCypherObsidian)|Export (HTML/Cypher/Obsidian)]]
- [[_COMMUNITY_Graph Analysis & Surprises|Graph Analysis & Surprises]]
- [[_COMMUNITY_Ingest & URL Fetching|Ingest & URL Fetching]]
- [[_COMMUNITY_File Detection & Classification|File Detection & Classification]]
- [[_COMMUNITY_Sample API & CRUD Handlers|Sample API & CRUD Handlers]]
- [[_COMMUNITY_Code Analysis Utilities|Code Analysis Utilities]]
- [[_COMMUNITY_API Client Fixtures|API Client Fixtures]]
- [[_COMMUNITY_User Models & Cache Layer|User Models & Cache Layer]]
- [[_COMMUNITY_Documentation & Architecture|Documentation & Architecture]]
- [[_COMMUNITY_AudioVideo Transcription|Audio/Video Transcription]]
- [[_COMMUNITY_Git Hooks Integration|Git Hooks Integration]]
- [[_COMMUNITY_Graph Build Utilities|Graph Build Utilities]]
- [[_COMMUNITY_Token Benchmarking|Token Benchmarking]]
- [[_COMMUNITY_httpx Worked Example|httpx Worked Example]]
- [[_COMMUNITY_PHP Event Listeners|PHP Event Listeners]]
- [[_COMMUNITY_OOP Class Fixtures (Animal)|OOP Class Fixtures (Animal)]]
- [[_COMMUNITY_PHP DI Container|PHP DI Container]]
- [[_COMMUNITY_PHP Static Properties|PHP Static Properties]]
- [[_COMMUNITY_Transformer Model Fixture|Transformer Model Fixture]]
- [[_COMMUNITY_Package Init|Package Init]]

## God Nodes (most connected - your core abstractions)
1. `Graphify` - 32 edges
2. `httpx Graph Report` - 5 edges
3. `AST Extraction Pass` - 4 edges
4. `Knowledge Graph Output` - 4 edges
5. `Graphify Pipeline (detect→extract→build→cluster→analyze→report→export)` - 4 edges
6. `Transformer Architecture (Vaswani et al. 2017)` - 4 edges
7. `Semantic Extraction (Claude Subagents)` - 3 edges
8. `Persistent graph.json` - 3 edges
9. `Multi-Head Attention (h=8 parallel heads)` - 3 edges
10. `Encoder-Decoder Structure (6 layers each)` - 3 edges

## Surprising Connections (you probably didn't know these)
- `Graphify Logo Icon (SVG)` --conceptually_related_to--> `Graphify`  [INFERRED]
  graphify/docs/logo-icon.svg → graphify/README.md
- `Graphify Logo with Text (SVG)` --conceptually_related_to--> `Graphify`  [INFERRED]
  graphify/docs/logo-text.svg → graphify/README.md
- `Graphify Skill for Claude Code` --references--> `Graphify`  [EXTRACTED]
  graphify/graphify/skill.md → graphify/README.md
- `Graphify Skill for Codex` --references--> `Graphify`  [EXTRACTED]
  graphify/graphify/skill-codex.md → graphify/README.md
- `Mixed Corpus Example (docs + code)` --references--> `Graphify`  [EXTRACTED]
  graphify/worked/mixed-corpus/README.md → graphify/README.md

## Hyperedges (group relationships)
- **All Platform Skills (Claude, Codex, Aider, Kiro, Copilot, VS Code, Trae, Droid, Claw, OpenCode, Windows)** — skill_claude, skill_codex, skill_opencode, skill_aider, skill_kiro, skill_copilot, skill_vscode, skill_trae, skill_droid, skill_claw, skill_windows [EXTRACTED 1.00]
- **Three Core Outputs (HTML + JSON + Report)** — readme_graph_html, readme_graph_json, readme_graph_report [EXTRACTED 1.00]
- **Three Extraction Passes (AST + Whisper + Semantic)** — readme_ast_extraction, readme_semantic_extraction, readme_leiden_clustering [EXTRACTED 0.90]
- **All README Translations (25+ languages)** — readme_nl, readme_ko, readme_fr, readme_pl, readme_no, readme_fi, readme_ar, readme_tr, readme_sv, readme_pt, readme_da, readme_de, readme_ro, readme_zh_cn, readme_zh_tw [EXTRACTED 1.00]
- **Worked Examples (karpathy + httpx)** — karpathy_repos_readme, httpx_readme, worked_mixed_corpus [EXTRACTED 0.90]
- **Transformer Core Components (Attention + Encoder/Decoder + Positional Encoding)** — attention_multihead, attention_encoder_decoder, attention_positional_encoding, attention_layer_norm [EXTRACTED 1.00]

## Communities

### Community 0 - "HTTP Auth & Client Models"
Cohesion: 0.08
Nodes (24): Test Fixture Sample (Markdown), README (Arabic), README (German), README (Greek), README (Spanish), README (French), Graphify, README (Hindi) (+16 more)

### Community 1 - "Graphify CLI & Platform Install"
Cohesion: 0.22
Nodes (9): httpx AsyncClient (god node), httpx Client (god node - 26 edges), httpx Example, httpx Graph Report, httpx Response (god node), httpx Review, Karpathy Repos Example, Karpathy Repos Graph Report (+1 more)

### Community 2 - "Multi-Language AST Extraction"
Cohesion: 0.33
Nodes (7): Confidence Labels (EXTRACTED/INFERRED/AMBIGUOUS), Extraction Output Schema (nodes/edges dict), Graphify Pipeline (detect→extract→build→cluster→analyze→report→export), Cache Namespace Fix (ast/ vs semantic/ subdirs), AST Extraction Pass, Leiden Community Detection, Semantic Extraction (Claude Subagents)

### Community 3 - "Graph Export & Visualization"
Cohesion: 0.33
Nodes (7): Encoder-Decoder Structure (6 layers each), Layer Normalization + Residual Connection, Multi-Head Attention (h=8 parallel heads), Positional Encoding (sin/cos), Scaled Dot-Product Attention, Transformer Architecture (Vaswani et al. 2017), Mixed Corpus Example (docs + code)

### Community 4 - "Clustering & Community Detection"
Cohesion: 0.33
Nodes (6): Shrink Guard (prevents overwriting graph with smaller data), Interactive HTML Visualization, Persistent graph.json, GRAPH_REPORT.md (audit report), Knowledge Graph Output, validate_graph_path() (must resolve inside graphify-out/)

### Community 5 - "Graph Build & Core Pipeline"
Cohesion: 1.0
Nodes (2): Graphify Logo Icon (SVG), Graphify Logo with Text (SVG)

### Community 6 - "Export (HTML/Cypher/Obsidian)"
Cohesion: 1.0
Nodes (2): Graphify Skill for Claude Code, Graphify Skill for Codex

### Community 7 - "Graph Analysis & Surprises"
Cohesion: 1.0
Nodes (2): safe_fetch() (size cap + timeout), validate_url() (security)

### Community 8 - "Ingest & URL Fetching"
Cohesion: 1.0
Nodes (1): .graphifyignore (exclusion file)

### Community 9 - "File Detection & Classification"
Cohesion: 1.0
Nodes (1): sanitize_label() (strips control chars)

### Community 10 - "Sample API & CRUD Handlers"
Cohesion: 1.0
Nodes (1): Graphify Skill for OpenClaw

### Community 11 - "Code Analysis Utilities"
Cohesion: 1.0
Nodes (1): Graphify Skill for Trae

### Community 12 - "API Client Fixtures"
Cohesion: 1.0
Nodes (1): Graphify Skill for Windows

### Community 13 - "User Models & Cache Layer"
Cohesion: 1.0
Nodes (1): Graphify Skill for Factory Droid

### Community 14 - "Documentation & Architecture"
Cohesion: 1.0
Nodes (1): README (Polish)

### Community 15 - "Audio/Video Transcription"
Cohesion: 1.0
Nodes (1): README (Norwegian)

### Community 16 - "Git Hooks Integration"
Cohesion: 1.0
Nodes (1): README (Finnish)

### Community 17 - "Graph Build Utilities"
Cohesion: 1.0
Nodes (1): README (Swedish)

### Community 18 - "Token Benchmarking"
Cohesion: 1.0
Nodes (1): README (Danish)

### Community 19 - "httpx Worked Example"
Cohesion: 1.0
Nodes (1): README (Romanian)

### Community 20 - "PHP Event Listeners"
Cohesion: 1.0
Nodes (1): README (Traditional Chinese)

### Community 21 - "OOP Class Fixtures (Animal)"
Cohesion: 1.0
Nodes (1): README (Czech)

### Community 22 - "PHP DI Container"
Cohesion: 1.0
Nodes (1): README (Indonesian)

### Community 23 - "PHP Static Properties"
Cohesion: 1.0
Nodes (1): README (Vietnamese)

### Community 24 - "Transformer Model Fixture"
Cohesion: 1.0
Nodes (1): README (Ukrainian)

### Community 25 - "Package Init"
Cohesion: 1.0
Nodes (1): README (Hungarian)

## Knowledge Gaps
- **57 isolated node(s):** `Raw Folder Workflow (Karpathy)`, `Interactive HTML Visualization`, `GRAPH_REPORT.md (audit report)`, `.graphifyignore (exclusion file)`, `Confidence Labels (EXTRACTED/INFERRED/AMBIGUOUS)` (+52 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `Graph Build & Core Pipeline`** (2 nodes): `Graphify Logo Icon (SVG)`, `Graphify Logo with Text (SVG)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Export (HTML/Cypher/Obsidian)`** (2 nodes): `Graphify Skill for Claude Code`, `Graphify Skill for Codex`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Graph Analysis & Surprises`** (2 nodes): `safe_fetch() (size cap + timeout)`, `validate_url() (security)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Ingest & URL Fetching`** (1 nodes): `.graphifyignore (exclusion file)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `File Detection & Classification`** (1 nodes): `sanitize_label() (strips control chars)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Sample API & CRUD Handlers`** (1 nodes): `Graphify Skill for OpenClaw`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Code Analysis Utilities`** (1 nodes): `Graphify Skill for Trae`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `API Client Fixtures`** (1 nodes): `Graphify Skill for Windows`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `User Models & Cache Layer`** (1 nodes): `Graphify Skill for Factory Droid`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Documentation & Architecture`** (1 nodes): `README (Polish)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Audio/Video Transcription`** (1 nodes): `README (Norwegian)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Git Hooks Integration`** (1 nodes): `README (Finnish)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Graph Build Utilities`** (1 nodes): `README (Swedish)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Token Benchmarking`** (1 nodes): `README (Danish)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `httpx Worked Example`** (1 nodes): `README (Romanian)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `PHP Event Listeners`** (1 nodes): `README (Traditional Chinese)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `OOP Class Fixtures (Animal)`** (1 nodes): `README (Czech)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `PHP DI Container`** (1 nodes): `README (Indonesian)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `PHP Static Properties`** (1 nodes): `README (Vietnamese)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Transformer Model Fixture`** (1 nodes): `README (Ukrainian)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Package Init`** (1 nodes): `README (Hungarian)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Graphify` connect `HTTP Auth & Client Models` to `Multi-Language AST Extraction`, `Graph Export & Visualization`, `Clustering & Community Detection`, `Graph Build & Core Pipeline`, `Export (HTML/Cypher/Obsidian)`?**
  _High betweenness centrality (0.359) - this node is a cross-community bridge._
- **Why does `Mixed Corpus Example (docs + code)` connect `Graph Export & Visualization` to `HTTP Auth & Client Models`?**
  _High betweenness centrality (0.086) - this node is a cross-community bridge._
- **Why does `Knowledge Graph Output` connect `Clustering & Community Detection` to `HTTP Auth & Client Models`?**
  _High betweenness centrality (0.076) - this node is a cross-community bridge._
- **Are the 3 inferred relationships involving `Graphify` (e.g. with `Test Fixture Sample (Markdown)` and `Graphify Logo Icon (SVG)`) actually correct?**
  _`Graphify` has 3 INFERRED edges - model-reasoned connections that need verification._
- **Are the 2 inferred relationships involving `AST Extraction Pass` (e.g. with `Semantic Extraction (Claude Subagents)` and `Cache Namespace Fix (ast/ vs semantic/ subdirs)`) actually correct?**
  _`AST Extraction Pass` has 2 INFERRED edges - model-reasoned connections that need verification._
- **What connects `Raw Folder Workflow (Karpathy)`, `Interactive HTML Visualization`, `GRAPH_REPORT.md (audit report)` to the rest of the system?**
  _57 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `HTTP Auth & Client Models` be split into smaller, more focused modules?**
  _Cohesion score 0.08 - nodes in this community are weakly interconnected._