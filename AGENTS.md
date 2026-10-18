# Ozhungu — AGENTS.md

## What

Agentic Android file organizer. Kotlin, plain Views (no Compose), minSdk 26 / target 35,
AGP 8.7.3 + Kotlin 2.0.21 (same toolchain as zo-voice/zai-widget). SDK `/opt/android-sdk`,
`local.properties` gitignored. `make verify` = `testDebugUnitTest lintDebug assembleDebug`.

## Architecture (keep these boundaries)

- `plan/` — pure JVM, unit-tested: `Plan` grammar, `PlanParser` (balanced-brace JSON extractor,
  accepts flat or `{"plan":{...}}`), `PlanValidator` (all guardrails live here — do NOT add
  execution-side sanity checks elsewhere), `PromptBuilder` (contract + capped listing).
- `saf/SafFs` — only class that touches DocumentsContract. Instance-per-tree.
  `AppResolver.init(application)` in `MainActivity.onCreate` supplies the ContentResolver.
- `scan/Scanner` — BFS walk (depth ≤ 4, ≤ 600 files), skips `/Android` + dot-dirs,
  text snippets via `readTextHead` ≤ 1200 chars.
- `brain/ZoPlanner` — thin adapter over `dev.zocomputer:ask` (zo-kotlin). Composite build
  picks up `../zo-kotlin` when present. App needs `okhttp` on its own classpath because
  `ZoApi.ask` returns `Call`.
- `exec/Executor` — applies checked rows via `ensurePath(create=true)` → `move` →
  conditional `rename`; journals each success (`JournalEntry`, encoded to
  `filesDir/last_journal.json`); `undo()` replays newest-first.
- `MainActivity` — single Activity, programmatic Views, CheckBox list as the review surface.
  `onDone` from the stream arrives on an OkHttp worker thread — always `runOnUiThread` for UI.

## Settings (v0.3.0+)

`SettingsActivity` (full-screen, plain Views — no Compose, no XML layouts): backend radio
cards (Zo / OpenAI-compatible / System One decision) toggle section visibility; **Test** buttons do real
round-trips (Zo `GET /models/available` via `ZoApi.listModels`, OAI `GET {base}/models`);
`Browse catalog` opens the searchable models.dev browser (picker filter/rows are pure
`ModelsDev.filter`/`ModelsDev.rows` — unit-tested). MainActivity deep-links here on
first run and re-reads Settings + drops a stale planner in `onResume()`.

## Package quirk

Package path starts with `in.` (India TLD) → must be backtick-escaped:
`package ` + backtick + `in` + backtick + `.cashlessconsumer.ozhungu` — same as zo-voice.

## Verification gates

- No feature merges with failing `make verify`. The validator tests in `PlanTest` are the
  safety net for the "model proposes, device disposes" contract — extend them with every
  new guardrail.
- Test gotcha: `PromptBuilder` "more not shown" counts entries over `MAX_PROMPT_ENTRIES`
  (300), not `skippedCount`.

## v0.1 deliberately excludes

Delete/copy ops, background scheduling, on-device models. If asked, discuss before adding —
delete especially (contract says moves only).

## v0.2 — OpenAI-compatible backends (2026-10-18)

- `Planner` interface (`brain/Planner.kt`): `plan`/`refine`/`cancel`. `ZoPlanner` keeps
  conversation continuity server-side (`conversation_id`); `OaiPlanner` replays a local
  transcript (system + last user + last assistant, trimmed) because /chat/completions is
  stateless. MainActivity creates a fresh planner per "Ask for a plan" (new session) and
  reuses it for refinements.
- `ModelsDev.parse(raw)` is pure + unit-tested: keeps providers with an `api` base URL,
  drops `NON_OAI_SDKS` (@ai-sdk/anthropic, google, amazon-bedrock), sorts ZDR-text-flagged
  first. The ★ ZDR badge is TEXT-DERIVED ONLY (models.dev has no retention field) — never
  present it as a policy guarantee.
- `OaiPlanner.Sse.delta(line)` is the unit-tested SSE parser; `[DONE]` → null, non-content
  lines → null. System prompt = `PromptBuilder.SYSTEM_RULES` (made public in v0.2) so both
  backends enforce the identical plan contract.

## Docs site (2026-10-18)

- Live: https://logicinczo.github.io/ozhungu/ (`index.html`) + `/guide.html`.
- Static, zero-build, hand-written HTML + `docs/style.css` (same shell as the zai-usage
  docs site). Served by **legacy GitHub Pages from `main` `/docs`** — no Pages workflow.
  To change a page, edit the file, commit, push; the `pages-build-deployment` workflow
  rebuilds in ~40 s. Verify with `curl -o /dev/null -w "%{http_code}"`.
- Docs live in-repo, not in the workspace: `docs/` is part of the repo, so a docs edit is
  a normal commit. Keep numbers in `guide.html` (test count, coverage table) in sync with
  `make coverage` output — the coverage table is hand-maintained and WILL drift otherwise.

## v0.4 — System One decision backend (2026-10-18)

