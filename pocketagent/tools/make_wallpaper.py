#!/usr/bin/env python3
"""Compatibility entry point for the shared PocketAgent SVG brand generator.

The wallpaper uses the same brackets + spark paths as the launcher.
Requires Node.js, sharp and ImageMagick; see branding/README.md.
"""
from pathlib import Path
import subprocess

if __name__ == "__main__":
    subprocess.run(["node", str(Path(__file__).resolve().with_name("make_brand_icons.mjs"))], check=True)
