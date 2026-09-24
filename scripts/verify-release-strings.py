#!/usr/bin/env python3
"""Fail when the release APK lost announcement strings to resource shrinking.

Guidance speaks every prompt from an `event_*` string resource. If one is missing from the
release build the user hears the raw key read aloud instead -- which is what happened when the
strings were looked up by name at runtime and the shrinker, unable to see that, removed them.
Unit tests cannot see this: it only exists in the shrunk release APK. Run after
`:app:assembleRelease`.

Usage: verify-release-strings.py [path/to/release.apk]
(default: this repository's app/build/outputs/apk/release/*.apk; a distribution that consumes
this repository as a submodule passes its own APK.)
"""
from __future__ import annotations

import glob
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# The strings the app speaks announcements with: exactly the ones SceneEventStrings maps.
MAPPING = ROOT / "sailens-shell/src/main/java/com/sailens/shell/device/SceneEventStrings.kt"


def find_aapt2() -> str:
    on_path = shutil.which("aapt2")
    if on_path:
        return on_path
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        local = ROOT / "local.properties"
        if local.is_file():
            match = re.search(r"^sdk\.dir=(.+)$", local.read_text(encoding="utf-8"), re.MULTILINE)
            if match:
                sdk = match.group(1).strip().replace("\\:", ":").replace("\\\\", "\\")
    if not sdk:
        sys.exit("verify-release-strings: set ANDROID_HOME or put aapt2 on PATH")
    candidates = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "aapt2*")))
    if not candidates:
        sys.exit(f"verify-release-strings: no aapt2 under {sdk}/build-tools")
    return candidates[-1]


def main() -> int:
    apks = sys.argv[1:] or sorted(glob.glob(str(ROOT / "app/build/outputs/apk/release/*.apk")))
    if not apks:
        sys.exit("verify-release-strings: no release APK; run :app:assembleRelease first")
    wanted = set(re.findall(r"R\.string\.([a-z_]+)", MAPPING.read_text(encoding="utf-8")))
    if not wanted:
        sys.exit(f"verify-release-strings: found no R.string references in {MAPPING}")
    dump = subprocess.run(
        [find_aapt2(), "dump", "resources", apks[-1]],
        check=True, capture_output=True, text=True, encoding="utf-8", errors="replace",
    ).stdout
    present = set(re.findall(r"string/(event_[a-z_]+)\b", dump))
    missing = sorted(wanted - present)
    if missing:
        print(f"Release APK {apks[-1]} is missing {len(missing)} of {len(wanted)} announcement strings:",
              file=sys.stderr)
        for name in missing:
            print(f"  - {name}", file=sys.stderr)
        return 1
    print(f"Release APK keeps all {len(wanted)} announcement strings.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
