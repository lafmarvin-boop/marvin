# Graph Report - /home/user/marvin  (2026-05-01)

## Corpus Check
- Corpus is ~0 words - fits in a single context window. You may not need a graph.

## Summary
- 1559 nodes · 3245 edges · 62 communities detected
- Extraction: 63% EXTRACTED · 37% INFERRED · 0% AMBIGUOUS · INFERRED: 1198 edges (avg confidence: 0.74)
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
- [[_COMMUNITY_Security & URL Validation|Security & URL Validation]]
- [[_COMMUNITY_Manifest Module|Manifest Module]]
- [[_COMMUNITY_Test Init|Test Init]]
- [[_COMMUNITY_Graphifyignore|Graphifyignore]]
- [[_COMMUNITY_Label Sanitization|Label Sanitization]]
- [[_COMMUNITY_OpenClaw Skill|OpenClaw Skill]]
- [[_COMMUNITY_Factory Droid Skill|Factory Droid Skill]]
- [[_COMMUNITY_README (Polish)|README (Polish)]]
- [[_COMMUNITY_README (Norwegian)|README (Norwegian)]]
- [[_COMMUNITY_README (Finnish)|README (Finnish)]]
- [[_COMMUNITY_README (Swedish)|README (Swedish)]]
- [[_COMMUNITY_README (Danish)|README (Danish)]]
- [[_COMMUNITY_README (Romanian)|README (Romanian)]]
- [[_COMMUNITY_README (Traditional Chinese)|README (Traditional Chinese)]]
- [[_COMMUNITY_README (Czech)|README (Czech)]]
- [[_COMMUNITY_README (Indonesian)|README (Indonesian)]]
- [[_COMMUNITY_README (Vietnamese)|README (Vietnamese)]]
- [[_COMMUNITY_README (Ukrainian)|README (Ukrainian)]]
- [[_COMMUNITY_README (Hungarian)|README (Hungarian)]]
- [[_COMMUNITY_Community 47|Community 47]]
- [[_COMMUNITY_Community 48|Community 48]]
- [[_COMMUNITY_Community 49|Community 49]]
- [[_COMMUNITY_Community 50|Community 50]]
- [[_COMMUNITY_Community 51|Community 51]]
- [[_COMMUNITY_Community 52|Community 52]]
- [[_COMMUNITY_Community 53|Community 53]]
- [[_COMMUNITY_Community 54|Community 54]]
- [[_COMMUNITY_Community 55|Community 55]]
- [[_COMMUNITY_Community 56|Community 56]]
- [[_COMMUNITY_Community 57|Community 57]]
- [[_COMMUNITY_Community 58|Community 58]]
- [[_COMMUNITY_Community 59|Community 59]]
- [[_COMMUNITY_Community 60|Community 60]]
- [[_COMMUNITY_Community 61|Community 61]]
- [[_COMMUNITY_Community 62|Community 62]]
- [[_COMMUNITY_Community 63|Community 63]]

## God Nodes (most connected - your core abstractions)
1. `Response` - 47 edges
2. `Request` - 43 edges
3. `build_from_json()` - 41 edges
4. `main()` - 37 edges
5. `cluster()` - 36 edges
6. `_labels()` - 34 edges
7. `_make_id()` - 32 edges
8. `Graphify` - 32 edges
9. `detect()` - 31 edges
10. `Cookies` - 28 edges

## Surprising Connections (you probably didn't know these)
- `test_make_id_strips_dots_and_underscores()` --calls--> `_make_id()`  [INFERRED]
  tests/test_extract.py → graphify/extract.py
- `test_make_id_no_leading_trailing_underscores()` --calls--> `_make_id()`  [INFERRED]
  tests/test_extract.py → graphify/extract.py
- `test_watch_raises_without_watchdog()` --calls--> `watch()`  [INFERRED]
  tests/test_watch.py → graphify/watch.py
- `test_count_words_sample_md()` --calls--> `count_words()`  [INFERRED]
  tests/test_detect.py → graphify/detect.py
- `_query_subgraph_tokens()` --calls--> `add()`  [INFERRED]
  graphify/benchmark.py → tests/fixtures/sample.zig

