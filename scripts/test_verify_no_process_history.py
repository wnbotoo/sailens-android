"""Tests for verify-no-process-history.py.

Run from the repository root:
    python -m unittest discover -s scripts -p "test_verify_no_process_history.py"
"""
from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location(
    "verify_no_process_history", Path(__file__).resolve().parent / "verify-no-process-history.py"
)
check = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = check  # dataclasses look their module up while the class is built
_SPEC.loader.exec_module(check)


class RepoTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.files: list[str] = []
        self.write("docs/models.md", "# Models\n\n## 1. Contract\n\n### 1.2 Layout\n")
        self.write("docs/models.zh-CN.md", "# 模型\n\n## 1. 契约\n")

    def tearDown(self):
        self._tmp.cleanup()

    def write(self, path: str, text: str) -> None:
        full = self.root / path
        full.parent.mkdir(parents=True, exist_ok=True)
        full.write_text(text, encoding="utf-8")
        if path not in self.files:
            self.files.append(path)

    def findings(self, allowed=frozenset({"models"})):
        return check.scan(self.root, self.files, allowed)

    def rules(self, path: str) -> list[str]:
        return [f.rule for f in self.findings() if f.path == path]


class MarkerRulesTest(RepoTest):
    def test_each_kind_of_process_history_in_a_comment_is_found(self):
        cases = {
            "milestone id": "// the M3a ground contact",
            "plan id": "// as det plan item D3a-1 says",
            "plan name": "// det-first measurement",
            "PR number": "// added in PR #12",
            "review round": "// Review round 2: the fallback",
            "review finding id": "// P1-2: a description holds the floor",
            "date": "// decided 2026-09-25",
        }
        for rule, comment in cases.items():
            with self.subTest(rule=rule):
                self.write(f"src/{rule.replace(' ', '_')}.kt", f"package a\n\n{comment}\nval x = 1\n")
                self.assertEqual([rule], self.rules(f"src/{rule.replace(' ', '_')}.kt"))

    def test_chinese_process_history_is_found(self):
        self.write("src/A.kt", "// （det 优先方案 D3a-1）\n// 第 3 轮评审\n")
        self.assertEqual(2, len(self.rules("src/A.kt")))

    def test_identifiers_strings_and_device_names_are_not(self):
        self.write("src/A.kt", "\n".join([
            "// Measured on SM8850 and SM8450; see architecture.md §6.1 and models.md §1.2.",
            'val height = System.getProperty("sailens.m3a.phoneHeight")',
            'val report = "M3a off: kept ${count}, det-first PR-0 baseline 2026-10-11 // not a comment"',
            "val sets = D18Sets.CALIBRATION",
            "fun `every M2a scenario resolves`() = Unit",
            'val golden = "tilt.m3a.golden.txt"',
        ]))
        self.write("docs/architecture.md", "## 6. Decisions\n\n### 6.1 Frames\n")
        self.write("docs/architecture.zh-CN.md", "## 6. 决定\n")
        self.assertEqual([], self.rules("src/A.kt"))

    def test_kotlin_block_comments_nest_and_templates_hold_strings(self):
        self.write("src/A.kt", "\n".join([
            "/* outer /* inner */ still the comment: M0b */",
            'val s = "${if (on) "ON" else "off"} // M3a in a template string"',
            'val raw = """',
            "  // M3o inside a raw string",
            '"""',
            "val t = 1 // review finding on #25",
            "fun `the simulator's \"off\" baseline`() = Unit // P2-4: after a backticked name",
        ]))
        self.assertEqual([(1, "milestone id"), (6, "review round"), (7, "review finding id")],
                         [(f.line, f.rule) for f in self.findings() if f.path == "src/A.kt"])

    def test_cpp_digit_separators_and_raw_strings(self):
        self.write("src/a.cpp", "\n".join([
            "int n = 1'000; /* M3a */",
            "int h = 0xFF'FF; // P1-2",
            "char c = '/'; // D5",
            'auto s = R"(// M3a inside a raw string)";',
            'auto t = u8R"x(text // M3o )" still raw)x"; // PR #9',
            "auto u = LR\"(\n// M0b in a multi-line raw string\n)\";",
        ]))
        self.assertEqual([(1, "milestone id"), (2, "review finding id"), (3, "plan id"), (5, "PR number")],
                         [(f.line, f.rule) for f in self.findings() if f.path == "src/a.cpp"])

    def test_java_text_blocks_are_strings(self):
        self.write("src/A.java", "\n".join([
            'String s = """',
            "    // M3a inside a text block, \\\"\"\" escaped quotes too",
            '    """; // P1-2',
        ]))
        self.assertEqual([(3, "review finding id")],
                         [(f.line, f.rule) for f in self.findings() if f.path == "src/A.java"])

    def test_ids_next_to_chinese_and_ids_not_used_yet_are_found(self):
        self.write("src/A.kt", "\n".join([
            "// 按M3a方案处理",
            "// M4a: next milestone",
            "// 于2026-10-11 决定",
            "// 见D7.2",
        ]))
        self.assertEqual(["milestone id", "milestone id", "date", "plan id"], self.rules("src/A.kt"))

    def test_a_model_name_is_not_review_history(self):
        self.write("src/A.kt", "// The VLM can be backed by GPT.\n// A 0xD5 mask, 3D boxes, an M-estimator.\n"
                               "// GPT review asked for this.\n// 第 2 轮评审\n")
        self.assertEqual([3, 4], [f.line for f in self.findings() if f.path == "src/A.kt"])

    def test_a_multi_line_kdoc_reports_the_right_line(self):
        self.write("src/A.kt", "/**\n * Fine.\n * Then M3a.\n */\nclass A\n")
        self.assertEqual([3], [f.line for f in self.findings() if f.path == "src/A.kt"])

    def test_python_comments_and_docstrings_are_checked_but_strings_are_not(self):
        self.write("tools/a.py", '"""Reads field captures (M0a)."""\n\nx = "det-first D1.8"  # fine\n# PR #9\n')
        self.assertEqual([1, 4], [f.line for f in self.findings() if f.path == "tools/a.py"])

    def test_xml_comments_are_checked_but_text_is_not(self):
        self.write("res/strings.xml", '<resources>\n  <!-- M0a field capture -->\n'
                                      '  <string name="a">M3a</string>\n</resources>\n')
        self.assertEqual([2], [f.line for f in self.findings() if f.path == "res/strings.xml"])

    def test_hash_comments_in_build_files_are_checked(self):
        self.write("CMakeLists.txt", "# added for M3b\nset(A \"#1\")\n")
        self.write("app/proguard-rules.pro", "-keep class a.B # kept since 2026-10-09\n")
        self.assertEqual(["milestone id"], self.rules("CMakeLists.txt"))
        self.assertEqual(["date"], self.rules("app/proguard-rules.pro"))

    def test_markdown_is_checked_in_full_but_list_numbers_are_not_pr_numbers(self):
        self.write("README.md", "Same as #1 above.\n\nLanded in milestone M4.\n")
        self.assertEqual([3], [f.line for f in self.findings() if f.path == "README.md"])

    def test_dated_file_names_are_not_dates(self):
        self.write("README.md", "See [the probe](docs/experiments/geometry-probe-2026-09.md) and "
                                "results/summary-2026-09-24.txt.\n")
        self.write("docs/experiments/geometry-probe-2026-09.md", "x\n")
        self.write("docs/experiments/geometry-probe-2026-09.zh-CN.md", "x\n")
        self.assertEqual([], self.rules("README.md"))

    def test_exempt_paths_skip_the_marker_rules_only(self):
        evaluation = "sailens-guidance/src/test/java/com/sailens/guidance/evaluation/A.kt"
        self.write(evaluation, "// R7 of the frozen note, D1.8, M5\n// see docs/gone.md\n")
        self.assertEqual(["missing document"], self.rules(evaluation))


