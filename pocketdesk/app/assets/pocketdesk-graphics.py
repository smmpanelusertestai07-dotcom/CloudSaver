#!/usr/bin/env python3
"""Check the dlopen-only graphics libraries an Electron app needs, without starting one."""
import ctypes
import json
import os
from pathlib import Path
import sys
import sysconfig


LIBRARIES = (
    "libEGL.so.1", "libEGL_mesa.so.0", "libGL.so.1",
    "libGLX_mesa.so.0", "libGLESv2.so.2",
)


def check_runtime(root=Path("/"), loader=ctypes.CDLL, multiarch=None):
    """Actually resolve libraries: dpkg state and ldd miss optional dlopen calls.

    Loading does not initialize a display. In particular this can run from the Apps tab before
    the private X server exists. The software DRI driver and Mesa vendor file are also required
    so finding just the GLVND dispatch library cannot incorrectly mark graphics ready.
    """
    errors = []
    handles = []  # Keep the libraries loaded until all dependency checks finish.
    for soname in LIBRARIES:
        try:
            handles.append(loader(soname, mode=os.RTLD_NOW | os.RTLD_LOCAL))
        except OSError as error:
            errors.append(f"cannot load {soname}: {error}")
    vendor = root / "usr/share/glvnd/egl_vendor.d/50_mesa.json"
    try:
        metadata = json.loads(vendor.read_text())
        library = metadata["ICD"]["library_path"]
        if not isinstance(library, str) or not library:
            raise ValueError("missing ICD library_path")
        handles.append(loader(library, mode=os.RTLD_NOW | os.RTLD_LOCAL))
    except (OSError, ValueError, KeyError, TypeError) as error:
        errors.append(f"Mesa EGL vendor configuration is not usable: {error}")
    multiarch = multiarch or sysconfig.get_config_var("MULTIARCH")
    if not multiarch:
        errors.append("Python could not determine the native graphics library directory")
    else:
        driver = root / "usr/lib" / multiarch / "dri/swrast_dri.so"
        try:
            # Resolve the driver's own dependencies too, e.g. LLVM after interrupted updates.
            handles.append(loader(str(driver), mode=os.RTLD_NOW | os.RTLD_LOCAL))
        except OSError as error:
            errors.append(f"Mesa software rendering driver is not usable: {error}")
    return errors


if __name__ == "__main__":
    failures = check_runtime()
    for failure in failures:
        print(f"PocketLinux: graphics dependency: {failure}", flush=True)
    sys.exit(1 if failures else 0)