## Hyperedges (group relationships)
- **All Platform Skills (Claude, Codex, Aider, Kiro, Copilot, VS Code, Trae, Droid, Claw, OpenCode, Windows)** — skill_claude, skill_codex, skill_opencode, skill_aider, skill_kiro, skill_copilot, skill_vscode, skill_trae, skill_droid, skill_claw, skill_windows [EXTRACTED 1.00]
- **Three Core Outputs (HTML + JSON + Report)** — readme_graph_html, readme_graph_json, readme_graph_report [EXTRACTED 1.00]
- **Three Extraction Passes (AST + Whisper + Semantic)** — readme_ast_extraction, readme_semantic_extraction, readme_leiden_clustering [EXTRACTED 0.90]
- **All README Translations (25+ languages)** — readme_nl, readme_ko, readme_fr, readme_pl, readme_no, readme_fi, readme_ar, readme_tr, readme_sv, readme_pt, readme_da, readme_de, readme_ro, readme_zh_cn, readme_zh_tw [EXTRACTED 1.00]
- **Worked Examples (karpathy + httpx)** — karpathy_repos_readme, httpx_readme, worked_mixed_corpus [EXTRACTED 0.90]
- **Transformer Core Components (Attention + Encoder/Decoder + Positional Encoding)** — attention_multihead, attention_encoder_decoder, attention_positional_encoding, attention_layer_norm [EXTRACTED 1.00]
- **All Platform Skills (Claude, Codex, Aider, Kiro, Copilot, VS Code, Trae, Droid, Claw, OpenCode, Windows)** — skill_claude, skill_codex, skill_opencode, skill_aider, skill_kiro, skill_copilot, skill_vscode, skill_trae, skill_droid, skill_claw, skill_windows [EXTRACTED 1.00]
- **Three Core Outputs (HTML + JSON + Report)** — readme_graph_html, readme_graph_json, readme_graph_report [EXTRACTED 1.00]
- **Three Extraction Passes (AST + Whisper + Semantic)** — readme_ast_extraction, readme_semantic_extraction, readme_leiden_clustering [EXTRACTED 0.90]
- **All README Translations (25+ languages)** — readme_nl, readme_ko, readme_fr, readme_pl, readme_no, readme_fi, readme_ar, readme_tr, readme_sv, readme_pt, readme_da, readme_de, readme_ro, readme_zh_cn, readme_zh_tw [EXTRACTED 1.00]
- **Worked Examples (karpathy + httpx)** — karpathy_repos_readme, httpx_readme, worked_mixed_corpus [EXTRACTED 0.90]
- **Transformer Core Components (Attention + Encoder/Decoder + Positional Encoding)** — attention_multihead, attention_encoder_decoder, attention_positional_encoding, attention_layer_norm [EXTRACTED 1.00]
- **All Platform Skills (Claude, Codex, Aider, Kiro, Copilot, VS Code, Trae, Droid, Claw, OpenCode, Windows)** — skill_claude, skill_codex, skill_opencode, skill_aider, skill_kiro, skill_copilot, skill_vscode, skill_trae, skill_droid, skill_claw, skill_windows [EXTRACTED 1.00]
- **Three Core Outputs (HTML + JSON + Report)** — readme_graph_html, readme_graph_json, readme_graph_report [EXTRACTED 1.00]
- **Three Extraction Passes (AST + Whisper + Semantic)** — readme_ast_extraction, readme_semantic_extraction, readme_leiden_clustering [EXTRACTED 0.90]
- **All README Translations (25+ languages)** — readme_nl, readme_ko, readme_fr, readme_pl, readme_no, readme_fi, readme_ar, readme_tr, readme_sv, readme_pt, readme_da, readme_de, readme_ro, readme_zh_cn, readme_zh_tw [EXTRACTED 1.00]
- **Worked Examples (karpathy + httpx)** — karpathy_repos_readme, httpx_readme, worked_mixed_corpus [EXTRACTED 0.90]
- **Transformer Core Components (Attention + Encoder/Decoder + Positional Encoding)** — attention_multihead, attention_encoder_decoder, attention_positional_encoding, attention_layer_norm [EXTRACTED 1.00]
- **All Platform Skills (Claude, Codex, Aider, Kiro, Copilot, VS Code, Trae, Droid, Claw, OpenCode, Windows)** — skill_claude, skill_codex, skill_opencode, skill_aider, skill_kiro, skill_copilot, skill_vscode, skill_trae, skill_droid, skill_claw, skill_windows [EXTRACTED 1.00]
- **Three Core Outputs (HTML + JSON + Report)** — readme_graph_html, readme_graph_json, readme_graph_report [EXTRACTED 1.00]
- **Three Extraction Passes (AST + Whisper + Semantic)** — readme_ast_extraction, readme_semantic_extraction, readme_leiden_clustering [EXTRACTED 0.90]
- **All README Translations (25+ languages)** — readme_nl, readme_ko, readme_fr, readme_pl, readme_no, readme_fi, readme_ar, readme_tr, readme_sv, readme_pt, readme_da, readme_de, readme_ro, readme_zh_cn, readme_zh_tw [EXTRACTED 1.00]
- **Worked Examples (karpathy + httpx)** — karpathy_repos_readme, httpx_readme, worked_mixed_corpus [EXTRACTED 0.90]
- **Transformer Core Components (Attention + Encoder/Decoder + Positional Encoding)** — attention_multihead, attention_encoder_decoder, attention_positional_encoding, attention_layer_norm [EXTRACTED 1.00]
- **All Platform Skills (Claude, Codex, Aider, Kiro, Copilot, VS Code, Trae, Droid, Claw, OpenCode, Windows)** — skill_claude, skill_codex, skill_opencode, skill_aider, skill_kiro, skill_copilot, skill_vscode, skill_trae, skill_droid, skill_claw, skill_windows [EXTRACTED 1.00]
- **Three Core Outputs (HTML + JSON + Report)** — readme_graph_html, readme_graph_json, readme_graph_report [EXTRACTED 1.00]
- **Three Extraction Passes (AST + Whisper + Semantic)** — readme_ast_extraction, readme_semantic_extraction, readme_leiden_clustering [EXTRACTED 0.90]
- **All README Translations (25+ languages)** — readme_nl, readme_ko, readme_fr, readme_pl, readme_no, readme_fi, readme_ar, readme_tr, readme_sv, readme_pt, readme_da, readme_de, readme_ro, readme_zh_cn, readme_zh_tw [EXTRACTED 1.00]
- **Worked Examples (karpathy + httpx)** — karpathy_repos_readme, httpx_readme, worked_mixed_corpus [EXTRACTED 0.90]
- **Transformer Core Components (Attention + Encoder/Decoder + Positional Encoding)** — attention_multihead, attention_encoder_decoder, attention_positional_encoding, attention_layer_norm [EXTRACTED 1.00]
- **All Platform Skills (Claude, Codex, Aider, Kiro, Copilot, VS Code, Trae, Droid, Claw, OpenCode, Windows)** — skill_claude, skill_codex, skill_opencode, skill_aider, skill_kiro, skill_copilot, skill_vscode, skill_trae, skill_droid, skill_claw, skill_windows [EXTRACTED 1.00]
- **Three Core Outputs (HTML + JSON + Report)** — readme_graph_html, readme_graph_json, readme_graph_report [EXTRACTED 1.00]
- **Three Extraction Passes (AST + Whisper + Semantic)** — readme_ast_extraction, readme_semantic_extraction, readme_leiden_clustering [EXTRACTED 0.90]
- **All README Translations (25+ languages)** — readme_nl, readme_ko, readme_fr, readme_pl, readme_no, readme_fi, readme_ar, readme_tr, readme_sv, readme_pt, readme_da, readme_de, readme_ro, readme_zh_cn, readme_zh_tw [EXTRACTED 1.00]
- **Worked Examples (karpathy + httpx)** — karpathy_repos_readme, httpx_readme, worked_mixed_corpus [EXTRACTED 0.90]
- **Transformer Core Components (Attention + Encoder/Decoder + Positional Encoding)** — attention_multihead, attention_encoder_decoder, attention_positional_encoding, attention_layer_norm [EXTRACTED 1.00]
- **All Platform Skills (Claude, Codex, Aider, Kiro, Copilot, VS Code, Trae, Droid, Claw, OpenCode, Windows)** — skill_claude, skill_codex, skill_opencode, skill_aider, skill_kiro, skill_copilot, skill_vscode, skill_trae, skill_droid, skill_claw, skill_windows [EXTRACTED 1.00]
- **Three Core Outputs (HTML + JSON + Report)** — readme_graph_html, readme_graph_json, readme_graph_report [EXTRACTED 1.00]
- **Three Extraction Passes (AST + Whisper + Semantic)** — readme_ast_extraction, readme_semantic_extraction, readme_leiden_clustering [EXTRACTED 0.90]
- **All README Translations (25+ languages)** — readme_nl, readme_ko, readme_fr, readme_pl, readme_no, readme_fi, readme_ar, readme_tr, readme_sv, readme_pt, readme_da, readme_de, readme_ro, readme_zh_cn, readme_zh_tw [EXTRACTED 1.00]
- **Worked Examples (karpathy + httpx)** — karpathy_repos_readme, httpx_readme, worked_mixed_corpus [EXTRACTED 0.90]
- **Transformer Core Components (Attention + Encoder/Decoder + Positional Encoding)** — attention_multihead, attention_encoder_decoder, attention_positional_encoding, attention_layer_norm [EXTRACTED 1.00]
- **All Platform Skills (Claude, Codex, Aider, Kiro, Copilot, VS Code, Trae, Droid, Claw, OpenCode, Windows)** — skill_claude, skill_codex, skill_opencode, skill_aider, skill_kiro, skill_copilot, skill_vscode, skill_trae, skill_droid, skill_claw, skill_windows [EXTRACTED 1.00]
- **Three Core Outputs (HTML + JSON + Report)** — readme_graph_html, readme_graph_json, readme_graph_report [EXTRACTED 1.00]
- **Three Extraction Passes (AST + Whisper + Semantic)** — readme_ast_extraction, readme_semantic_extraction, readme_leiden_clustering [EXTRACTED 0.90]
- **All README Translations (25+ languages)** — readme_nl, readme_ko, readme_fr, readme_pl, readme_no, readme_fi, readme_ar, readme_tr, readme_sv, readme_pt, readme_da, readme_de, readme_ro, readme_zh_cn, readme_zh_tw [EXTRACTED 1.00]
- **Worked Examples (karpathy + httpx)** — karpathy_repos_readme, httpx_readme, worked_mixed_corpus [EXTRACTED 0.90]
- **Transformer Core Components (Attention + Encoder/Decoder + Positional Encoding)** — attention_multihead, attention_encoder_decoder, attention_positional_encoding, attention_layer_norm [EXTRACTED 1.00]
- **All Platform Skills (Claude, Codex, Aider, Kiro, Copilot, VS Code, Trae, Droid, Claw, OpenCode, Windows)** — skill_claude, skill_codex, skill_opencode, skill_aider, skill_kiro, skill_copilot, skill_vscode, skill_trae, skill_droid, skill_claw, skill_windows [EXTRACTED 1.00]
- **Three Core Outputs (HTML + JSON + Report)** — readme_graph_html, readme_graph_json, readme_graph_report [EXTRACTED 1.00]
- **Three Extraction Passes (AST + Whisper + Semantic)** — readme_ast_extraction, readme_semantic_extraction, readme_leiden_clustering [EXTRACTED 0.90]
- **All README Translations (25+ languages)** — readme_nl, readme_ko, readme_fr, readme_pl, readme_no, readme_fi, readme_ar, readme_tr, readme_sv, readme_pt, readme_da, readme_de, readme_ro, readme_zh_cn, readme_zh_tw [EXTRACTED 1.00]
- **Worked Examples (karpathy + httpx)** — karpathy_repos_readme, httpx_readme, worked_mixed_corpus [EXTRACTED 0.90]
- **Transformer Core Components (Attention + Encoder/Decoder + Positional Encoding)** — attention_multihead, attention_encoder_decoder, attention_positional_encoding, attention_layer_norm [EXTRACTED 1.00]
- **All Platform Skills (Claude, Codex, Aider, Kiro, Copilot, VS Code, Trae, Droid, Claw, OpenCode, Windows)** — skill_claude, skill_codex, skill_opencode, skill_aider, skill_kiro, skill_copilot, skill_vscode, skill_trae, skill_droid, skill_claw, skill_windows [EXTRACTED 1.00]
- **Three Core Outputs (HTML + JSON + Report)** — readme_graph_html, readme_graph_json, readme_graph_report [EXTRACTED 1.00]
- **Three Extraction Passes (AST + Whisper + Semantic)** — readme_ast_extraction, readme_semantic_extraction, readme_leiden_clustering [EXTRACTED 0.90]
- **All README Translations (25+ languages)** — readme_nl, readme_ko, readme_fr, readme_pl, readme_no, readme_fi, readme_ar, readme_tr, readme_sv, readme_pt, readme_da, readme_de, readme_ro, readme_zh_cn, readme_zh_tw [EXTRACTED 1.00]
- **Worked Examples (karpathy + httpx)** — karpathy_repos_readme, httpx_readme, worked_mixed_corpus [EXTRACTED 0.90]
- **Transformer Core Components (Attention + Encoder/Decoder + Positional Encoding)** — attention_multihead, attention_encoder_decoder, attention_positional_encoding, attention_layer_norm [EXTRACTED 1.00]
- **All Platform Skills (Claude, Codex, Aider, Kiro, Copilot, VS Code, Trae, Droid, Claw, OpenCode, Windows)** — skill_claude, skill_codex, skill_opencode, skill_aider, skill_kiro, skill_copilot, skill_vscode, skill_trae, skill_droid, skill_claw, skill_windows [EXTRACTED 1.00]
- **Three Core Outputs (HTML + JSON + Report)** — readme_graph_html, readme_graph_json, readme_graph_report [EXTRACTED 1.00]
- **Three Extraction Passes (AST + Whisper + Semantic)** — readme_ast_extraction, readme_semantic_extraction, readme_leiden_clustering [EXTRACTED 0.90]
- **All README Translations (25+ languages)** — readme_nl, readme_ko, readme_fr, readme_pl, readme_no, readme_fi, readme_ar, readme_tr, readme_sv, readme_pt, readme_da, readme_de, readme_ro, readme_zh_cn, readme_zh_tw [EXTRACTED 1.00]
- **Worked Examples (karpathy + httpx)** — karpathy_repos_readme, httpx_readme, worked_mixed_corpus [EXTRACTED 0.90]
- **Transformer Core Components (Attention + Encoder/Decoder + Positional Encoding)** — attention_multihead, attention_encoder_decoder, attention_positional_encoding, attention_layer_norm [EXTRACTED 1.00]
- **All Platform Skills (Claude, Codex, Aider, Kiro, Copilot, VS Code, Trae, Droid, Claw, OpenCode, Windows)** — skill_claude, skill_codex, skill_opencode, skill_aider, skill_kiro, skill_copilot, skill_vscode, skill_trae, skill_droid, skill_claw, skill_windows [EXTRACTED 1.00]
- **Three Core Outputs (HTML + JSON + Report)** — readme_graph_html, readme_graph_json, readme_graph_report [EXTRACTED 1.00]
- **Three Extraction Passes (AST + Whisper + Semantic)** — readme_ast_extraction, readme_semantic_extraction, readme_leiden_clustering [EXTRACTED 0.90]
- **All README Translations (25+ languages)** — readme_nl, readme_ko, readme_fr, readme_pl, readme_no, readme_fi, readme_ar, readme_tr, readme_sv, readme_pt, readme_da, readme_de, readme_ro, readme_zh_cn, readme_zh_tw [EXTRACTED 1.00]
- **Worked Examples (karpathy + httpx)** — karpathy_repos_readme, httpx_readme, worked_mixed_corpus [EXTRACTED 0.90]
- **Transformer Core Components (Attention + Encoder/Decoder + Positional Encoding)** — attention_multihead, attention_encoder_decoder, attention_positional_encoding, attention_layer_norm [EXTRACTED 1.00]
- **All Platform Skills (Claude, Codex, Aider, Kiro, Copilot, VS Code, Trae, Droid, Claw, OpenCode, Windows)** — skill_claude, skill_codex, skill_opencode, skill_aider, skill_kiro, skill_copilot, skill_vscode, skill_trae, skill_droid, skill_claw, skill_windows [EXTRACTED 1.00]
- **Three Core Outputs (HTML + JSON + Report)** — readme_graph_html, readme_graph_json, readme_graph_report [EXTRACTED 1.00]
- **Three Extraction Passes (AST + Whisper + Semantic)** — readme_ast_extraction, readme_semantic_extraction, readme_leiden_clustering [EXTRACTED 0.90]
- **All README Translations (25+ languages)** — readme_nl, readme_ko, readme_fr, readme_pl, readme_no, readme_fi, readme_ar, readme_tr, readme_sv, readme_pt, readme_da, readme_de, readme_ro, readme_zh_cn, readme_zh_tw [EXTRACTED 1.00]
- **Worked Examples (karpathy + httpx)** — karpathy_repos_readme, httpx_readme, worked_mixed_corpus [EXTRACTED 0.90]
- **Transformer Core Components (Attention + Encoder/Decoder + Positional Encoding)** — attention_multihead, attention_encoder_decoder, attention_positional_encoding, attention_layer_norm [EXTRACTED 1.00]
- **All Platform Skills (Claude, Codex, Aider, Kiro, Copilot, VS Code, Trae, Droid, Claw, OpenCode, Windows)** — skill_claude, skill_codex, skill_opencode, skill_aider, skill_kiro, skill_copilot, skill_vscode, skill_trae, skill_droid, skill_claw, skill_windows [EXTRACTED 1.00]
- **Three Core Outputs (HTML + JSON + Report)** — readme_graph_html, readme_graph_json, readme_graph_report [EXTRACTED 1.00]
- **Three Extraction Passes (AST + Whisper + Semantic)** — readme_ast_extraction, readme_semantic_extraction, readme_leiden_clustering [EXTRACTED 0.90]
- **All README Translations (25+ languages)** — readme_nl, readme_ko, readme_fr, readme_pl, readme_no, readme_fi, readme_ar, readme_tr, readme_sv, readme_pt, readme_da, readme_de, readme_ro, readme_zh_cn, readme_zh_tw [EXTRACTED 1.00]
- **Worked Examples (karpathy + httpx)** — karpathy_repos_readme, httpx_readme, worked_mixed_corpus [EXTRACTED 0.90]
- **Transformer Core Components (Attention + Encoder/Decoder + Positional Encoding)** — attention_multihead, attention_encoder_decoder, attention_positional_encoding, attention_layer_norm [EXTRACTED 1.00]
- **All Platform Skills (Claude, Codex, Aider, Kiro, Copilot, VS Code, Trae, Droid, Claw, OpenCode, Windows)** — skill_claude, skill_codex, skill_opencode, skill_aider, skill_kiro, skill_copilot, skill_vscode, skill_trae, skill_droid, skill_claw, skill_windows [EXTRACTED 1.00]
- **Three Core Outputs (HTML + JSON + Report)** — readme_graph_html, readme_graph_json, readme_graph_report [EXTRACTED 1.00]
- **Three Extraction Passes (AST + Whisper + Semantic)** — readme_ast_extraction, readme_semantic_extraction, readme_leiden_clustering [EXTRACTED 0.90]
- **All README Translations (25+ languages)** — readme_nl, readme_ko, readme_fr, readme_pl, readme_no, readme_fi, readme_ar, readme_tr, readme_sv, readme_pt, readme_da, readme_de, readme_ro, readme_zh_cn, readme_zh_tw [EXTRACTED 1.00]
- **Worked Examples (karpathy + httpx)** — karpathy_repos_readme, httpx_readme, worked_mixed_corpus [EXTRACTED 0.90]
- **Transformer Core Components (Attention + Encoder/Decoder + Positional Encoding)** — attention_multihead, attention_encoder_decoder, attention_positional_encoding, attention_layer_norm [EXTRACTED 1.00]