class LinkRulesTest(RepoTest):
    def test_a_mention_of_a_missing_document_is_found_anywhere(self):
        self.write("src/A.kt", 'val hint = "see docs/gone.md"\n')
        self.assertEqual(["missing document"], self.rules("src/A.kt"))

    def test_a_section_must_be_a_heading_of_that_document(self):
        self.write("src/A.kt", "// docs/models.md §1.2 holds; models.md §1 too; models.md §9 does not\n")
        self.assertEqual(["missing section"], self.rules("src/A.kt"))

    def test_relative_markdown_links_must_resolve(self):
        self.write("docs/models.md", "# Models\n\n## 1. Contract\n\n[ok](models.zh-CN.md) [web](https://x.org/a.md) "
                                     "[gone](roadmap.md) [anchor](#1-contract)\n")
        self.assertEqual(["broken link"], self.rules("docs/models.md"))

    def test_a_docs_path_inside_an_external_url_is_not_this_repository(self):
        self.write("src/A.kt", "// see https://example.com/repo/blob/main/docs/api.md and docs/models.md §1\n")
        self.assertEqual([], self.rules("src/A.kt"))

    def test_links_with_titles_references_and_parentheses_follow_markdown(self):
        self.write("docs/file_(1).md", "x\n")
        self.write("docs/file_(1).zh-CN.md", "x\n")
        self.write("README.md", "\n".join([
            '[titled](gone-a.md "a title")',
            "[ok](docs/file_(1).md) [angle](<docs/file_(1).md>) [spaced](docs/file_%281%29.md)",
            "[by reference][ref]",
            "",
            "[ref]: gone-b.md",
            "`[not a link](gone-c.md)`",
            "```",
            "[in a code block](gone-d.md)",
            "```",
        ]))
        self.assertEqual([(1, "broken link"), (5, "broken link")],
                         [(f.line, f.rule) for f in self.findings() if f.path == "README.md"])

    def test_a_heading_inside_a_code_block_is_not_a_section(self):
        self.write("docs/models.md", "# Models\n\n## 1. Contract\n\n```\n## 9. Example\n```\n")
        self.write("src/A.kt", "// models.md §1 holds, models.md §9 does not\n")
        self.assertEqual(["missing section"], self.rules("src/A.kt"))


