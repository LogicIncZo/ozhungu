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
cards (Zo / OpenAI-compatible) toggle section visibility; **Test** buttons do real
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
