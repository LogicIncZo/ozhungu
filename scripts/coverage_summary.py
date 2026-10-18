#!/usr/bin/env python3
"""Prints a compact line-coverage table from the AGP JaCoCo unit-test report."""
import glob
import sys
import xml.etree.ElementTree as ET

REPORTS = glob.glob(
    "app/build/reports/coverage/test/debug/report.xml"
) + glob.glob("app/build/reports/coverage/androidTest/debug/report.xml")

if not REPORTS:
    print("no coverage report found — run: make coverage")
    sys.exit(1)


def line_counter(el):
    """Class/report totals are direct <counter> children (JaCoCo XML shape).

    cls.iter("counter") would return the FIRST METHOD's counter instead.
    """
    return next((c for c in el if c.tag == "counter" and c.get("type") == "LINE"), None)


agg = {}
total = (0, 0)
for path in REPORTS:
    root = ET.parse(path).getroot()
    top = line_counter(root)
    if top is not None:
        total = (total[0] + int(top.get("covered")), total[1] + int(top.get("missed")))
    for pkg in root.iter("package"):
        for cls in pkg.iter("class"):
            name = cls.get("name", "?").split("/")[-1]
            lines = line_counter(cls)
            if lines is None:
                continue
            missed, covered = int(lines.get("missed")), int(lines.get("covered"))
            m, c = agg.get(name, (0, 0))
            agg[name] = (m + missed, c + covered)

rows = sorted(((m, c, n) for n, (m, c) in agg.items()), key=lambda r: (-(r[0] + r[1]), r[2]))
for missed, covered, name in rows:
    t = missed + covered
    pct = 100.0 * covered / t if t else 0.0
    print(f"{name:38s} {covered:4d}/{t:<4d} {pct:5.1f}%")
covered, missed = total
grand = covered + missed
pct = 100.0 * covered / grand if grand else 0.0
print("-" * 55)
print(f"{'TOTAL':38s} {covered:4d}/{grand:<4d} {pct:5.1f}%")
