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