## Communities

### Community 0 - "HTTP Auth & Client Models"
Cohesion: 0.03
Nodes (101): Exception, Auth, BasicAuth, BearerAuth, DigestAuth, NetRCAuth, Authentication handlers. Auth objects are callables that modify a request before, Load credentials from ~/.netrc based on the request host. (+93 more)

### Community 1 - "Graphify CLI & Platform Install"
Cohesion: 0.02
Nodes (131): _agents_install(), _agents_uninstall(), _antigravity_install(), _antigravity_uninstall(), _check_skill_version(), claude_install(), claude_uninstall(), _clone_repo() (+123 more)

### Community 2 - "Multi-Language AST Extraction"
Cohesion: 0.03
Nodes (120): extract_c(), extract_cpp(), extract_csharp(), extract_elixir(), extract_java(), extract_julia(), extract_kotlin(), extract_objc() (+112 more)

### Community 3 - "Graph Export & Visualization"
Cohesion: 0.04
Nodes (100): convert_office_file(), count_words(), docx_to_markdown(), extract_pdf_text(), _is_ignored(), Extract plain text from a PDF file using pypdf., Convert a .docx file to markdown text using python-docx., Convert an .xlsx file to markdown text using openpyxl. (+92 more)

### Community 4 - "Clustering & Community Detection"
Cohesion: 0.04
Nodes (95): add(), build_from_json(), Build a NetworkX graph from an extraction dict.      directed=True produces a Di, _check_tree_sitter_version(), _csharp_extra_walk(), extract(), extract_blade(), extract_dart() (+87 more)

