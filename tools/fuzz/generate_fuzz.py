#!/usr/bin/env python3
"""Generate a fuzz corpus of malformed .lingua plugins.

Usage: python3 generate_fuzz.py <out_dir> [count]

Produces >= `count` (default 200) malformed files covering: truncated JSON,
wrong types, duplicate IDs, dangling refs, forward refs, 4-option violations,
missing gender, huge files, deep nesting, plus mutations. Every file must be
rejected by the LinguaMod validator with a clear message and zero crashes.
"""
import json
import os
import random
import sys

VALID_BASE = {
    "formatVersion": 1,
    "meta": {"id": "fuzz", "language": "it", "languageName": "Italian", "version": 1},
    "units": [
        {
            "id": "u1", "number": 1, "title": "Fuzz",
            "lessons": [
                {"id": "u1l1", "type": "vocabulary", "title": "V",
                 "dictionaryRefs": ["ciao"],
                 "exercises": [
                     {"id": "u1l1e1", "type": "multiple_choice", "prompt": "p",
                      "question": "Ciao?", "options": ["a", "b", "c", "d"],
                      "correctIndex": 0, "explanation": "e"},
                     {"id": "u1l1e2", "type": "fill_blank", "prompt": "p",
                      "sentence": "___ mondo", "answers": ["Ciao"], "explanation": "e"},
                     {"id": "u1l1e3", "type": "translation_it_en", "prompt": "p",
                      "sourceIt": "Ciao", "acceptedEn": ["Hello"], "explanation": "e"},
                     {"id": "u1l1e4", "type": "translation_en_it", "prompt": "p",
                      "sourceEn": "Hello", "acceptedIt": ["Ciao"], "explanation": "e"},
                 ]},
                {"id": "u1l2", "type": "grammar", "title": "G", "dictionaryRefs": ["ciao"],
                 "exercises": [
                     {"id": "u1l2e1", "type": "multiple_choice", "prompt": "p",
                      "question": "Ciao?", "options": ["a", "b", "c", "d"],
                      "correctIndex": 0, "explanation": "e"},
                     {"id": "u1l2e2", "type": "multiple_choice", "prompt": "p",
                      "question": "Ciao?", "options": ["a", "b", "c", "d"],
                      "correctIndex": 1, "explanation": "e"},
                     {"id": "u1l2e3", "type": "multiple_choice", "prompt": "p",
                      "question": "Ciao?", "options": ["a", "b", "c", "d"],
                      "correctIndex": 2, "explanation": "e"},
                     {"id": "u1l2e4", "type": "multiple_choice", "prompt": "p",
                      "question": "Ciao?", "options": ["a", "b", "c", "d"],
                      "correctIndex": 3, "explanation": "e"},
                 ]},
                {"id": "u1l3", "type": "mixed", "title": "M", "dictionaryRefs": ["ciao"],
                 "exercises": [
                     {"id": "u1l3e1", "type": "multiple_choice", "prompt": "p",
                      "question": "Ciao?", "options": ["a", "b", "c", "d"],
                      "correctIndex": 0, "explanation": "e"},
                     {"id": "u1l3e2", "type": "multiple_choice", "prompt": "p",
                      "question": "Ciao?", "options": ["a", "b", "c", "d"],
                      "correctIndex": 1, "explanation": "e"},
                     {"id": "u1l3e3", "type": "multiple_choice", "prompt": "p",
                      "question": "Ciao?", "options": ["a", "b", "c", "d"],
                      "correctIndex": 2, "explanation": "e"},
                     {"id": "u1l3e4", "type": "multiple_choice", "prompt": "p",
                      "question": "Ciao?", "options": ["a", "b", "c", "d"],
                      "correctIndex": 3, "explanation": "e"},
                 ]},
                {"id": "u1l4", "type": "oral", "title": "O", "dictionaryRefs": ["ciao"],
                 "exercises": [
                     {"id": "u1l4e1", "type": "listening", "prompt": "p", "mode": "choice",
                      "speakIt": "Ciao", "options": ["a", "b", "c", "d"],
                      "correctIndex": 0, "explanation": "e"},
                     {"id": "u1l4e2", "type": "listening", "prompt": "p", "mode": "type",
                      "speakIt": "Ciao", "acceptedIt": ["Ciao"], "explanation": "e"},
                     {"id": "u1l4e3", "type": "speaking", "prompt": "p",
                      "targetIt": "Ciao", "explanation": "e"},
                     {"id": "u1l4e4", "type": "sentence_scramble", "prompt": "p",
                      "tokens": ["Ciao", "mondo"], "correctSentence": "Ciao mondo",
                      "explanation": "e"},
                 ]},
            ],
            "checkpoint": {"id": "u1c", "exercises": [
                {"id": f"u1ce{i}", "type": "multiple_choice", "prompt": "p",
                 "question": "Ciao?", "options": ["a", "b", "c", "d"],
                 "correctIndex": i % 4, "explanation": "e"} for i in range(10)
            ]},
        }
    ],
    "dictionary": [
        {"id": "ciao", "word": "ciao", "translation": "hello",
         "partOfSpeech": "interjection",
         "examples": [{"it": "Ciao!", "en": "Hello!"}], "introducedInUnit": 1}
    ],
    "stories": [],
}


