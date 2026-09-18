# LinguaMod v1.0

An offline-first Android app that teaches Italian from absolute zero to
conversation. A strictly linear curriculum of **60 units + 4 stories** lives
entirely in external `.lingua` plugin files; the app ships with the complete
Italian course bundled. **~100% offline**: no `INTERNET` permission, no
analytics, no accounts — the single online touch is the OCR text-recognition
model, delivered by Play Services on first camera use (see Known limitations).

- **Android 8.0+** (minSdk 26), target/compileSdk 34
- Release APK: **3,283,218 bytes ≈ 3.13 MB** (cap 20 MB)
- `LinguaMod-v1.0.apk` at the repo root is the signed release build
- Built and verified **fully autonomously**: every acceptance check is a
  command, not a claim — see `BUILD_REPORT.md`

## Features

- **Lessons** — 4 per unit (vocabulary / grammar / mixed / oral) across
  **7 exercise types**: multiple choice, fill-in-the-blank, translate
  IT→EN, translate EN→IT, listening, speaking, sentence scramble.
- **Checkpoints** — a 10-exercise gate at the end of every unit (15 for the
  Unit 60 final exam). Pass it to unlock the next unit; fail it and retry
  with no penalty beyond hearts.
- **TTS audio** — every Italian sentence is speakable via on-device
  text-to-speech, cached to disk (cap 500 files, LRU). If the device has no
  Italian voice, a one-time dialog deep-links to system TTS settings and
  every audio button hides rather than erroring.
- **Listening & speaking** — listening exercises autoplay once with a slow
  replay; speaking exercises use the on-device speech recognizer (it-IT) with
  token-overlap scoring (pass ≥ 0.7, default). If the recognizer is
  unavailable or the mic is denied, the exercise **silently becomes a
  listening variant** of the same phrase — lessons always complete.
- **Sentence scramble** — tap-to-place word ordering; exact-order grading
  with duplicate-token-proof interaction.
- **SRS review ("SM-2 lite")** — every wrong answer creates a review item;
  wrong → due again in 10 minutes, correct → interval × 2.5 from 1 day,
  capped at 30 days. A Home card surfaces due counts; review sessions reuse
  the lesson UI and never touch hearts/XP/streaks.
- **Flashcards** — dictionary-backed deck from started units, 20-card
  sessions, self-graded with in-session re-queue.
- **Stories (4)** — branching Italian dialogues with wrong-choice teaching
  loops; +30 XP and a per-story *Narratore* badge on first completion;
  replayable.
- **Boss battles** — a 15-question gauntlet after every 5th unit, weighted
  toward exercises you got wrong. 3 strikes loses (no penalty); a win awards
  100 XP, doubles your gems, and grants a per-boss badge.
- **Mixed practice** — 10 exercises sampled across all completed units
  (60% previously-wrong/untried, 40% random review); no hearts/XP, feeds the
  SRS scheduler.
- **OCR camera** — point the camera at Italian text, tap any recognized word
  for its dictionary entry (known words award +2 XP; look-ups feed Profile
  stats).
- **Gamification** — XP (5/correct + 10/lesson + 50/checkpoint), streaks,
  four levels (at units 10/25/40/60), hearts (5 max, −1/wrong, +1/30 min —
  **never blocking**), gems (10/checkpoint, spent only on cosmetic themes),
  badges (*Primo Passo*, *Dieci Unità*, *Perfezionista*, *Settimana
  Italiana*, *Narratore*, *Boss Champion*), accent-color/dark themes.
- **Personal stats** — "Your Records": XP/day for 14 days, best checkpoint
  scores, longest streak, most-looked-up words. Personal only — no server,
  no fabricated competitors.

## Feature unlock table

Every advanced feature is gated by a persisted `FeatureUnlocks` flag and
never appears before its trigger:

| Flag | Entry point | Trigger |
|---|---|---|
| `leaderboards` | Profile → "Your Records" | Unit 1 checkpoint passed |
| `bossBattles` | ⚔ boss rows after every 5th unit | Any 5 unit checkpoints passed |
| `story1` | 📖 "Al bar" after Unit 5 | Unit 5 checkpoint passed |
| `story2` | 📖 "Il ristorante" after Unit 15 | Unit 15 checkpoint passed |
| `story3` | 📖 "Il messaggio" after Unit 28 | Unit 28 checkpoint passed |
| `story4` | 📖 "A cena dalla famiglia" after Unit 45 | Unit 45 checkpoint passed |
| `ocrCamera` | Dictionary → camera button | Unit 10 checkpoint passed |
| `mixedPractice` | Home → "Mixed Practice" card | Unit 10 checkpoint passed (Phase 1 complete) |

