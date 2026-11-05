#!/usr/bin/env python3
"""Checks the JUnit XML of a Gradle unit-test run against the test sources.

Two modes:

    green --results DIR --source-root DIR --tests "fqcn ..."
        Every listed class ran exactly the @Test methods its source declares, none skipped.

    red --results DIR --class FQCN --method NAME
        The named test is the only one run, and it failed on a Truth assertion rather than on a
        timeout or a setup error.
"""

import argparse
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

# The message of the assertion the mutation proof watches; the red run must fail on exactly this.
MARKER = "FULL_TEXT_TOGGLE_OFF_MUST_SURVIVE_STORE_COMPLETION"


def fail(message):
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def test_methods(source_root, fqcn):
    """The @Test methods declared by the source of [fqcn], in source order."""
    path = source_root.joinpath(*fqcn.split(".")).with_suffix(".kt")
    if not path.is_file():
        fail(f"no source file for {fqcn} at {path}")

    names = []
    pending = False
    ignored = False
    fun = re.compile(r"fun\s+(?:`([^`]+)`|(\w+))\s*\(")
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("//"):
            continue
        if pending:
            if line.startswith("@Ignore"):
                ignored = True
                continue
            match = fun.match(line)
            if match:
                if not ignored:
                    names.append(match.group(1) or match.group(2))
                pending = False
                ignored = False
                continue
            if line.startswith("@"):
                continue
            # A declaration of a nested class or a property ends the search for this annotation.
            pending = False
            ignored = False
            continue
        if line.startswith("@Ignore"):
            # An @Ignore before @Test marks the method as not run; keep looking for its fun.
            pending = True
            ignored = True
            continue
        if line.startswith("@Test"):
            pending = True
            ignored = False
            rest = line[len("@Test"):].strip()
            match = fun.match(rest)
            if match:
                names.append(match.group(1) or match.group(2))
                pending = False
    if not names:
        fail(f"no @Test methods found in {path}")
    return names


def suite_of(results, fqcn):
    path = results / f"TEST-{fqcn}.xml"
    if not path.is_file():
        fail(f"no test results for {fqcn} at {path}")
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as error:
        fail(f"cannot read {path}: {error}")
    suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
    if len(suites) != 1:
        fail(f"{path} holds {len(suites)} test suites, expected one")
    suite = suites[0]
    if suite.get("name") != fqcn:
        fail(f"{path} names the suite {suite.get('name')!r}, expected {fqcn!r}")
    return suite


def count(suite, name):
    value = suite.get(name, "0")
    if not value.isdigit():
        fail(f"the suite of {suite.get('name')} has a non-numeric {name}={value!r}")
    return int(value)


def check_green(args):
    filters = args.tests.split()
    if not filters:
        fail("--tests lists no class")
    total = 0
    for fqcn in filters:
        expected = test_methods(args.source_root, fqcn)
        suite = suite_of(args.results, fqcn)
        cases = suite.findall("testcase")
        tests = count(suite, "tests")
        if tests != len(cases):
            fail(f"{fqcn}: the suite says {tests} tests but holds {len(cases)} cases")
        if tests == 0:
            fail(f"{fqcn}: no tests ran")
        if count(suite, "skipped") != 0 or any(case.find("skipped") is not None for case in cases):
            fail(f"{fqcn}: a test was skipped")
        if count(suite, "failures") != 0 or count(suite, "errors") != 0:
            fail(f"{fqcn}: {count(suite, 'failures')} failures and {count(suite, 'errors')} errors")
        ran = [case.get("name") for case in cases]
        for case in cases:
            if case.get("classname") != fqcn:
                fail(f"{fqcn}: the case {case.get('name')!r} belongs to {case.get('classname')!r}")
        missing = [name for name in expected if name not in ran]
        extra = [name for name in ran if name not in expected]
        if missing or extra:
            fail(f"{fqcn}: missing {missing}, unexpected {extra}")
        if len(ran) != len(set(ran)):
            fail(f"{fqcn}: a test appears more than once")
        total += tests
        print(f"{fqcn}: {tests} tests, 0 skipped, 0 failures, 0 errors")
    print(f"checked {len(filters)} classes, {total} tests total")


def check_red(args):
    suite = suite_of(args.results, args.klass)
    cases = suite.findall("testcase")
    if count(suite, "tests") != 1 or len(cases) != 1:
        fail(f"the run held {len(cases)} tests, expected only {args.klass}.{args.method}")
    if count(suite, "skipped") != 0:
        fail("the test was skipped")
    if count(suite, "errors") != 0:
        fail("the test errored instead of failing an assertion")
    if count(suite, "failures") != 1:
        fail("the test did not fail")
    case = cases[0]
    if case.get("classname") != args.klass or case.get("name") != args.method:
        fail(f"the failing test is {case.get('classname')}.{case.get('name')}, not the intended one")
    failure = case.find("failure")
    if failure is None:
        fail("the test has no failure element")
    if failure.get("type") != "java.lang.AssertionError":
        fail(f"the failure type is {failure.get('type')!r}, not java.lang.AssertionError")
    text = " ".join(filter(None, [failure.get("message"), failure.text]))
    if MARKER not in text:
        fail(f"the failure does not carry the message {MARKER}")
    print(f"{args.klass}.{args.method}: failed on the intended assertion")
    print(" ".join(text.split())[:300])


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="mode", required=True)

    green = sub.add_parser("green")
    green.add_argument("--results", type=pathlib.Path, required=True)
    green.add_argument("--source-root", type=pathlib.Path, required=True)
    green.add_argument("--tests", required=True)
    green.set_defaults(run=check_green)

    red = sub.add_parser("red")
    red.add_argument("--results", type=pathlib.Path, required=True)
    red.add_argument("--class", dest="klass", required=True)
    red.add_argument("--method", required=True)
    red.set_defaults(run=check_red)

    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