Third `Planner` backend (`brain/DecisionPlanner.kt` + pure core `brain/DecisionModel.kt`)
speaking the Jev-compatible System One API (TypeSafe Jev, Cloudflare Clef/Clef-flash on
Workers AI, self-hosted Clef). Mapping: scan listing = request `state`; every file = one
`choice` question (max 64 per request — Workers AI cap) over a shared vocabulary
(existing dirs first, then canonical buckets by content, explicit `__keep__` last).
Argmax probability >= user confidence floor and != `__keep__` and != current dir → move
action (targets deduped with " (2)" suffixes); everything else is skipped by design.
Response parser is envelope-tolerant (bare Jev `answers` or Workers AI
`{result:{answers}}`). The resulting plan re-enters the normal PlanParser →
PlanValidator → review checklist pipeline — MainActivity can't tell the backends apart.
Settings: endpoint URL (full ai/run or /v1/decisions), API key (blank self-hosted),
confidence floor %; Test = one `noul` round-trip. 10 unit tests in `DecisionModelTest`
(vocabulary, batching, envelope parse, threshold/keep/dedupe, validator round-trip);
DecisionModel at 97.4% lines, DecisionPlanner is the OkHttp boundary (untested like the
other network paths). Refine is intentionally unsupported here (single-shot): the app
raises "single-shot backend" instead of silently ignoring the refinement.

## v0.5 — Files-by-Google-style UI (2026-10-18)

MainActivity is a two-tab shell: **Browse** (top bar with folder name + refresh + settings,
category tile grid from `scan/Categories.kt`, Recent list, long-press multi-select with a
selection bar — "Clean up N") and **Clean** (state machine: hero → scanned summary →
suggestion cards → applied, with sticky Apply N/Undo bottom bar, backend chip, refine box).
View factories live in `ui/UiKit.kt` (plain framework widgets, rounded GradientDrawables,
Google-blue palette — no Material/appcompat dependency, keep it that way).

Invariants:
- `Categories` is pure and unit-tested (100% lines); it owns categorize/summarize/recent/
  restrict. `restrict(scan, selection)` is how a Browse multi-select scopes a plan —
  empty set = whole scan. DecisionPlanner and PromptBuilder both receive the scoped copy.
- Plan rows still map 1:1 to `ValidatedPlan.valid` by index (`rows.getOrNull(idx)?.checked`)
  — the invalid rows are appended after and are never checkable. Don't reorder rows.
- `setBusy` gates plan/apply/undo; the bottom-bar apply label always recomputes from rows.
- Do not add Compose, appcompat, or Material Components. No XML layouts.

## v0.7 — Storage modes: SAF + All-files access (2026-10-18)

- `saf/Fs.kt` is the storage interface (opaque string handles: SAF = document URI,
  plain = absolute path). `SafFs` (per-tree SAF) and `PlainFs` (java.io.File,
  JVM-testable — no android imports) both implement it. Scanner + Executor + MainActivity
  only see `Fs`.
- **SAF mode (default, zero permissions):** Android 11+ refuses the Downloads root and
  storage-volume roots in `ACTION_OPEN_DOCUMENT_TREE` — that was the v0.6 blocker.
- **All-files mode (opt-in):** common-folder tiles on the Clean hero (Downloads, DCIM,
  Pictures, Documents, Music, Movies, WhatsApp, Telegram — paths from
  `Environment.getExternalStorageDirectory()`); first tap fires
  `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` (REQ_ALLFILES=42, `pendingCommon`
  resumes the tap on grant). Prefs: `plain_root` + `plain_label`; picking a SAF tree
  clears `plain_root`. Guard every `Environment.isExternalStorageManager()` call with
  `SDK_INT >= 30` (minSdk 26, lint NewApi).
- Scanner skip rules moved to pure `Scanner.skipsDir(rel)`: dot-dirs + LOST.DIR checked
  on the **last segment** (not the whole rel), `Android/data` + `Android/obb` as subtree
  prefixes, **`Android/media` is scanned** (WhatsApp/Telegram media).
- `Scanner.guessMime` touches `MimeTypeMap` (not mocked in JVM tests) — tests must feed
  entries with mime set (PlainFs returns mime via its own ext map; blank mime falls back
  only on device).

## v0.6 — OpenRouter System One + real folder browsing (2026-10-18)

- Decision backend has two flavors (`settings.dFlavor`): `clef` (raw Jev-compatible endpoint,
  e.g. Cloudflare Workers AI) and `openrouter` (`DecisionPlanner.OPENROUTER_URL` =
  `https://openrouter.ai/api/alpha/decisions`, model field required, Bearer key required).
- OpenRouter System One model list: `GET /api/v1/models?output_modalities=decisions` —
  PUBLIC (no key), ~27 models; default `DecisionPlanner.DEFAULT_OR_MODEL` = `~typesafe/jev-latest`.
  OR answers use `confidence` (not `probabilities`) — `DecisionModel.parseAnswers` accepts both.
- Folder visibility fix: Browse tab now has an "All folders" section (recursive dirs from
  `scan.dirPaths`, per-folder counts) with drilldown (`folderFilter` + breadcrumb); Clean tab
  has a "Move into" row opening a checkbox folder sheet → `pickedFolders`/`allowAutoFolders`
  → `DecisionModel.folders(scan, picked, allowAuto)` (picked first; allowAuto=false limits
  vocabulary to picked + KEEP). LLM backends get the picks appended to the prompt.
- Docs to update together with code: README Limits, docs/index hero badge, docs/guide backends.
