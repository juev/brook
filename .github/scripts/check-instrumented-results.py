#!/usr/bin/env python3
"""Checks Android instrumentation XML without assuming how AGP laid it out.

AGP may write one TEST-<class>.xml per class or one TEST-<device>.xml per run, so this reads every
TEST-*.xml below --results and groups the test cases by their classname.

    python3 -I .github/scripts/check-instrumented-results.py --results DIR --tests "fqcn ..."

Every listed class must have at least one case, none skipped, none failed or errored. A class that
ran but was not listed fails the check too, so a compact pass that also ran the large-window class
cannot pass.
"""

import argparse
import pathlib
import sys
import xml.etree.ElementTree as ET


def fail(message):
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", type=pathlib.Path, required=True)
    parser.add_argument("--tests", required=True)
    args = parser.parse_args()

    expected = set(args.tests.split())
    if not expected:
        fail("--tests lists no class")
    if not args.results.is_dir():
        fail(f"no results directory at {args.results}")

    cases = {}
    files = 0
    for path in sorted(args.results.rglob("TEST-*.xml")):
        files += 1
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            fail(f"cannot read {path}: {error}")
        for case in root.iter("testcase"):
            name = case.get("classname")
            if not name:
                fail(f"{path}: a test case has no classname")
            cases.setdefault(name, []).append(case)

    if files == 0:
        fail(f"no TEST-*.xml under {args.results}")

    ran_classes = set(cases)
    missing = expected - ran_classes
    if missing:
        fail(f"classes that did not run: {sorted(missing)}")
    unexpected = ran_classes - expected
    if unexpected:
        fail(f"classes that should not have run: {sorted(unexpected)}")

    total = 0
    for name in sorted(expected):
        class_cases = cases[name]
        if not class_cases:
            fail(f"{name}: no test cases")
        if any(case.find("skipped") is not None for case in class_cases):
            fail(f"{name}: a test was skipped")
        if any(case.find("failure") is not None or case.find("error") is not None for case in class_cases):
            fail(f"{name}: a test failed or errored")
        total += len(class_cases)
        print(f"{name}: {len(class_cases)} tests, 0 skipped, 0 failures, 0 errors")

    print(f"checked {len(expected)} classes, {total} tests from {files} XML file(s)")


if __name__ == "__main__":
    main()
