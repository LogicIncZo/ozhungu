# Ozhungu (ஒழுங்கு — "order")

An agentic Android file organizer: pick a folder, scan it, ask the [Zo agent](https://zo.computer)
for a move/rename plan, review every line, apply only what you approve. Nothing is deleted;
nothing moves until you tap **Apply**; every applied step is journaled and reversible.

**Docs site:** https://logicinczo.github.io/ozhungu/ · [full guide](https://logicinczo.github.io/ozhungu/guide.html)

Part of the Zo ecosystem — talks to the same `/zo/ask` API as zo-voice, via the shared
[zo-kotlin](https://github.com/LogicIncZo/zo-kotlin) SDK (`dev.zocomputer:ask`, consumed from
source when `../zo-kotlin` is checked out next to this repo, Maven artifact otherwise).

**v0.2 — pluggable backends:** besides Zo, any OpenAI-compatible `/chat/completions`
endpoint works. The model picker defaults from [models.dev](https://models.dev/api.json):
it lists the OpenAI-compatible providers (SDK `@ai-sdk/openai-compatible` + an `api` base
URL) and sorts "ZDR-default" providers first — the ★ badge is text-derived from the
catalog itself (models.dev carries no retention field, so a provider is flagged only when
its own model descriptions mention zero data retention). The badge is a hint, not a
guarantee: verify the provider's policy before sending anything. Your API key stays in
EncryptedSharedPreferences on the device; refinement continuity is local (the app replays
its own transcript — these endpoints are stateless).

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

1. ⚙ Settings — a full-screen settings UI: radio-card backend choice (Zo /
   OpenAI-compatible / System One decision model), password-masked key fields with
   show/hide, **Test** buttons that round-trip the endpoint (Zo lists your models; OAI
   does `GET {base}/models`; decision models answer one `noul` question), a searchable
   in-place models.dev browser (★ ZDR-defaults first, provider-grouped), and a
   model-list fetch for Zo. Saves back to EncryptedSharedPreferences.
2. Pick folder — SAF tree picker (Downloads, internal storage root, SD card…).
3. Scan — walks the tree (depth ≤ 4, ≤ 600 files, snippets for small text files).
4. Ask for a plan — the Zo/OAI backends stream one; the decision backend answers in batches; the plan lands as a checklist of
   `✓ MOVE a/b.pdf → Bills/2026/a.pdf (reason)` rows.
5. Uncheck what you don't want → **Apply** → **Undo** reverses the whole batch newest-first.
6. Refine in the same conversation: "put invoices from March under Bills/2026".

**v0.4 — System One decision models:** a third backend speaks the Jev-compatible
"System One" API (TypeSafe Jev, Cloudflare Clef/Clef-flash on Workers AI, self-hosted
Clef) — the model answers typed folder-choice questions with calibrated probabilities
instead of generating text. The scan listing is the request `state`; every file becomes
one `choice` question (64 per request) over a shared candidate vocabulary (existing
folders first, then canonical Pictures/Documents/Bills/…, plus an explicit __keep__
option). Argmax at or above a configurable confidence floor becomes a move; everything
else — low confidence, __keep__, already-in-place — is skipped. Same validator, review
checklist, executor, and undo journal as the LLM backends.

## Limits (v0.4)

- Moves/renames only — no delete, no copy, no content editing.
- OAI-compatible mode sends the same scoped listing to whatever endpoint you configure —
  the contract, validator, and SAF-only execution are identical to the Zo backend.
- `/Android` trees (app-private dirs) are skipped by the scanner.
- A stale scan (files changed elsewhere mid-session) fails those rows individually, not the batch.
- Providers that refuse `moveDocument`/`renameDocument` (rare on API 26+) report a per-row failure.

## Tests & coverage

JVM-only tests, no emulator needed: `make test` (29 tests). Coverage via AGP unit-test
coverage (JaCoCo): `make coverage` prints a per-class table (scripts/coverage_summary.py).

The JVM-testable core — plan parser, validator, prompt builder, plan grammar, journal
codec, models.dev catalog parse, OAI transcript/SSE — sits at 96–100% line coverage.
The uncovered remainder is Android-instrumentation code by design: MainActivity, SAF
(SafFs/Scanner/Executor), encrypted Settings, and the OkHttp enqueue paths. Those need
Robolectric or a device; the project deliberately keeps CI emulator-free, so they are
verified on-device (SAF failures surface per-row in the journal, never as silent drops).

## Build

Local SDK at `/opt/android-sdk` (`local.properties`, gitignored). `make verify` =
JVM tests + lint + assembleDebug; `make apk` copies to `releases/ozhungu-latest.apk`.
GitHub Actions (`.github/workflows/android.yml`): tests + lint + debug APK artifact per push.

License: GPL-3.0-or-later.
