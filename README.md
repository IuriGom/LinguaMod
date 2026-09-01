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

- On first launch the bundled course `assets/plugins/it.lingua` is copied to
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

## Test harness

The app is verified by an autonomous harness (see `BUILD_REPORT.md` for results):

- **Unit tests** (JVM): `./gradlew testDebugUnitTest` — plugin validator (every §8 rule),
  answer matcher (every §9 boundary), progress/streak/XP logic, conjugation cross-check.
- **Instrumented journeys** (emulator): `./gradlew connectedDebugAndroidTest` — fresh install,
  unit completion, checkpoint failure/retry, rotation, airplane mode, empty plugins, fuzz corpus.
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
offline-first (RECORD_AUDIO arrives with Stage 3, CAMERA with Stage 4 — each gated and
gracefully degrading).

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
