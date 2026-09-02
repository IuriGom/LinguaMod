# LinguaMod

An offline-first Android app that teaches Italian from absolute zero — a strictly linear
curriculum of 60 units (4 lessons + checkpoint each), all content living in external
`.lingua` plugin files. Built and verified **fully autonomously**: every acceptance check
is a command, not a claim.

## Build

Requirements: JDK 17, Android SDK (platforms;android-34, build-tools;34.0.0).

```bash
./gradlew assembleDebug        # debug APK -> app/build/outputs/apk/debug/
./gradlew assembleRelease      # minified release APK (debug key) -> app/build/outputs/apk/release/
```

APK size: release ≈ **2.9 MB** (cap: 20 MB since Stage 4's OCR camera). Debug builds include test tooling and are larger by design; the size gate applies to the shippable artifact.

## Plugin installation

Language content ships in `.lingua` files (format spec: [`docs/LINGUA_FORMAT.md`](docs/LINGUA_FORMAT.md)).

- The bundled Italian course lives at `plugins/it.lingua` in the repo root and is packaged
  into the APK via the symlink `app/src/main/assets/plugins/it.lingua` (Gradle asset
  packaging follows the symlink). It currently covers Units 1–10 (Stage 2); the format
  scales to the full 60-unit course.
- On first launch the bundled course is copied from assets to
  `Android/data/com.linguamod.app/files/plugins/` on the device.
- Drop additional `.lingua` files into that directory and tap **Rescan** on the Profile tab.
  Invalid files are rejected with a per-file error message and never partially loaded.

## Gamification (Stage 2B)

Gamification reflects progress; it never gates learning.

- **XP**: 5 per correct exercise, 10 bonus per completed lesson, 50 per passed checkpoint.
  The lesson-complete screen shows the gained amount; the total is on Profile.
- **Streak**: consecutive days with ≥ 1 lesson completed. Visual only — a broken streak
  resets silently, never blocks, no modals.
- **Levels**: exactly four, at phase completion — Level 1 when Unit 10's checkpoint is
  passed, then at Units 25, 40, 60. Profile shows the level and progress to the next.
- **Badges** (Profile): *Primo Passo* (Unit 1 checkpoint), *Dieci Unità* (Unit 10),
  *Perfezionista* (100% on any checkpoint), *Settimana Italiana* (7-day streak).
  Locked badges are grayed with their unlock condition.
- **Hearts**: 5 max, −1 per wrong answer, full refill on lesson completion, +1 per
  30 minutes. **Hearts never block**: at 0, lessons stay fully playable with a subtle
  "take your time" note. No popups, paywalls, or timers.
- **Gems**: 10 per passed checkpoint. Spent only on cosmetic themes in
  Profile → Appearance (accent colors, an alternate dark theme; 50–150 gems).
  Never purchasable with money.
- **Home progress bars**: % of current unit, current phase, and total course,
  computed from real progress rows; short plugins are handled gracefully.
- **Feature gates**: persisted flags (leaderboards, boss battles, stories 1–4, camera OCR,
  mixed practice) trip on their checkpoint triggers and show a one-time, non-blocking
  "New feature unlocked" snackbar. Unbuilt features never surface in the UI.

## Audio (Stage 3)

The app has a voice and ears, both fully offline-degradable:

- **Text-to-speech**: central `TtsManager` (Italian). Speaker icons sit on every
  dictionary entry, Italian example, and the correct answer after feedback;
  listening exercises autoplay once (the only autoplay) with a 0.75× replay
  button. Playback is cached: `synthesizeToFile` into `cacheDir/tts/<sha1>.wav`
  keyed by normalized text, cap 500 files with LRU eviction, cache checked
  before synthesis.
- **TTS setup**: if the device has no Italian voice, a one-time "Install the
  Italian voice" dialog deep-links to the system TTS settings (shown-once flag
  persisted). Until then every audio button **hides** rather than erroring —
  lessons stay fully playable.
- **Listening exercises**: large play button + slow replay, unlimited replays;
  choice or type mode; what was spoken is revealed only after answering.
- **Speaking exercises**: on-device recognition (it-IT, free-form). Scoring is
  token overlap vs the target after normalization, pass at ≥ `minAccuracy`
  (default 0.7). Feedback always shows what the recognizer heard and
  distinguishes a pronunciation issue from "completely different — try a
  quieter room" (overlap < 0.35). If the recognizer is unavailable (offline,
  no mic permission), the exercise silently becomes a listening variant of the
  same phrase with a one-time-per-session snackbar; lessons remain completable.