def mutations():
    """Yield (name, bytes) malformed variants."""
    base = json.dumps(VALID_BASE, indent=1)
    b = base.encode()

    # 1-30: truncations at various points
    for i in range(30):
        cut = random.randint(1, len(b) - 1)
        yield f"trunc_{i:03d}", b[:cut]

    # 31-50: bit flips / byte swaps
    for i in range(20):
        buf = bytearray(b)
        for _ in range(random.randint(1, 8)):
            pos = random.randint(0, len(buf) - 1)
            buf[pos] = random.randint(0, 255)
        yield f"bitflip_{i:03d}", bytes(buf)

    def mod(name, fn):
        obj = json.loads(base)
        fn(obj)
        return name, json.dumps(obj).encode()

    # wrong types
    yield mod("wrongtype_format_version", lambda o: o.update(formatVersion="one"))
    yield mod("wrongtype_units_obj", lambda o: o.update(units={"a": 1}))
    yield mod("wrongtype_meta_list", lambda o: o.update(meta=[1, 2]))
    yield mod("wrongtype_number_str", lambda o: o["units"][0].update(number="one"))
    yield mod("wrongtype_options_str", lambda o: o["units"][0]["lessons"][0]["exercises"][0].update(options="abcd"))
    yield mod("wrongtype_correctindex", lambda o: o["units"][0]["lessons"][0]["exercises"][0].update(correctIndex="zero"))
    yield mod("wrongtype_dict_str", lambda o: o.update(dictionary="nope"))
    yield mod("wrongtype_stories_int", lambda o: o.update(stories=42))
    yield mod("wrongtype_lessons_null", lambda o: o["units"][0].update(lessons=None))
    yield mod("wrongtype_gender_int", lambda o: o["dictionary"][0].update(gender=3))

    # duplicate IDs
    yield mod("dup_unit_id", lambda o: o["units"].append(dict(o["units"][0])))
    yield mod("dup_dict_id", lambda o: o["dictionary"].append(dict(o["dictionary"][0])))
    yield mod("dup_exercise_id", lambda o: o["units"][0]["lessons"][0]["exercises"].append(
        dict(o["units"][0]["lessons"][0]["exercises"][0])))

    # dangling refs
    yield mod("dangling_dictref", lambda o: o["units"][0]["lessons"][0]["dictionaryRefs"].append("nonexistent"))
    yield mod("dangling_unit_ref", lambda o: o["dictionary"][0].update(introducedInUnit=99))
    yield mod("dangling_story_next", lambda o: o.update(stories=[{
        "id": "s1", "title": "t", "unlockAfterUnit": 1,
        "nodes": [{"id": "n1", "speaker": "x", "textIt": "t",
                   "choices": [{"text": "a", "next": "nope", "correct": True},
                               {"text": "b", "next": "n1", "correct": False, "feedbackEn": "f"}]}]}]))

    # forward refs
    def fwd(o):
        o["units"].append(json.loads(json.dumps(o["units"][0])))
        o["units"][1].update(id="u2", number=2)
        o["units"][1]["checkpoint"]["id"] = "u2c"
        for j, l in enumerate(o["units"][1]["lessons"]):
            l["id"] = f"u2l{j+1}"
            for k, e in enumerate(l["exercises"]):
                e["id"] = f"u2l{j+1}e{k+1}"
            for k, e in enumerate(l.get("exercises", [])):
                e["id"] = f"u2l{j+1}e{k+1}"
        for k, e in enumerate(o["units"][1]["checkpoint"]["exercises"]):
            e["id"] = f"u2ce{k}"
        o["dictionary"][0]["introducedInUnit"] = 2  # word introduced in u2 used in u1
    yield mod("forward_vocab_ref", fwd)

    # 4-option violations
    yield mod("options_3", lambda o: o["units"][0]["lessons"][0]["exercises"][0].update(options=["a", "b", "c"]))
    yield mod("options_5", lambda o: o["units"][0]["lessons"][0]["exercises"][0].update(options=["a", "b", "c", "d", "e"]))
    yield mod("options_dup", lambda o: o["units"][0]["lessons"][0]["exercises"][0].update(options=["a", "a", "b", "c"]))
    yield mod("correctindex_oob", lambda o: o["units"][0]["lessons"][0]["exercises"][0].update(correctIndex=4))
    yield mod("correctindex_neg", lambda o: o["units"][0]["lessons"][0]["exercises"][0].update(correctIndex=-1))
    yield mod("listening_options_2", lambda o: o["units"][0]["lessons"][3]["exercises"][0].update(options=["a", "b"]))

    # missing gender / article
    def noun(o):
        o["dictionary"][0].update(partOfSpeech="noun")
        o["dictionary"][0].pop("gender", None)
        o["dictionary"][0].pop("article", None)
    yield mod("missing_gender", noun)
    yield mod("bad_gender", lambda o: o["dictionary"][0].update(partOfSpeech="noun", article="il", gender="x"))
    yield mod("bad_article", lambda o: o["dictionary"][0].update(partOfSpeech="noun", article="gli", gender="m", word="ciao"))
    yield mod("article_wrong_form", lambda o: o["dictionary"][0].update(partOfSpeech="noun", article="lo", gender="m", word="ciao"))
    yield mod("gender_on_verb", lambda o: o["dictionary"][0].update(partOfSpeech="verb", gender="m"))

    # structural violations
    yield mod("lessons_3", lambda o: o["units"][0].update(lessons=o["units"][0]["lessons"][:3]))
    yield mod("lessons_5", lambda o: o["units"][0]["lessons"].append(dict(o["units"][0]["lessons"][0], id="u1l5")))
    yield mod("wrong_lesson_order", lambda o: o["units"][0]["lessons"][0].update(type="grammar"))
    yield mod("checkpoint_9", lambda o: o["units"][0]["checkpoint"].update(
        exercises=o["units"][0]["checkpoint"]["exercises"][:9]))
    yield mod("checkpoint_11", lambda o: o["units"][0]["checkpoint"]["exercises"].append(
        dict(o["units"][0]["checkpoint"]["exercises"][0], id="u1ce10")))
    yield mod("unit_number_gap", lambda o: o["units"][0].update(number=2))
    yield mod("missing_explanation", lambda o: o["units"][0]["lessons"][0]["exercises"][0].update(explanation=""))
    yield mod("missing_prompt", lambda o: o["units"][0]["lessons"][0]["exercises"][0].update(prompt=""))
    yield mod("blank_missing", lambda o: o["units"][0]["lessons"][0]["exercises"][1].update(sentence="Nessuno spazio"))
    yield mod("blank_double", lambda o: o["units"][0]["lessons"][0]["exercises"][1].update(sentence="___ ___ ciao"))
    yield mod("answers_empty", lambda o: o["units"][0]["lessons"][0]["exercises"][1].update(answers=[]))
    yield mod("scramble_not_perm", lambda o: o["units"][0]["lessons"][3]["exercises"][3].update(
        tokens=["Ciao", "terra"]))
    yield mod("exercises_3", lambda o: o["units"][0]["lessons"][0].update(
        exercises=o["units"][0]["lessons"][0]["exercises"][:3]))
    yield mod("orphan_dict_entry", lambda o: o["dictionary"].append({
        "id": "orphan", "word": "orphan", "translation": "x", "partOfSpeech": "adverb",
        "examples": [{"it": "x", "en": "y"}], "introducedInUnit": 1}))
    yield mod("speaking_bad_accuracy", lambda o: o["units"][0]["lessons"][3]["exercises"][2].update(minAccuracy=1.5))
    yield mod("story_two_correct", lambda o: o.update(stories=[{
        "id": "s1", "title": "t", "unlockAfterUnit": 1,
        "nodes": [{"id": "n1", "speaker": "x", "textIt": "t",
                   "choices": [{"text": "a", "next": "n2", "correct": True},
                               {"text": "b", "next": "n2", "correct": True}]},
                  {"id": "n2", "speaker": "x", "textIt": "t", "terminal": True}]}]))
    yield mod("story_unreachable_terminal", lambda o: o.update(stories=[{
        "id": "s1", "title": "t", "unlockAfterUnit": 1,
        "nodes": [{"id": "n1", "speaker": "x", "textIt": "t",
                   "choices": [{"text": "a", "next": "n1", "correct": True},
                               {"text": "b", "next": "n1", "correct": False, "feedbackEn": "f"}]},
                  {"id": "n2", "speaker": "x", "textIt": "t", "terminal": True}]}]))

    # deep nesting
    deep = {"a": None}
    cur = deep
    for _ in range(64):
        cur["a"] = {"a": None}
        cur = cur["a"]
    yield "deep_nesting", json.dumps(deep).encode()

    # huge file (valid-ish base bloated with junk dictionary entries)
    huge = json.loads(base)
    for i in range(40000):
        huge["dictionary"].append({
            "id": f"pad{i}", "word": f"pad{i}", "translation": "x",
            "partOfSpeech": "adverb", "examples": [{"it": "x", "en": "y"}],
            "introducedInUnit": 1})
    yield "huge_file", json.dumps(huge).encode()

    # not json at all
    yield "not_json_xml", b"<plugin><meta/></plugin>"
    yield "not_json_empty", b""
    yield "not_json_text", b"hello this is not json at all"
    yield "not_json_bom_json", b"\xef\xbb\xbf" + b[:100]


