# LINGUA Plugin Format Specification — v1

**Status: normative.** Every `.lingua` plugin, the LinguaMod validator, the answer matcher, and all curriculum content MUST conform to this document. Where a stage document references "spec §N", this is the referenced document.

A `.lingua` file is a single UTF-8 encoded JSON document describing a complete language course: metadata, a strictly linear sequence of units, a dictionary, and optional stories.

---

## §1. Top-level structure

```json
{
  "formatVersion": 1,
  "meta":      { ... },   // §2
  "units":     [ ... ],   // §3
  "dictionary":[ ... ],   // §5
  "stories":   [ ... ]    // §7, may be empty
}
```

Unknown top-level keys are ignored. `formatVersion` MUST be `1`.

## §2. `meta`

| Field | Type | Required | Notes |
|---|---|---|---|
| `id` | string | yes | Lowercase ASCII, unique per loaded set (e.g. `"it"`). |
| `language` | string | yes | BCP-47 code, e.g. `"it"`. |
| `languageName` | string | yes | e.g. `"Italian"`. |
| `version` | int | yes | Monotonic content version, ≥ 1. |
| `description` | string | no | Free text. |

## §3. Units and lessons

A course is a **strictly linear path** of units. Each unit object:

| Field | Type | Required | Notes |
|---|---|---|---|
| `id` | string | yes | Unique. Convention: `"u1"`, `"u2"`, … |
| `number` | int | yes | Unit position. Units MUST be numbered consecutively starting at 1 and sorted by `number`. |
| `title` | string | yes | Display title, may be Italian. |
| `lessons` | array | yes | **Exactly 4** lesson objects (§4). |
| `checkpoint` | object | yes | §4.3. |

Lesson `type` values, in fixed order within a unit:

1. `vocabulary`
2. `grammar`
3. `mixed`
4. `oral` (speaking/listening)

## §4. Lessons and exercises

### §4.1 Lesson object

| Field | Type | Required | Notes |
|---|---|---|---|
| `id` | string | yes | Unique. Convention: `"u1l1"`. |
| `type` | string | yes | One of the four values above. |
| `title` | string | yes | |
| `dictionaryRefs` | string[] | yes | IDs of dictionary entries this lesson teaches/reviews. Every referenced ID MUST exist. For `vocabulary` lessons these are the *new* words; for `mixed` lessons (unit N ≥ 3) at least one ref MUST have `introducedInUnit < N` (recycling rule). |
| `grammarNotes` | string | no | Markdown-ish explanation. Conjugation tables MUST list all six persons. |
| `exercises` | array | yes | 4–12 exercise objects (§6). |

### §4.2 Common exercise fields

Every exercise, regardless of `type`:

| Field | Type | Required | Notes |
|---|---|---|---|
| `id` | string | yes | Unique across the whole file. Convention: `"u1l1e1"`. |
| `type` | string | yes | §6. |
| `prompt` | string | yes | Instruction shown to the learner (may be English or Italian). |
| `explanation` | string | yes | Teaching feedback shown after answering. MUST explain the *why*, never be empty or a bare "wrong". |
| `dictionaryRefs` | string[] | no | Entries used by this exercise. |

### §4.3 Checkpoint object

| Field | Type | Required | Notes |
|---|---|---|---|
| `id` | string | yes | Convention: `"u1c"`. |
| `exercises` | array | yes | **Exactly 10** exercises (exception: the checkpoint of unit 60 MAY have 15). Pass threshold is **≥ 80 %** correct. Checkpoint exercises mix types and cover all four lessons of the unit. |

## §5. Dictionary entries

| Field | Type | Required | Notes |
|---|---|---|---|
| `id` | string | yes | Unique, lowercase ASCII (e.g. `"il-ragazzo"`). |
| `word` | string | yes | Lemma **without** article for nouns (article is separate). |
| `article` | string | for nouns | One of `il lo la l' i gli le un uno una un'`. MUST be consistent with §8-R12 (lo/gli rules). |
| `translation` | string | yes | English meaning. |
| `partOfSpeech` | string | yes | `noun`, `verb`, `adjective`, `adverb`, `pronoun`, `preposition`, `conjunction`, `interjection`, `phrase`, `number`. |
| `gender` | string | for nouns | `m` or `f`. MUST NOT appear on non-nouns. |
| `examples` | array | yes | 1–3 objects `{ "it": "...", "en": "..." }`, both non-empty, grammatically correct. |
| `introducedInUnit` | int | yes | MUST reference an existing unit whose theme the word serves. |

