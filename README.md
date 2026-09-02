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

APK size: release ≈ **1.8 MB** (cap: 15 MB). Debug builds include test tooling and are larger by design; the size gate applies to the shippable artifact.

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
  stored"). Denial silently substitutes a listening exercise. It is the only
  permission the app requests — there is no `INTERNET` permission at all.

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
  fuzz corpus, dictionary gating, gamification, and the Stage 2 complete-units run
  (`CompleteUnitsStage2JourneyTest`: all 10 units unlocked in strict linear order and
  completed by the Solver Bot).
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
Kotlinx Serialization. minSdk 26, target/compileSdk 34. No analytics, no crashlytics,
offline-first: `RECORD_AUDIO` (Stage 3) is the only runtime permission — requested with
rationale, degrading to silent listening substitution on denial; CAMERA arrives with
Stage 4 on the same gated pattern.

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
