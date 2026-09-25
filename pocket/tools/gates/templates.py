#!/usr/bin/env python3
"""The build templates the app adds to the owner's projects (assets/templates/*.yml).

A template runs in someone's own GitHub account and spends their Actions minutes, so each one:
  - runs only when asked (workflow_dispatch and nothing else: 2.6.0's ran on every push);
  - reads, never writes: top-level `permissions: contents: read`, and no write grant anywhere;
  - pins every action to a full commit SHA, so a moved tag cannot change what runs;
  - keeps Secrets away from the repository's code: a job that reads a Secret never checks the
    repository out, because anything the build runs in a job (gradlew, build scripts) can change
    what that job's later steps run, and so read the Secrets they are given.
"""
from __future__ import annotations

import sys
from pathlib import Path

import actions_rules
import common
import yaml_lite


def template_dir(root: Path) -> Path:
    return common.assets(root) / "templates"


def checks_out(job: dict) -> bool:
    steps = job.get("steps") or []
    return any(isinstance(step, dict) and str(step.get("uses", "")).startswith("actions/checkout@") for step in steps)


def check_template(name: str, text: str) -> list[str]:
    problems = []
    try:
        workflow = yaml_lite.load(text)
    except yaml_lite.YamlError as error:
        return [f"{name}: cannot be read: {error}"]
    if not isinstance(workflow, dict):
        return [f"{name}: is not a workflow"]

    triggers = workflow.get("on")
    names = set(triggers) if isinstance(triggers, (dict, list)) else {triggers}
    if names != {"workflow_dispatch"}:
        shown = ", ".join(sorted(str(n) for n in names)) or "nothing"
        problems.append(f"{name}: runs on {shown}; a template must run on workflow_dispatch only")

    top = workflow.get("permissions")
    if not (isinstance(top, dict) and str(top.get("contents")) == "read"):
        problems.append(f"{name}: needs top-level 'permissions: contents: read'")
    grants = actions_rules.write_grants(top)
    for job_name, job in (workflow.get("jobs") or {}).items():
        if isinstance(job, dict):
            grants += [f"{s} (job {job_name})" for s in actions_rules.write_grants(job.get("permissions"))]
    for grant in grants:
        problems.append(f"{name}: grants write access to {grant}; templates only read")

    for job_name, job in (workflow.get("jobs") or {}).items():
        if isinstance(job, dict) and "secrets." in str(job) and checks_out(job):
            problems.append(f"{name}: job {job_name} reads Secrets and checks out the repository, whose code could "
                            "take them; use the Secrets in a job of their own, without a checkout")

    for line, reference, _comment in actions_rules.uses_lines(text):
        why = actions_rules.pin_problem(reference)
        if why:
            problems.append(f"{name}:{line}: {reference} {why}")
    return problems


def check(root: Path = common.POCKET) -> common.Report:
    report = common.Report()
    files = sorted(template_dir(root).glob("*.yml")) + sorted(template_dir(root).glob("*.yaml"))
    if not files:
        report.fail("no build templates in app/src/main/assets/templates/*.yml yet: the builds module ships "
                    "them (plan §10, §13); this gate checks each one when it lands")
        return report
    for path in files:
        for problem in check_template(f"templates/{path.name}", path.read_text(encoding="utf-8")):
            report.fail(problem)
    return report


if __name__ == "__main__":
    sys.exit(common.run_standalone("build templates", check))