Every dictionary entry MUST be used by at least one exercise or lesson `dictionaryRefs` (§8-R14). Verbs are listed in the infinitive; full conjugation appears in `grammarNotes`/explanations.

## §6. Exercise types

### §6.1 `multiple_choice`

```json
{ "id":"…", "type":"multiple_choice", "prompt":"…",
  "question":"Come ti chiami?",
  "options":["What is your name?","How are you?","Where are you from?","See you later"],
  "correctIndex":0, "explanation":"…" }
```

- `question`: string, required.
- `options`: **exactly 4** distinct strings (§8-R7).
- `correctIndex`: int 0–3.

### §6.2 `fill_blank`

```json
{ "id":"…", "type":"fill_blank", "prompt":"Complete the sentence.",
  "sentence":"Io ___ Marco.", "answers":["mi chiamo"], "explanation":"…" }
```

- `sentence` contains exactly one blank marked `___`.
- `answers`: non-empty array of accepted Italian (or English, if the blank asks for it) variants; `answers[0]` is canonical. Matching per §9.

### §6.3 `translation_it_en`  /  §6.4 `translation_en_it`

```json
{ "id":"…", "type":"translation_it_en", "prompt":"Translate to English.",
  "sourceIt":"Piacere di conoscerti.", "acceptedEn":["Nice to meet you."], "explanation":"…" }
```

- `translation_it_en`: `sourceIt` + `acceptedEn` (non-empty).
- `translation_en_it`: `sourceEn` + `acceptedIt` (non-empty). First variant is canonical. Matching per §9.

### §6.5 `listening`

```json
{ "id":"…", "type":"listening", "prompt":"Listen and choose the meaning.",
  "mode":"choice", "speakIt":"Buonasera, come stai?",
  "options":["Good evening, how are you?","…","…","…"], "correctIndex":0,
  "explanation":"…" }
```

- `speakIt`: required; the Italian text spoken by TTS. Revealed only after answering.
- `mode: "choice"`: `options` (exactly 4 English meanings) + `correctIndex`.
- `mode: "type"`: `acceptedIt` (non-empty; variant 0 equals `speakIt`); learner types what was heard, matched per §9.

### §6.6 `speaking`

```json
{ "id":"…", "type":"speaking", "prompt":"Say it in Italian.",
  "targetIt":"Mi chiamo Marco.", "minAccuracy":0.7, "explanation":"…" }
```

- `targetIt`: required. `minAccuracy`: optional, 0 < x ≤ 1, default **0.7**.
- Scoring: normalize both strings per §9, compute token-overlap vs `targetIt`, pass at ≥ `minAccuracy`.

### §6.7 `sentence_scramble`

```json
{ "id":"…", "type":"sentence_scramble", "prompt":"Put the words in order.",
  "tokens":["chiamo","Mi","Marco","."], "correctSentence":"Mi chiamo Marco.",
  "explanation":"…" }
```

- `tokens`: ≥ 2 strings, shown shuffled; duplicate tokens allowed and indistinguishable.
- `correctSentence`: whitespace-normalized token sequence MUST be a permutation of `tokens` (§8-R13).

## §7. Stories

```json
{ "id":"story1", "title":"Al bar", "unlockAfterUnit":5,
  "nodes":[ { "id":"n1", "speaker":"Barista", "textIt":"…", "textEn":"…",
              "choices":[ {"text":"…","next":"n2","correct":true},
                          {"text":"…","next":"n1","correct":false,"feedbackEn":"…"} ] },
            { "id":"n9", "speaker":"Narratore", "textIt":"…", "terminal":true } ] }
```

| Field | Notes |
|---|---|
| `id`, `title` | Unique `id`. |
| `unlockAfterUnit` | int; story unlocks when that unit's checkpoint is passed. May reference a unit not present in this plugin (dormant unlock; no error). |
| `nodes` | May be empty (`[]`): a registered placeholder the player renders as "arrives with a future content pack". |