### Community 5 - "Graph Build & Core Pipeline"
Cohesion: 0.04
Nodes (90): generate(), Mirrors export.safe_name so community hub filenames and report wikilinks always, _safe_community_name(), _cross_community_surprises(), _cross_file_surprises(), _file_category(), god_nodes(), graph_diff() (+82 more)

### Community 6 - "Export (HTML/Cypher/Obsidian)"
Cohesion: 0.04
Nodes (82): _cross_community_surprises(), _cross_file_surprises(), _file_category(), god_nodes(), graph_diff(), _is_concept_node(), _is_file_node(), _node_community_map() (+74 more)

### Community 7 - "Graph Analysis & Surprises"
Cohesion: 0.05
Nodes (66): _detect_url_type(), _download_binary(), _fetch_arxiv(), _fetch_html(), _fetch_tweet(), _fetch_webpage(), _html_to_markdown(), ingest() (+58 more)

### Community 8 - "Ingest & URL Fetching"
Cohesion: 0.05
Nodes (65): handle_delete(), handle_enrich(), handle_get(), handle_list(), handle_search(), handle_upload(), API module - exposes the document pipeline over HTTP. Thin layer over parser, va, Accept a list of file paths, run the full pipeline on each,     and return a sum (+57 more)

### Community 9 - "File Detection & Classification"
Cohesion: 0.06
Nodes (62): build_graph(), Graph, attach_hyperedges(), Store hyperedges in the graph's metadata dict., _community_article(), _cross_community_links(), _god_node_article(), _index_md() (+54 more)

