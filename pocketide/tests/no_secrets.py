#!/usr/bin/env python3
"""Nothing that looks like a credential may be in the source, the assets or the resources."""
import os, re, sys

app = sys.argv[1]
patterns = [
    (r'sk-[A-Za-z0-9]{20,}', "an OpenAI-style key"),
    (r'sk-ant-[A-Za-z0-9\-]{20,}', "an Anthropic-style key"),
    (r'AIza[0-9A-Za-z\-_]{30,}', "a Google API key"),
    (r'ghp_[A-Za-z0-9]{30,}', "a GitHub token"),
    (r'-----BEGIN [A-Z ]*PRIVATE KEY-----', "a private key"),
    (r'(?i)\b(api[_-]?key|secret|token)\s*=\s*["\'][A-Za-z0-9/+_\-]{24,}["\']', "an assigned secret"),
]
problems = []
for root, dirs, files in os.walk(app):
    dirs[:] = [d for d in dirs if d not in ("build", ".git", ".signing", "__pycache__")]
    for name in files:
        if name.endswith((".png", ".jpg", ".so", ".jks", ".apk")):
            continue
        path = os.path.join(root, name)
        try:
            text = open(path, encoding="utf-8", errors="ignore").read()
        except OSError:
            continue
        for pattern, what in patterns:
            for found in re.finditer(pattern, text):
                problems.append(f"{os.path.relpath(path, app)}: {what}")
                break

for problem in sorted(set(problems)):
    print(f"  {problem}", file=sys.stderr)
sys.exit(1 if problems else 0)
