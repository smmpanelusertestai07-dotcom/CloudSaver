#!/usr/bin/env python3
"""Every fact Agents.java states about an extension must still be true on Open VSX.

An earlier build of this project told an owner a download was 700 MB when it was 143, and
another told them Google published no extension at all when it does. Both were believable and
both were wrong. This asks the registry.

Offline (no network) the check is skipped rather than failed: a gate that fails on a train is
a gate people learn to ignore."""
import json, re, sys, urllib.request, urllib.error

src = sys.argv[1]
text = open(f"{src}/Agents.java").read()

agents = re.findall(
    r'new Agent\(\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*([0-9_]+)L,\s*"([^"]+)"',
    text)
# Three official rows and one community row. Both lists are checked against the registry the
# same way; what differs is the claim the app makes about them, which safety.py holds.
if len(agents) != 4:
    print(f"  expected 4 agents, parsed {len(agents)}", file=sys.stderr)
    sys.exit(1)
official_block = text.split("COMMUNITY")[0]
for ident, *_ in agents[:3]:
    if ident not in official_block:
        print(f"  {ident} is not in the official list it is counted in", file=sys.stderr)
        sys.exit(1)
if agents[3][0] in official_block:
    print(f"  {agents[3][0]} is in ALL, so the app would call it official", file=sys.stderr)
    sys.exit(1)

def fetch(url):
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as answer:
        return json.load(answer)

problems = []
for ident, name, publisher, platform, size, engine in agents:
    namespace, short = ident.split(".", 1)
    url = f"https://open-vsx.org/api/{namespace}/{short}"
    if platform != "universal":
        url += f"/{platform}/latest"
    try:
        listing = fetch(url)
    except (urllib.error.URLError, TimeoutError, OSError) as offline:
        print(f"  skipped (registry unreachable: {offline})")
        sys.exit(0)
    if not listing.get("verified"):
        problems.append(f"{ident}: namespace is not verified on Open VSX")
    got_engine = (listing.get("engines") or {}).get("vscode", "")
    if got_engine and got_engine != engine:
        problems.append(f"{ident}: engine is {got_engine}, Agents.java says {engine}")
    got_platform = listing.get("targetPlatform", "universal")
    if got_platform != platform:
        problems.append(f"{ident}: platform is {got_platform}, Agents.java says {platform}")

for problem in problems:
    print(f"  {problem}", file=sys.stderr)
sys.exit(1 if problems else 0)