### Community 10 - "Sample API & CRUD Handlers"
Cohesion: 0.05
Nodes (54): Base, area(), Analyzer, compute_score(), normalize(), Fixture: functions and methods that call each other - for call-graph extraction, run_analysis(), Circle (+46 more)

### Community 11 - "Code Analysis Utilities"
Cohesion: 0.04
Nodes (19): ApiClient, CacheManager, Config, createProcessor(), DataProcessor, Get-Data(), GraphifyDemo, HttpClient (+11 more)

### Community 12 - "API Client Fixtures"
Cohesion: 0.06
Nodes (53): Enum, classify_file(), detect(), FileType, _is_noise_dir(), _is_sensitive(), _load_graphifyignore(), _looks_like_paper() (+45 more)

### Community 13 - "User Models & Cache Layer"
Cohesion: 0.06
Nodes (47): MyApp.Accounts.User, create(), find(), validate(), _body_content(), cache_dir(), cached_files(), check_semantic_cache() (+39 more)

### Community 14 - "Documentation & Architecture"
Cohesion: 0.05
Nodes (48): Confidence Labels (EXTRACTED/INFERRED/AMBIGUOUS), Extraction Output Schema (nodes/edges dict), Graphify Pipeline (detect→extract→build→cluster→analyze→report→export), Encoder-Decoder Structure (6 layers each), Layer Normalization + Residual Connection, Multi-Head Attention (h=8 parallel heads), Positional Encoding (sin/cos), Scaled Dot-Product Attention (+40 more)