class DocsSetTest(RepoTest):
    def test_an_unlisted_top_level_document_is_found(self):
        self.write("docs/roadmap.md", "x\n")
        self.write("docs/roadmap.zh-CN.md", "x\n")
        self.assertEqual(["unlisted document"], self.rules("docs/roadmap.md"))

    def test_every_document_needs_its_chinese_twin(self):
        self.write("docs/experiments/probe-2026-09.md", "x\n")
        self.assertEqual(["missing twin"], self.rules("docs/experiments/probe-2026-09.md"))

    def test_listed_documents_with_twins_pass(self):
        self.assertEqual([], self.findings())


class ScopeTest(RepoTest):
    def test_the_test_data_file_is_not_scanned_but_the_checker_is(self):
        self.write("scripts/test_verify_no_process_history.py", "# M3a\nx = 'see docs/gone.md'\n")
        self.write("scripts/verify-no-process-history.py", "# M3a is a pattern it checks\nx = 'docs/gone.md'\n")
        self.assertEqual([], self.rules("scripts/test_verify_no_process_history.py"))
        self.assertEqual(["missing document"], self.rules("scripts/verify-no-process-history.py"))


class RepositoryTest(unittest.TestCase):
    def test_the_repository_itself_passes(self):
        findings = check.scan(check.ROOT, check.tracked_files(check.ROOT))
        self.assertEqual([], [str(f) for f in findings])


if __name__ == "__main__":
    unittest.main()
