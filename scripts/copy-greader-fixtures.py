#!/usr/bin/env python3
"""Copies reference Google Reader API answers from a freshgo checkout into the unit test resources.

The answers were recorded from FreshRSS 1.30.1 and are what freshgo is tested against
(freshgo testdata/reference/api). Usage: scripts/copy-greader-fixtures.py <path to freshgo>
"""
import json
import pathlib
import re
import sys

CASES = [
    "login",
    "login wrong password",
    "token",
    "user info",
    "tag list",
    "subscription list",
    "unread count",
    "reading list first page",
    "reading list continuation past the end",
    "ids first page",
    "ids third page",
    "items in three forms",
]

def main() -> None:
    freshgo = pathlib.Path(sys.argv[1])
    reference = freshgo / "testdata" / "reference" / "api"
    responses = {r["name"]: r for r in json.loads((reference / "responses.json").read_text())}
    out = pathlib.Path(__file__).resolve().parent.parent / "app" / "src" / "test" / "resources" / "greader"
    out.mkdir(parents=True, exist_ok=True)
    for name in CASES:
        body = responses[name].get("body", "")
        if not isinstance(body, str):
            body = json.dumps(body)
        (out / (re.sub(r"[^a-z0-9]+", "-", name) + ".txt")).write_text(body)
    print(f"{len(CASES)} answers written to {out}")

if __name__ == "__main__":
    main()