- **Permissions rationale**: `RECORD_AUDIO` is requested at runtime, only when
  the user taps the microphone on a speaking exercise, with an in-context
  rationale ("Audio goes to Google's on-device speech recognizer; nothing is
  stored"). Denial silently substitutes a listening exercise. There is no
  `INTERNET` permission at all. (`CAMERA` arrives with Stage 4, same pattern.)

## Advanced features (Stage 4)

Seasoning on top of the curriculum — every entry point is gated by a
`FeatureUnlocks` flag and never appears before its trigger:

- **Story Mode**: book icons anchor on the Home path after their unlocking
  unit's node. A story is a node graph: Italian text (optional English
  toggle), speaker label, 2–3 choices; a correct choice advances, a wrong one
  shows teaching feedback and loops. First completion: +30 XP and a
  per-story *Narratore* badge; replayable. Story 1 "Al bar" ships fully;
  stories 2–4 are registered placeholders that render "This story arrives
  with a future content pack."
- **Boss Battles**: a ⚔ overlay after every 5th unit. A boss is a 15-question
  gauntlet sampled from the checkpoints of completed units, weighted toward
  exercises you previously got wrong. 3 wrong answers lose the battle (no
  penalty); a win awards 100 XP, doubles your gems, and grants a per-boss
  badge (first win only).
- **Mixed Practice**: a Home card with 10 exercises sampled across all
  completed units and all exercise types — 60% weighted toward exercises
  previously wrong or never attempted, 40% random review. No hearts, no XP;
  results feed the review scheduler.
- **Camera OCR** (Dictionary → "📷 Scan text with the camera"): CameraX
  preview → tap-to-scan → recognized blocks → tap any word for a bottom sheet
  with its dictionary entry (lemma match with spec §9 normalization), or
  "not in your dictionary yet" with the recognized text and context. Known
  words award +2 XP and increment a lookup counter (most-looked-up words
  surface on Profile). Recognition is ML Kit Text Recognition v2, Latin
  script, **unbundled** — the model is delivered by Play Services and never
  ships in the APK. If the model is still downloading you get a one-time
  "downloading text recognizer…" note; on devices without Play Services the
  feature hides entirely after a one-time explanation. `CAMERA` is requested
  with a rationale; denial hides the scanner gracefully.
- **Your Records** (Profile, personal stats only — no server, no fabricated
  competitors): XP per day for the last 14 days, best checkpoint scores per
  unit, longest streak, most-looked-up OCR words.

### Feature unlock table

| Flag | Entry point | Trigger |
|---|---|---|
| `leaderboards` | Profile → "Your Records" | Unit 1 checkpoint passed |
| `bossBattles` | ⚔ boss rows after every 5th unit | Any 5 unit checkpoints passed |
| `story1` | 📖 "Al bar" after Unit 5 | Unit 5 checkpoint passed |
| `story2`–`story4` | future stories after Units 15/28/45 | Units 15/28/45 checkpoints passed |
| `ocrCamera` | Dictionary → camera button | Unit 10 checkpoint passed |
| `mixedPractice` | Home → "Mixed Practice" card | Unit 10 checkpoint passed (Phase 1 complete) |

## Plugin authoring quickstart

A course is a single UTF-8 JSON `.lingua` file — the normative format spec is
[`docs/LINGUA_FORMAT.md`](docs/LINGUA_FORMAT.md):

1. **Write**: `formatVersion: 1`, a `meta` block (`id` lowercase-ascii,
   `language`, `languageName`, integer `version`), `units` numbered
   consecutively from 1 (4 lessons each in the fixed order vocabulary /
   grammar / mixed / oral, 4–12 exercises per lesson, a 10-exercise
   checkpoint — 15 for Unit 60), a `dictionary` (every entry referenced by at
   least one lesson or exercise), and optional `stories`.
2. **Validate**: the same validator the app runs is a pure-JVM unit test —
   `PluginValidator.validateText(json)` (see `PluginValidatorTest`), or just
   drop the file in and read the per-rule rejection messages.
3. **Install**: copy the file to
   `Android/data/com.linguamod.app/files/plugins/` on the device and tap
   **Rescan** on the empty-plugins screen. Invalid files are rejected with
   per-rule errors and never partially loaded.
4. **Upgrade**: bump `meta.version`. On the next launch the app replaces the
   installed copy of the bundled plugin when the bundled asset is newer;
   progress rows key to unit numbers (not plugin versions), so upgrades never
   wipe progress, and dictionary lookup counts are carried over.

## Review — spaced repetition (Stage 3 §5)

"SM-2 lite", simple and inspectable, driven by the exercise results already
recorded during lessons:

- Every wrong exercise creates/updates a **review item** keyed by exercise ID;
  a later correct answer to a previously-wrong item updates it too.