Node fields: `id` (unique within the story), `speaker`, `textIt` (required), `textEn` (optional), `choices` (non-terminal nodes: 2–3), `terminal` (bool; terminal nodes have **no** choices).

Rules: every non-terminal node has **exactly one** `correct: true` choice; every `next` resolves to a node in the same story; every node can reach a terminal node; wrong choices carry teaching `feedbackEn` and loop (their `next` points back to the current node or a recovery node).

## §8. Validation rules

The validator rejects a plugin **entirely** on the first violation class found; it reports every rule violated with plugin id, rule id, and offending element id. Rules:

| Rule | Check |
|---|---|
| R1 | File parses as JSON; `formatVersion == 1`; total size ≤ 5 MB; JSON nesting depth ≤ 32. |
| R2 | All `meta` required fields present and well-formed. |
| R3 | All IDs (units, lessons, exercises, dictionary, stories, story nodes) unique in their scope; unit/lesson/exercise/dictionary IDs globally unique. |
| R4 | Units numbered consecutively from 1, sorted. |
| R5 | Each unit has exactly 4 lessons with types `vocabulary, grammar, mixed, oral` in order, plus a checkpoint. |
| R6 | Checkpoint has exactly 10 exercises (15 allowed iff `unit.number == 60`). |
| R7 | `multiple_choice` and `listening(choice)` have exactly 4 distinct options and `correctIndex` in range. |
| R8 | Every exercise has non-empty `prompt` and `explanation`. |
| R9 | Every noun has `article` + `gender` (`m`/`f`); non-nouns have neither. `article` ∈ allowed set. |
| R10 | No dangling refs: every `dictionaryRefs` ID exists; every `introducedInUnit` exists; every story `next` resolves. |
| R11 | No forward vocabulary references: an exercise/lesson in unit N MUST NOT reference (via `dictionaryRefs`) a dictionary entry with `introducedInUnit > N`. |
| R12 | Article-form consistency: `lo`/`gli` only before s+consonant, z, gn, ps, x, y; `l'` only before a vowel; `un'` only feminine before vowel; `uno` under lo-rules; `un` otherwise. |
| R13 | `sentence_scramble`: `correctSentence` tokens are a permutation of `tokens`. |
| R14 | Every dictionary entry is referenced by ≥ 1 exercise or lesson `dictionaryRefs`. |
| R15 | Every lesson has 4–12 exercises. `fill_blank` `answers`, translation `acceptedIt`/`acceptedEn`, `listening(type)` `acceptedIt` non-empty; `speaking.targetIt` non-empty, `minAccuracy` ∈ (0,1]; `listening.speakIt` non-empty. |
| R16 | `fill_blank.sentence` contains exactly one `___` blank. |
| R17 | Stories: exactly one correct choice per non-terminal node; 2–3 choices; wrong choices have `feedbackEn`; every node reaches a terminal. |
| R18 | Mixed lesson of unit N ≥ 3 references ≥ 1 entry with `introducedInUnit < N`. |

## §9. Answer matching (normative algorithm)

Used by `fill_blank`, `translation_*`, `listening(type)`, and speaking scoring.

**Normalization** (applied to both expected and actual):

1. Unicode NFC; lowercase.
2. Trim; collapse all whitespace runs to one space.
3. Normalize apostrophe variants (`'`, `'`, `` ` ``, `ʼ`, `´`) to `'`.
4. Strip punctuation `. , ! ? ; : " « » ( )` (apostrophes are **kept**).
5. Treat `l'amore` ≈ `l amore` (a space after an eliding apostrophe is insignificant).

**Comparison**: actual matches an accepted variant iff, token by token (same token count):

- tokens equal after normalization, **or**
- the expected token has length **≥ 4** and Levenshtein distance from the actual token is **exactly 1**.

Boundaries (all testable): length ≤ 3 with distance 1 → **reject**; distance ≥ 2 → **reject** regardless of length; extra/missing tokens → reject. An answer matching **any** accepted variant is correct.
