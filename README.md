# Ozhungu (ஒழுங்கு — "order")

An agentic Android file organizer: pick a folder, scan it, ask the [Zo agent](https://zo.computer)
for a move/rename plan, review every line, apply only what you approve. Nothing is deleted;
nothing moves until you tap **Apply**; every applied step is journaled and reversible.

**Docs site:** https://logicinczo.github.io/ozhungu/ · [full guide](https://logicinczo.github.io/ozhungu/guide.html)

Part of the Zo ecosystem — talks to the same `/zo/ask` API as zo-voice, via the shared
[zo-kotlin](https://github.com/LogicIncZo/zo-kotlin) SDK (`dev.zocomputer:ask`, consumed from
source when `../zo-kotlin` is checked out next to this repo, Maven artifact otherwise).

**v0.5 — Files-by-Google-style UI**: Browse tab (category tiles with counts, Recent list,
long-press multi-select that scopes the plan), Clean tab (suggestion cards, Apply N /
Undo bottom bar), top app bar. Plain-Views `UiKit` + pure `Categories` (100% covered);
47 JVM tests.

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

## Usage (v0.5 — Files-by-Google-style UI)

- **Browse tab** — category tiles (Images / Videos / Audio / Documents / Archives / Apps /
  Other, live counts + bytes), Recent list (newest first), per-category browsing, and
  tap-to-select files; the selection scopes the next plan.
- **Clean tab** — the agentic surface: a hero ("N files · organise them") with the
  backend chip, *Get suggestions* → plan checklist cards (move icon, from → to, reason,
  tap to keep/drop), sticky *Apply N* bar, *Refine* box, and *Undo last apply*.
- **First run** — "Organise your files" hero with one *Choose a folder* button; a
  ⚙ toolbar action opens the full-screen Settings (backend radio cards, Test
  round-trips, models.dev browser).

The pipeline underneath is unchanged: SAF scan → planner (Zo / OpenAI-compatible /
System One decision model) → PlanValidator → apply + journal undo.

## Legacy CLI-style flow (pre-0.5)

## Usage

The UI is Files-by-Google-style: **Browse** tab (category tiles, Recent, long-press
multi-select) and **Clean** tab (the organise loop).

1. First run: pick a folder (SAF tree picker — Downloads, internal storage root, SD card…)
   and set a backend in ⚙ Settings.
2. **Clean** tab → *Scan* walks the tree (depth ≤ 4, ≤ 600 files, snippets for small text
   files). The Browse tab fills with category tiles and a Recent list.
3. Select files in Browse (long-press) to scope a plan to just those, or leave the
   selection empty to plan over the whole scan.
4. *Get suggestions* — the Zo/OAI backends stream a plan; the decision backend answers in
   batches; suggestions land as reviewable cards (ticked by default, rejections shown with
   the rule that killed them).
5. **Apply N** (bottom bar) applies only the ticked rows; **Undo** reverses the whole batch
   newest-first.
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

**v0.6.0** — OpenRouter System One flavor (Decisions API, public 27-model list,
`~typesafe/jev-latest` default; accepts both `probabilities` and `confidence` answer shapes),
an All-folders browser in Browse (recursive, drilldown), and a Move-into folder picker on
Clean that feeds the decision model's option set (picked folders first).

## Limits (v0.6)

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
