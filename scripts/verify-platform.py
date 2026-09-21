#!/usr/bin/env python3
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_MODULES = {
    ":app",
    ":sailens-camera",
    ":sailens-core",
    ":sailens-describe",
    ":sailens-guidance",
    ":sailens-output",
    ":sailens-runtime",
    ":sailens-shell",
    ":sailens-vision",
    ":sailens-vlm",
}


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def require_pattern(errors: list[str], text: str, pattern: str, message: str) -> None:
    if re.search(pattern, text, re.MULTILINE | re.DOTALL) is None:
        errors.append(message)


def main() -> int:
    errors: list[str] = []

    tracked_models = [line for line in git("ls-files", "--", "*.tflite").splitlines() if line]
    if tracked_models:
        errors.append(
            "Sailens Android must remain model-neutral; tracked .tflite files found: "
            + ", ".join(tracked_models)
        )

    settings = read("settings.gradle.kts")
    declared_modules: set[str] = set()
    for line in settings.splitlines():
        match = re.fullmatch(r'\s*include\("(:[A-Za-z0-9-]+)"\)\s*', line)
        if match:
            declared_modules.add(match.group(1))

    if declared_modules != EXPECTED_MODULES:
        missing = sorted(EXPECTED_MODULES - declared_modules)
        extra = sorted(declared_modules - EXPECTED_MODULES)
        errors.append(
            "Gradle module contract drifted"
            f" (missing={missing or 'none'}, extra={extra or 'none'})."
        )

    for module in sorted(EXPECTED_MODULES):
        module_dir = module.removeprefix(":")
        if not (ROOT / module_dir / "build.gradle.kts").is_file():
            errors.append(f"{module} is declared but {module_dir}/build.gradle.kts is missing.")

    gradle_properties = read("gradle.properties")
    require_pattern(
        errors,
        gradle_properties,
        r"^sailens\.enableLitertNpuRuntime=false\s*$",
        "CI/default builds must keep sailens.enableLitertNpuRuntime=false.",
    )

    app_gradle = read("app/build.gradle.kts")
    require_pattern(
        errors,
        app_gradle,
        r'applicationId\s*=\s*"com\.sailens\.reference"',
        "Reference host applicationId must remain com.sailens.reference.",
    )
    require_pattern(
        errors,
        app_gradle,
        r'buildConfigField\(\s*"String"\s*,\s*"APP_LICENSE"\s*,\s*"\\"Apache-2\.0\\""\s*\)',
        "Reference host APP_LICENSE must remain Apache-2.0.",
    )
    require_pattern(
        errors,
        app_gradle,
        r'buildConfigField\(\s*"String"\s*,\s*"APP_SOURCE_URL"\s*,\s*'
        r'"\\"https://github\.com/wnbotoo/sailens-android\\""\s*\)',
        "Reference host APP_SOURCE_URL must point to sailens-android.",
    )

    edition = read("app/src/main/java/com/sailens/app/SailensEdition.kt")
    require_pattern(
        errors,
        edition,
        r"guidanceRequired\s*=\s*false",
        "Reference host must not declare Guidance required.",
    )
    require_pattern(
        errors,
        edition,
        r"describeRequired\s*=\s*false",
        "Reference host must not declare Describe required.",
    )

    if errors:
        print("Sailens Android platform contract verification FAILED:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    print("Sailens Android platform contract verification passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