### Community 15 - "Audio/Video Transcription"
Cohesion: 0.08
Nodes (34): build_whisper_prompt(), download_audio(), _get_whisper(), _get_yt_dlp(), is_url(), _model_name(), Transcribe a video/audio file or URL to a .txt transcript.      If video_path is, Transcribe a list of video/audio files or URLs, return paths to transcript .txt (+26 more)

### Community 16 - "Git Hooks Integration"
Cohesion: 0.11
Nodes (34): _git_root(), _hooks_dir(), install(), _install_hook(), Walk up to find .git directory., Return the git hooks directory, respecting core.hooksPath if set (e.g. Husky)., Install a single git hook, appending if an existing hook is present., Remove graphify section from a git hook using start/end markers. (+26 more)

### Community 17 - "Graph Build Utilities"
Cohesion: 0.11
Nodes (25): build(), build_merge(), deduplicate_by_label(), _norm_label(), _normalize_id(), Merge multiple extraction results into one graph.      directed=True produces a, Canonical dedup key — lowercase, alphanumeric only., Merge nodes that share a normalised label, rewriting edge references.      Prefe (+17 more)

### Community 18 - "Token Benchmarking"
Cohesion: 0.19
Nodes (22): _estimate_tokens(), print_benchmark(), _query_subgraph_tokens(), Token-reduction benchmark - measures how much context graphify saves vs naive fu, Print a human-readable benchmark report., Run BFS from best-matching nodes and return estimated tokens in the subgraph con, Measure token reduction: corpus tokens vs graphify query tokens.      Args:, run_benchmark() (+14 more)