- Scheduling: **wrong → due in 10 minutes** (again this session);
  **correct → interval × 2.5**, starting from 1 day, capped at 30 days.
- Home shows an "N exercises due for review" card above the path, only when
  N > 0. Tapping runs a review session re-rendered from the original exercise
  payloads with the normal lesson UI. Always optional, never blocks; the card
  persists until the due items are cleared.
- Wrong answers in review reschedule; review sessions never touch hearts, XP,
  or streaks.

## Flashcards (Stage 3 §6)

Dictionary → **Study flashcards**. The deck is the dictionary entries from
units the user has started (same gating rule as the dictionary). A card shows
Italian (article + speaker icon), flips to English + example + gender badge,
and is self-graded "Got it" / "Still learning" — the latter re-queues the card
within the session. A session is 20 cards or deck exhaustion. Flashcards are
independent of the SRS.

## Test harness

The app is verified by an autonomous harness (see `BUILD_REPORT.md` for results):

- **Unit tests** (JVM): `./gradlew testDebugUnitTest` — plugin validator (every §8 rule),
  answer matcher (every §9 boundary), progress/streak/XP logic, conjugation cross-check,
  speaking scorer boundaries, TTS cache cap/LRU, SRS scheduling with a fake `Clock`,
  flashcard deck gating/session rules, substitution exactly-once.
- **Instrumented journeys** (emulator): `./gradlew connectedDebugAndroidTest` — fresh install,
  unit completion, checkpoint failure/retry, rotation, airplane mode (incl. the extended
  audio journey asserting TTS requests and speaking substitution), no-Italian-voice,
  review card (3 due → cleared), flashcards, duplicate-token scramble, empty plugins,
  fuzz corpus, dictionary gating, gamification, the Stage 2 complete-units run
  (`CompleteUnitsStage2JourneyTest`: all 10 units unlocked in strict linear order and
  completed by the Solver Bot), and the Stage 4 journeys: story/boss/practice,
  gating regression (Solver Bot Units 1–3 → all Stage 4 entry points hidden → each
  flag trips its entry point), OCR via a scripted fake gateway (known/unknown words,
  model-downloading note, no-Play-Services hiding, permission denial), and the
  Phase-2 readiness check (generated 60-unit dummy plugin renders and scrolls the
  full path with jank measured via `dumpsys gfxinfo`).
- **Content static analysis** (JVM): `MixedLessonRecyclingTest` asserts mixed lessons
  recycle vocabulary from earlier units, plus the fixed lesson-type order and
  10-exercise checkpoints across all units.
- **Solver Bot** (`app/src/androidTest/.../solver/SolverBot.kt`): completes any lesson or
  checkpoint through the real UI by deriving answers from the plugin JSON; chaos mode answers
  wrong on purpose to test failure paths.
- **Fuzz corpus**: `tools/fuzz/generate_fuzz.py <out_dir> [count]` — ≥ 200 malformed plugins;
  the journey test generates its own corpus in-test and self-verifies rejection.
- **Shell journeys** in `tools/journeys/`: `airplane.sh`, `process_death.sh`, `monkey.sh`.
- **Fast-forward hook**: `com.linguamod.app.debug.TestHooks` (debug builds only, attached via
  reflection, compiled out of release).

## Architecture

Kotlin · Jetpack Compose (Material 3) · MVVM · Hilt DI · Room · Navigation Compose ·
Kotlinx Serialization · CameraX + ML Kit (unbundled, Play Services delivery).
minSdk 26, target/compileSdk 34. No analytics, no crashlytics, offline-first.
Two runtime permissions, both requested with in-context rationale and both
degrading gracefully on denial: `RECORD_AUDIO` (speaking exercises → silent
listening substitution) and `CAMERA` (Stage 4 OCR → scanner hides).

Test seams (all with fake implementations for tests): `Clock`, `TtsGateway`,
`SpeechRecognizerGateway`, `OcrGateway` — see Orchestrator §C.5.

## Course map

| Phase | Units | Content |
|---|---|---|
| 1 | 1–10 | Greetings, essere/avere, alphabet, numbers, articles, questions, ordering at a bar |
| 2 | 11–25 | Regular verbs, adjectives, family, restaurant, time, pronouns, piacere |
| 3 | 26–40 | Past tenses, reflexives, future, modals, ci/ne, comparatives |
| 4 | 41–60 | Conditional, subjunctive, hypotheticals, register, idioms, final exam |

Four stories unlock along the way (after units 5, 15, 28, 45). A dating thread runs through
the curriculum: meeting someone → getting to know them → making plans → meeting the family.
