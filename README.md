# Ozhungu (ஒழுங்கு — "order")

An agentic Android file organizer: pick a folder, scan it, ask the [Zo agent](https://zo.computer)
for a move/rename plan, review every line, apply only what you approve. Nothing is deleted;
nothing moves until you tap **Apply**; every applied step is journaled and reversible.

Part of the Zo ecosystem — talks to the same `/zo/ask` API as zo-voice, via the shared
[zo-kotlin](https://github.com/LogicIncZo/zo-kotlin) SDK (`dev.zocomputer:ask`, consumed from
source when `../zo-kotlin` is checked out next to this repo, Maven artifact otherwise).

## How it works

```
SAF tree ──Scanner──▶ ScanResult ──PromptBuilder──▶ Zo /zo/ask ──PlanParser──▶ Plan
                                                                          │
                                              PlanValidator (local, strict) │
                                                                          ▼
                                              checked actions ──Executor──▶ SAF move/rename
                                                                          │
                                                                   Journal ──▶ Undo
```

The model only ever *proposes*. A deterministic, unit-tested validator rejects hallucinated
sources, `..`/absolute escapes, overwrites of scanned files, target collisions, chaining,
and targets that are existing folders. Execution goes through SAF with the user's own
persisted tree grant — no `MANAGE_EXTERNAL_STORAGE`, no `All files access` prompt.

## Usage

1. ⚙ — paste a Zo access token (Settings ▸ Advanced ▸ Access Tokens), optional model override.
2. Pick folder — SAF tree picker (Downloads, internal storage root, SD card…).
3. Scan — walks the tree (depth ≤ 4, ≤ 600 files, snippets for small text files).
4. Ask Zo for a plan — streams; the plan lands as a checklist of
   `✓ MOVE a/b.pdf → Bills/2026/a.pdf (reason)` rows.
5. Uncheck what you don't want → **Apply** → **Undo** reverses the whole batch newest-first.
6. Refine in the same conversation: "put invoices from March under Bills/2026".

## Limits (v0.1)

- Moves/renames only — no delete, no copy, no content editing.
- `/Android` trees (app-private dirs) are skipped by the scanner.
- A stale scan (files changed elsewhere mid-session) fails those rows individually, not the batch.
- Providers that refuse `moveDocument`/`renameDocument` (rare on API 26+) report a per-row failure.

## Build

Local SDK at `/opt/android-sdk` (`local.properties`, gitignored). `make verify` =
JVM tests + lint + assembleDebug; `make apk` copies to `releases/ozhungu-latest.apk`.
GitHub Actions (`.github/workflows/android.yml`): tests + lint + debug APK artifact per push.

License: GPL-3.0-or-later.