def main():
    out = sys.argv[1]
    count = int(sys.argv[2]) if len(sys.argv) > 2 else 200
    os.makedirs(out, exist_ok=True)
    random.seed(42)
    names = []
    for name, data in mutations():
        path = os.path.join(out, f"fuzz_{name}.lingua")
        with open(path, "wb") as f:
            f.write(data)
        names.append(path)
    # fill up to count with random truncations/bitflips of the valid base
    base = json.dumps(VALID_BASE).encode()
    i = 0
    while len(names) < count:
        buf = bytearray(base)
        mode = i % 3
        if mode == 0:
            buf = buf[: random.randint(1, len(buf) - 1)]
        elif mode == 1:
            for _ in range(random.randint(1, 20)):
                pos = random.randint(0, len(buf) - 1)
                buf[pos] = random.randint(0, 255)
        else:
            insert_at = random.randint(0, len(buf))
            buf = buf[:insert_at] + os.urandom(random.randint(1, 64)) + buf[insert_at:]
        path = os.path.join(out, f"fuzz_rand_{i:04d}.lingua")
        with open(path, "wb") as f:
            f.write(bytes(buf))
        names.append(path)
        i += 1
    print(f"generated {len(names)} fuzz files in {out}")


if __name__ == "__main__":
    main()