Flags trip a one-time, non-blocking "New feature unlocked" snackbar.

## Curriculum overview

60 units in 4 phases, each unit = 4 lessons + a checkpoint. A dating thread
runs through the course: meeting someone → getting to know them → making
plans → meeting the family. Four stories unlock along the way (after units
5, 15, 28, 45).

**Phase 1 — Foundations (units 1–10)**

1. Il primo incontro · 2. Di dove sei? · 3. L'alfabeto · 4. I numeri ·
5. Gli articoli · 6. Essere e avere · 7. La frase semplice · 8. Le domande ·
9. Al bar · 10. La mia giornata

**Phase 2 — Essentials (units 11–25)**

11. I verbi in -are · 12. I verbi in -ere e -ire · 13. Gli aggettivi ·
14. La mia famiglia · 15. Al ristorante · 16. Che ore sono? ·
17. I numeri grandi · 18. In città · 19. I pronomi diretti ·
20. Mi piace · 21. Il weekend · 22. La casa · 23. I vestiti ·
24. Il corpo e la salute · 25. Un appuntamento

**Phase 3 — Intermediate (units 26–40)**

26. Il passato prossimo I · 27. Il passato prossimo II ·
28. I verbi riflessivi · 29. Quando ero piccolo · 30. Ieri e allora ·
31. Il futuro · 32. Volere, potere, dovere · 33. I pronomi indiretti ·
34. Ci · 35. Ne · 36. I comparativi · 37. I superlativi ·
38. Al telefono · 39. I viaggi · 40. La mia settimana

**Phase 4 — Advanced (units 41–60)**

41. Il condizionale I · 42. Il condizionale II ·
43. Il congiuntivo presente I · 44. Il congiuntivo presente II ·
45. Il congiuntivo passato · 46. Lei o tu? · 47. Il periodo ipotetico I ·
48. Il congiuntivo imperfetto · 49. Il periodo ipotetico II ·
50. I pronomi combinati · 51. La forma passiva · 52. Il si impersonale ·
53. Frasi complesse · 54. Gerundio e infinito · 55. Il trapassato ·
56. Espressioni idiomatiche · 57. L'Italia regionale ·
58. La cultura italiana · 59. Conversazione libera · 60. Esame finale

Dictionary: 384 entries. Bundled plugin: `plugins/it.lingua`
(meta `version` 43).

## Plugin authoring quickstart

A course is a single UTF-8 JSON `.lingua` file — the normative format spec is
[`docs/LINGUA_FORMAT.md`](docs/LINGUA_FORMAT.md).

1. **Write**: `formatVersion: 1`, a `meta` block (`id` lowercase-ascii,
   `language`, `languageName`, integer `version`), `units` numbered
   consecutively from 1 (4 lessons each in the fixed order vocabulary /
   grammar / mixed / oral, 4–12 exercises per lesson, a 10-exercise
   checkpoint — 15 for Unit 60), a `dictionary` (every entry referenced by
   at least one lesson or exercise), and optional `stories`.
2. **Validate**: the same validator the app runs is a pure-JVM unit test —
   `PluginValidator.validateText(json)` (see `PluginValidatorTest`), or just
   drop the file in and read the per-rule rejection messages.
3. **Where plugins load from**: on first launch the bundled course is copied
   from APK assets to `Android/data/com.linguamod.app/files/plugins/` on the
   device (via the symlink `app/src/main/assets/plugins/it.lingua`). Drop
   additional `.lingua` files into that directory and tap **Rescan** on the
   Profile tab. Invalid files are rejected with a per-file error and never
   partially loaded.
4. **Upgrade**: bump `meta.version`. On the next launch the app replaces the
   installed copy of the bundled plugin when the bundled asset is newer;
   progress rows key to unit numbers (not plugin versions), so upgrades
   never wipe progress, and dictionary lookup counts are carried over.

## Testing guide

