"""Locks the semantics of the build's "-dirty" check (app/build.gradle.kts, buildGitSha).

The APK records the commit it was built from and appends "-dirty" when `git status --porcelain`
prints anything. This test runs that exact command in a scratch repository that uses this repo's
real .gitignore, and checks that the build script still uses it:

- a modified tracked file         -> dirty
- a new, untracked source file    -> dirty (Gradle compiles it too)
- a model under ignored assets/   -> clean (weights are brought, never committed)
- a clean checkout                -> clean

    python -m unittest discover -s scripts -p "test_build_identity.py"
"""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIRTY_COMMAND = ["git", "status", "--porcelain"]


def git(repo, *args):
    return subprocess.run(
        ["git", "-c", "user.name=test", "-c", "user.email=test@example.invalid", *args],
        cwd=repo, check=True, capture_output=True, text=True,
    ).stdout


def dirty(repo):
    return bool(subprocess.run(DIRTY_COMMAND, cwd=repo, check=True, capture_output=True, text=True).stdout.strip())


class BuildIdentityTest(unittest.TestCase):
    def setUp(self):
        self.repo = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.repo, ignore_errors=True)
        git(self.repo, "init", "-q")
        shutil.copy(os.path.join(REPO, ".gitignore"), os.path.join(self.repo, ".gitignore"))
        self.source = os.path.join(self.repo, "app", "src", "main", "java", "Foo.kt")
        os.makedirs(os.path.dirname(self.source))
        os.makedirs(os.path.join(self.repo, "app", "src", "main", "assets"))
        with open(self.source, "w") as f:
            f.write("class Foo\n")
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-q", "-m", "baseline")

    def write(self, *parts, content="x"):
        path = os.path.join(self.repo, *parts)
        with open(path, "w") as f:
            f.write(content)

    def test_a_clean_checkout_is_clean(self):
        self.assertFalse(dirty(self.repo))

    def test_a_modified_tracked_file_is_dirty(self):
        self.write("app", "src", "main", "java", "Foo.kt", content="class Foo { val changed = 1 }\n")
        self.assertTrue(dirty(self.repo))

    def test_an_untracked_source_file_is_dirty(self):
        self.write("app", "src", "main", "java", "Bar.kt", content="class Bar\n")
        self.assertTrue(dirty(self.repo))

    def test_model_weights_in_the_ignored_assets_are_not_dirty(self):
        self.write("app", "src", "main", "assets", "sem.tflite", content="weights")
        self.write("app", "src", "main", "assets", "det.tflite", content="weights")
        self.assertFalse(dirty(self.repo))

    def test_the_build_script_uses_this_command(self):
        with open(os.path.join(REPO, "app", "build.gradle.kts"), encoding="utf-8") as f:
            script = f.read()
        self.assertIn('commandLine("git", "status", "--porcelain")', script)
        self.assertNotIn("--untracked-files", script)


if __name__ == "__main__":
    unittest.main()
