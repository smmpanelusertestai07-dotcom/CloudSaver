#!/usr/bin/env python3
"""Only an agent whose publisher states a free tier may be marked free.

An earlier build of this project described Codex as free. It is not, and telling someone a paid
tool is free is a way of wasting their afternoon."""
import re, sys

src = sys.argv[1]
text = open(f"{src}/Agents.java").read()
agents = re.findall(
    r'new Agent\(\s*"([^"]+)",[^)]*?"([^"]*)",\s*(true|false),', text, re.S)

# Verified from each publisher's own words on 13 September 2026.
publishes_free_tier = {"Google.google-antigravity"}

problems = []
for ident, plan, free in agents:
    claimed = free == "true"
    truly = ident in publishes_free_tier
    if claimed and not truly:
        problems.append(f"{ident} is marked free but its publisher does not state a free tier")
    if truly and not claimed:
        problems.append(f"{ident} has a free tier but is not marked free")
    if claimed and "free" not in plan.lower():
        problems.append(f"{ident} is marked free but its plan text does not say so")

for problem in problems:
    print(f"  {problem}", file=sys.stderr)
sys.exit(1 if problems else 0)