The app is verified by an autonomous harness (results in `BUILD_REPORT.md`):

- **Unit tests** (JVM): `./gradlew testDebugUnitTest` — plugin validator
  (every §8 rule), answer matcher (every §9 boundary), progress/streak/XP
  logic, conjugation cross-check, speaking scorer boundaries, TTS cache
  cap/LRU, SRS scheduling with a fake `Clock`, flashcard sessions, story
  validation, boss/practice sampling, objective content audit
  (`ObjectiveAuditTest`), plugin upgrades, phase-2 readiness.
- **Instrumented journeys** (emulator): `./gradlew connectedDebugAndroidTest`
  — fresh install, per-unit Solver Bot playthroughs of all 60 units, chaos
  (wrong-answer) passes, checkpoint fail/retry, rotation, airplane mode,
  no-Italian-voice, review/flashcard journeys, story/boss/practice journeys,
  OCR via a scripted fake gateway, gating regression, fuzz corpus rejection,
  60-unit dummy-plugin rendering. Last recorded full pass: **97/97 green**
  (see `BUILD_REPORT.md`; Stage 8 stress-gauntlet numbers land there).
- **Solver Bot** (`app/src/androidTest/java/com/linguamod/app/solver/SolverBot.kt`):
  completes any lesson or checkpoint through the real UI by deriving answers
  from the plugin JSON; chaos mode answers wrong on purpose to exercise
  failure paths.
- **Fuzz**: `python3 tools/fuzz/generate_fuzz.py <out_dir> [count]` — ≥ 200
  malformed plugins; `tools/journeys/fuzz.sh` runs the on-device fuzz
  journey against a generated corpus.
- **Monkey**: `bash tools/journeys/monkey.sh` — wraps
  `adb shell monkey -p com.linguamod.app --pct-syskeys 0 20000`; zero
  crashes/ANRs allowed.
- **Shell journeys** in `tools/journeys/`: `airplane.sh` (radios off, full
  lesson), `process_death.sh`, `cold_start.sh`, `hostile_display.sh`,
  `release_audit.sh` (asserts no test classes in the release APK),
  `run_full_suite.sh` (every journey class on a device).

## APK

```bash
./gradlew assembleDebug     # debug APK (test tooling included, larger)
./gradlew assembleRelease   # minified release APK -> app/build/outputs/apk/release/
```

- Release APK: **3,283,218 bytes ≈ 3.13 MB** (cap 20 MB).
- `LinguaMod-v1.0.apk` at the repo root is that release build, renamed —
  install it with `adb install LinguaMod-v1.0.apk`.
- `tools/journeys/release_audit.sh` verifies the release dex contains no
  test/debug classes (SolverBot, TestHooks, fakes, journeys).

## Known limitations

- **TTS voice quality** depends on the device's installed Italian TTS voice;
  the app can't bundle one. Voices vary a lot between devices.
- **Speaking exercises require Google's on-device speech recognizer**. On
  devices without it (or with the mic denied) speaking exercises silently
  degrade to listening variants — always completable, but no pronunciation
  practice.
- **OCR needs one online touch**: the ML Kit text-recognition model is
  unbundled and downloads via Play Services on first camera use. After that
  it's offline. Devices without Play Services hide the feature after a
  one-time explanation.
- **Content is machine-generated and has not been reviewed by a native
  speaker.** A Stage 8 fresh-eyes audit fixed 61 of 62 findings (1 verified
  correct), but a professional editorial pass would still be worthwhile.

## Architecture

Kotlin · Jetpack Compose (Material 3) · MVVM · Hilt DI · Room · Navigation
Compose · Kotlinx Serialization · CameraX + ML Kit (unbundled). Two runtime
permissions, both requested with in-context rationale and degrading
gracefully on denial: `RECORD_AUDIO` and `CAMERA`. Test seams (each with
fake implementations): `Clock`, `TtsGateway`, `SpeechRecognizerGateway`,
`OcrGateway`. Debug-only fast-forward hooks (`TestHooks`, Solver Bot, fake
gateways) are compiled out of release.

## Build

Requirements: JDK 17, Android SDK (platforms;android-34, build-tools;34.0.0).
`JAVA_HOME`/`ANDROID_HOME` must point at them; Gradle 8.11.1 via the wrapper.