### Community 19 - "httpx Worked Example"
Cohesion: 0.12
Nodes (15): check_update(), _notify_only(), Check for pending semantic update flag and notify the user if set.      Cron-saf, Write a flag file and print a notification (fallback for non-code-only corpora)., Tests for watch.py - file watcher helpers (no watchdog required)., check_update returns True and is silent when needs_update flag is absent., check_update returns True and prints notification when flag exists., check_update never removes the needs_update flag (clearing is LLM's job). (+7 more)

### Community 20 - "PHP Event Listeners"
Cohesion: 0.22
Nodes (9): httpx AsyncClient (god node), httpx Client (god node - 26 edges), httpx Example, httpx Graph Report, httpx Response (god node), httpx Review, Karpathy Repos Example, Karpathy Repos Graph Report (+1 more)

### Community 21 - "OOP Class Fixtures (Animal)"
Cohesion: 0.43
Nodes (6): EventServiceProvider, NotifyAdmins, OrderPlaced, SendWelcomeEmail, ShipOrder, UserRegistered

### Community 22 - "PHP DI Container"
Cohesion: 0.33
Nodes (5): Animal, -initWithName, -speak, Dog, -fetch

### Community 23 - "PHP Static Properties"
Cohesion: 0.67
Nodes (4): AppServiceProvider, CashierGateway, PaymentGateway, StripeGateway

### Community 24 - "Transformer Model Fixture"
Cohesion: 0.6
Nodes (2): ColorResolver, DefaultPalette

### Community 25 - "Package Init"
Cohesion: 0.5
Nodes (1): Transformer

### Community 26 - "Security & URL Validation"
Cohesion: 0.67
Nodes (1): graphify - extract · build · cluster · analyze · report.

### Community 27 - "Manifest Module"
Cohesion: 1.0
Nodes (2): safe_fetch() (size cap + timeout), validate_url() (security)

### Community 28 - "Test Init"
Cohesion: 1.0
Nodes (2): Conversation, Conversation — réponse 1

### Community 29 - "Graphifyignore"
Cohesion: 1.0
Nodes (2): Conversation, Conversation — réponse 1

### Community 30 - "Label Sanitization"
Cohesion: 1.0
Nodes (2): Install Graphify repository, Install Graphify repository — réponse 1

### Community 31 - "OpenClaw Skill"
Cohesion: 1.0
Nodes (2): Conversation, Conversation — réponse 1

### Community 34 - "Factory Droid Skill"
Cohesion: 1.0
Nodes (1): .graphifyignore (exclusion file)

### Community 35 - "README (Polish)"
Cohesion: 1.0
Nodes (1): sanitize_label() (strips control chars)

### Community 36 - "README (Norwegian)"
Cohesion: 1.0
Nodes (1): Graphify Skill for OpenClaw

### Community 37 - "README (Finnish)"
Cohesion: 1.0
Nodes (1): Graphify Skill for Trae

### Community 38 - "README (Swedish)"
Cohesion: 1.0
Nodes (1): Graphify Skill for Windows

### Community 39 - "README (Danish)"
Cohesion: 1.0
Nodes (1): Graphify Skill for Factory Droid

### Community 40 - "README (Romanian)"
Cohesion: 1.0
Nodes (1): README (Polish)

### Community 41 - "README (Traditional Chinese)"
Cohesion: 1.0
Nodes (1): README (Norwegian)

### Community 42 - "README (Czech)"
Cohesion: 1.0
Nodes (1): README (Finnish)

### Community 43 - "README (Indonesian)"
Cohesion: 1.0
Nodes (1): README (Swedish)

### Community 44 - "README (Vietnamese)"
Cohesion: 1.0
Nodes (1): README (Danish)

### Community 45 - "README (Ukrainian)"
Cohesion: 1.0
Nodes (1): README (Romanian)

### Community 46 - "README (Hungarian)"
Cohesion: 1.0
Nodes (1): README (Traditional Chinese)

### Community 47 - "Community 47"
Cohesion: 1.0
Nodes (1): README (Czech)

### Community 48 - "Community 48"
Cohesion: 1.0
Nodes (1): README (Indonesian)

### Community 49 - "Community 49"
Cohesion: 1.0
Nodes (1): README (Vietnamese)

### Community 50 - "Community 50"
Cohesion: 1.0
Nodes (1): README (Ukrainian)

### Community 51 - "Community 51"
Cohesion: 1.0
Nodes (1): README (Hungarian)

### Community 52 - "Community 52"
Cohesion: 1.0
Nodes (1): Conversation

### Community 53 - "Community 53"
Cohesion: 1.0
Nodes (1): Conversation

### Community 54 - "Community 54"
Cohesion: 1.0
Nodes (1): Résumé de session — Automatisation graphify : ingestion conversations + SessionStart hook

### Community 55 - "Community 55"
Cohesion: 1.0
Nodes (1): Conversation

### Community 56 - "Community 56"
Cohesion: 1.0
Nodes (1): Conversation

### Community 57 - "Community 57"
Cohesion: 1.0
Nodes (1): Conversation

### Community 58 - "Community 58"
Cohesion: 1.0
Nodes (1): Conversation

### Community 59 - "Community 59"
Cohesion: 1.0
Nodes (1): Conversation

### Community 60 - "Community 60"
Cohesion: 1.0
Nodes (1): Conversation

### Community 61 - "Community 61"
Cohesion: 1.0
Nodes (1): Conversation

### Community 62 - "Community 62"
Cohesion: 1.0
Nodes (1): Conversation

### Community 63 - "Community 63"
Cohesion: 1.0
Nodes (1): Conversation

## Knowledge Gaps
- **434 isolated node(s):** `Token-reduction benchmark - measures how much context graphify saves vs naive fu`, `Run BFS from best-matching nodes and return estimated tokens in the subgraph con`, `Measure token reduction: corpus tokens vs graphify query tokens.      Args:`, `Print a human-readable benchmark report.`, `Return (community_label, edge_count) pairs for cross-community connections, sort` (+429 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `Transformer Model Fixture`** (5 nodes): `ColorResolver`, `.accent()`, `.primary()`, `DefaultPalette`, `sample_php_static_prop.php`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Package Init`** (4 nodes): `Transformer`, `.forward()`, `.__init__()`, `sample.py`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Security & URL Validation`** (3 nodes): `__getattr__()`, `__init__.py`, `graphify - extract · build · cluster · analyze · report.`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Manifest Module`** (2 nodes): `safe_fetch() (size cap + timeout)`, `validate_url() (security)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Test Init`** (2 nodes): `Conversation`, `Conversation — réponse 1`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Graphifyignore`** (2 nodes): `Conversation`, `Conversation — réponse 1`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Label Sanitization`** (2 nodes): `Install Graphify repository`, `Install Graphify repository — réponse 1`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `OpenClaw Skill`** (2 nodes): `Conversation`, `Conversation — réponse 1`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Factory Droid Skill`** (1 nodes): `.graphifyignore (exclusion file)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `README (Polish)`** (1 nodes): `sanitize_label() (strips control chars)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `README (Norwegian)`** (1 nodes): `Graphify Skill for OpenClaw`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `README (Finnish)`** (1 nodes): `Graphify Skill for Trae`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `README (Swedish)`** (1 nodes): `Graphify Skill for Windows`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `README (Danish)`** (1 nodes): `Graphify Skill for Factory Droid`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `README (Romanian)`** (1 nodes): `README (Polish)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `README (Traditional Chinese)`** (1 nodes): `README (Norwegian)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `README (Czech)`** (1 nodes): `README (Finnish)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `README (Indonesian)`** (1 nodes): `README (Swedish)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `README (Vietnamese)`** (1 nodes): `README (Danish)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `README (Ukrainian)`** (1 nodes): `README (Romanian)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `README (Hungarian)`** (1 nodes): `README (Traditional Chinese)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 47`** (1 nodes): `README (Czech)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 48`** (1 nodes): `README (Indonesian)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 49`** (1 nodes): `README (Vietnamese)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 50`** (1 nodes): `README (Ukrainian)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 51`** (1 nodes): `README (Hungarian)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 52`** (1 nodes): `Conversation`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 53`** (1 nodes): `Conversation`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 54`** (1 nodes): `Résumé de session — Automatisation graphify : ingestion conversations + SessionStart hook`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 55`** (1 nodes): `Conversation`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 56`** (1 nodes): `Conversation`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 57`** (1 nodes): `Conversation`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 58`** (1 nodes): `Conversation`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 59`** (1 nodes): `Conversation`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 60`** (1 nodes): `Conversation`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 61`** (1 nodes): `Conversation`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 62`** (1 nodes): `Conversation`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 63`** (1 nodes): `Conversation`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `main()` connect `Graphify CLI & Platform Install` to `Graph Export & Visualization`, `Graph Build & Core Pipeline`, `Export (HTML/Cypher/Obsidian)`, `File Detection & Classification`, `Sample API & CRUD Handlers`, `Git Hooks Integration`, `Token Benchmarking`, `httpx Worked Example`?**
  _High betweenness centrality (0.071) - this node is a cross-community bridge._
- **Why does `_extract_generic()` connect `Clustering & Community Detection` to `Multi-Language AST Extraction`, `Graph Export & Visualization`, `Export (HTML/Cypher/Obsidian)`?**
  _High betweenness centrality (0.069) - this node is a cross-community bridge._
- **Why does `Cookies` connect `HTTP Auth & Client Models` to `Clustering & Community Detection`, `Export (HTML/Cypher/Obsidian)`?**
  _High betweenness centrality (0.065) - this node is a cross-community bridge._
- **Are the 86 inferred relationships involving `str` (e.g. with `_load_tsconfig_aliases()` and `_import_python()`) actually correct?**
  _`str` has 86 INFERRED edges - model-reasoned connections that need verification._
- **Are the 41 inferred relationships involving `Response` (e.g. with `Timeout` and `Limits`) actually correct?**
  _`Response` has 41 INFERRED edges - model-reasoned connections that need verification._
- **Are the 40 inferred relationships involving `Request` (e.g. with `safe_fetch()` and `Timeout`) actually correct?**
  _`Request` has 40 INFERRED edges - model-reasoned connections that need verification._
- **Are the 39 inferred relationships involving `build_from_json()` (e.g. with `_rebuild_code()` and `validate_extraction()`) actually correct?**
  _`build_from_json()` has 39 INFERRED edges - model-reasoned connections that need verification._