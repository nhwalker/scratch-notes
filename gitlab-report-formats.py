#!/usr/bin/env python3
"""Convert analyzer XML reports into the CodeClimate JSON GitLab ingests.

Companion to `gitlab-cicd-example.yml`: the `checkstyle` and `spotbugs` CI jobs call
this instead of inlining the conversion. Standard library only.

Usage:
    gitlab-report-formats.py <checkstyle|spotbugs> [output_json]

Reads the tool's reports from `**/build/reports/<tool>/*.xml` (recursive, so it works
for both single- and multi-project builds), relativizes file paths against
CI_PROJECT_DIR (the repo root), and writes a CodeClimate report (default:
gl-code-quality-report.json). Point `artifacts:reports:codequality` at that file.
"""

import glob
import hashlib
import json
import os
import sys
import xml.etree.ElementTree as ET


def checkstyle_issues(project_dir):
    """CodeClimate issues from every module's Checkstyle XML report."""
    # Checkstyle "error" severity is our default; map to GitLab severities.
    severity = {"error": "major", "warning": "minor", "info": "info", "ignore": "info"}

    issues = []
    # Recursive glob collects every module's report (and the single-project case).
    for xml_file in glob.glob("**/build/reports/checkstyle/*.xml", recursive=True):
        for file_el in ET.parse(xml_file).getroot().findall("file"):
            # <file name> is absolute; relativize to the repo root so the path
            # renders as e.g. moduleA/src/main/java/...
            path = os.path.relpath(file_el.get("name"), project_dir)
            for err in file_el.findall("error"):
                line = int(err.get("line", "1"))
                source = err.get("source", "checkstyle")
                check = source.rsplit(".", 1)[-1]
                if check.endswith("Check"):
                    check = check[:-5]
                message = err.get("message", check)
                # Path is in the fingerprint, so identical findings in different
                # modules stay distinct.
                fingerprint = hashlib.sha256(
                    f"{path}:{line}:{source}:{message}".encode()
                ).hexdigest()
                issues.append({
                    "description": message,
                    "check_name": check,
                    "fingerprint": fingerprint,
                    "severity": severity.get(err.get("severity"), "minor"),
                    "location": {"path": path, "lines": {"begin": line}},
                })
    return issues


def _spotbugs_severity(rank, category):
    try:
        rank = int(rank)
    except (TypeError, ValueError):
        rank = 20
    sev = "critical" if rank <= 4 else "major" if rank <= 9 else "minor" if rank <= 14 else "info"
    if category == "SECURITY" and sev in ("minor", "info"):
        sev = "major"  # make FindSecBugs findings stand out
    return sev


def spotbugs_issues(project_dir):
    """CodeClimate issues from every module's SpotBugs XML report."""
    issues = []
    # Recursive glob collects every module's report (and the single-project case).
    for xml_file in glob.glob("**/build/reports/spotbugs/*.xml", recursive=True):
        root = ET.parse(xml_file).getroot()
        srcdirs = [e.text for e in root.findall("./Project/SrcDir") if e.text]
        for bug in root.findall("BugInstance"):
            lines = bug.findall(".//SourceLine")
            sl = next((s for s in lines if s.get("primary") == "true" and s.get("start")), None) \
                or next((s for s in lines if s.get("start")), None)
            if sl is None:
                continue
            sourcepath = sl.get("sourcepath", "")
            # SpotBugs sourcepath is package-relative; resolve against the source
            # roots it recorded (per module), then make it repo-relative.
            path = sourcepath
            for d in srcdirs:
                cand = os.path.join(d, sourcepath)
                if os.path.exists(cand):
                    path = os.path.relpath(cand, project_dir)
                    break
            lm = bug.find("LongMessage")
            desc = lm.text if lm is not None and lm.text else bug.get("type", "SpotBugs finding")
            instance = bug.get("instanceHash") or f"{sl.get('start')}:{bug.get('type')}"
            issues.append({
                "description": desc,
                "check_name": bug.get("type"),
                "categories": [bug.get("category", "BUG")],
                # Prefix with path so identical findings in different modules differ.
                "fingerprint": f"{path}:{instance}",
                "severity": _spotbugs_severity(bug.get("rank"), bug.get("category")),
                "location": {"path": path, "lines": {"begin": int(sl.get("start", "1"))}},
            })
    return issues


CONVERTERS = {"checkstyle": checkstyle_issues, "spotbugs": spotbugs_issues}


def main(argv):
    if len(argv) < 2 or argv[1] not in CONVERTERS:
        sys.stderr.write(
            "usage: gitlab-report-formats.py <checkstyle|spotbugs> [output_json]\n"
        )
        return 2

    fmt = argv[1]
    output = argv[2] if len(argv) > 2 else "gl-code-quality-report.json"
    project_dir = os.environ.get("CI_PROJECT_DIR", os.getcwd())

    issues = CONVERTERS[fmt](project_dir)
    with open(output, "w") as out:
        json.dump(issues, out, indent=2)
    print(f"Wrote {len(issues)} {fmt} issue(s) to {output}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
