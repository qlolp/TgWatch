#!/usr/bin/env python3
"""Run regressions and expose bounded, escaped failures in GitHub checks."""
import argparse
import sys
import unittest


def annotation(message):
    escaped = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print("::error title=Python regression failed::" + escaped, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", nargs="?", default="scripts/tests")
    args = parser.parse_args()
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.discover(args.directory))
    if not result.testsRun:
        annotation("No Python regression tests were discovered")
        return 1
    for test, trace in result.failures + result.errors:
        annotation(str(test) + "\n" + trace[-4096:])
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(main())
