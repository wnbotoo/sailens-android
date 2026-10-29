#!/usr/bin/env python3
"""Fail when comments or docs carry process history, or point at a document that is not there.

Comments say what the code does now and why; docs/ describes the code as it is. Milestone and plan
ids, PR numbers, review rounds and dates kept as history belong in commit messages and PR
descriptions: in a comment they are links to documents an outside reader does not have, and they go
stale the day the plan changes. This checks, over the files git tracks:

1. comments in source files (Kotlin, Java, C/C++, Python comments and docstrings, XML comments, and
   `#` comments in build and config files) -- comments only, so identifiers, serialized names and
   report text are never matched;
2. the full text of every Markdown file;
3. that every `docs/<name>.md` a tracked file mentions exists, and that `<name>.md §N` names a
   heading of that document; relative Markdown links must resolve too;
4. that docs/ holds only the listed reference documents, each with its Chinese twin, and that every
   experiment record under docs/experiments/ has its twin.

A false positive is fixed by narrowing a pattern or by adding a path exemption below, with its
reason, in the same change. There is no per-line escape hatch.

Usage: verify-no-process-history.py
"""
from __future__ import annotations

import ast
import io
import re
import subprocess
import sys
import tokenize
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Top-level reference documents. A new one is added here, in the change that adds it, so whether it
# is reference material (and not a plan, a roadmap, a review design or a manual) is decided in review.
DOCS = frozenset({
    "architecture",
    "distribution-model",
    "field-capture",
    "geometry",
    "models",
    "npu-litert-qnn",
    "perception-profiles",
    "trace_metrics_guide",
    "trace_replay_workflow",
})

# (rule, pattern). Applied to comment text and to Markdown.
MARKER_RULES = [
    ("milestone id", re.compile(r"\bM(?:0[ab]|1b|2[ab]|3[oab]|5[ab]|1[01]|[1-9])\b")),
    ("plan id", re.compile(r"\bD[1-5](?:\.\d+(?:-\d+)?|[a-c](?:-\d+)?)?\b")),
    ("plan name", re.compile(r"(?i:\bdet-first\b)|det\s*优先|\bplan v\d")),
    ("PR number", re.compile(r"\bPR[ -]?#?\d+\b|\bPR-(?:\d[a-z]?|D\d)\b|\(#\d+\)|\bpull/\d+\b")),
    ("review round", re.compile(
        r"(?i:\breview(?:ed)?\s+round\b|\breview\s+finding\b|\bround\s+\d+\s*[,:;)])|\bGPT\b"
        r"|第\s*[0-9一二三四五六七八九十]+\s*轮"
    )),
    ("review finding id", re.compile(r"\bP[0-2]-\d\b")),
    # Not inside a file name: experiment records are named after the month they were made in.
    ("date", re.compile(r"(?<![\w/.-])20\d\d-(?:0[1-9]|1[0-2])(?:-(?:0[1-9]|[12]\d|3[01]))?(?![\w-]|\.\w)")),
]
# Comments only: Markdown uses "#1" for list items and anchors.
COMMENT_ONLY_RULES = [
    ("PR number", re.compile(r"(?<![\w&#/])#\d{1,4}\b")),
]

# Path prefixes exempt from the marker rules (never from the link and docs rules).
EXEMPT = {
    "sailens-guidance/src/test/java/com/sailens/guidance/evaluation/":
        "implements the frozen prompt-policy threshold note in docs/experiments/; its ids name that "
        "note's items, and stay while the note is in force",
    "docs/experiments/":
        "experiment records: when a measurement was made, and under which plan item, is part of the record",
    "scripts/experiments/":
        "experiment scripts and their dated results, described by docs/experiments/",
    "scripts/verify-no-process-history.py": "spells out the patterns it checks",
}
# Not checked at all: its strings are deliberate violations and links to documents that do not exist.
TEST_DATA = {"scripts/test_verify_no_process_history.py"}

C_LIKE = {".kt", ".kts", ".java", ".c", ".cc", ".cpp", ".h", ".hpp", ".gradle"}
NESTED_BLOCK_COMMENTS = {".kt", ".kts"}
TEMPLATE_STRINGS = {".kt", ".kts"}
HASH_COMMENTS = {".pro", ".toml", ".properties", ".yml", ".yaml", ".cmake", ".sh", ".gitignore", ".gitattributes",
                 ".editorconfig"}
HASH_COMMENT_NAMES = {"CMakeLists.txt", ".gitignore", ".gitattributes", ".editorconfig"}
TEXT_FOR_LINKS = C_LIKE | HASH_COMMENTS | {".py", ".xml", ".md", ".txt", ".json"}

DOC_MENTION = re.compile(r"(?<![\w.-])docs/([\w./-]+?\.md)\b")
SECTION_REF = re.compile(r"(?<![\w.-])((?:docs/)?(?:experiments/)?[\w-]+(?:\.zh-CN)?\.md)`?\)?\s*§\s*(\d+(?:\.\d+)*)")
MD_LINK = re.compile(r"\]\(([^)\s]+)\)")
HEADING_NUMBER = re.compile(r"^#{1,6}\s+(\d+(?:\.\d+)*)\.?\s", re.MULTILINE)


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    rule: str
    text: str

    def __str__(self) -> str:
        return f"{self.path}:{self.line}: {self.rule}: {self.text.strip()}"


# ---- comment extraction: each yields (line number, comment text of that line) -------------------

def c_like_comments(text: str, nested: bool, templates: bool) -> list[tuple[int, str]]:
    """Comments of a C-family file. Strings (with Kotlin's raw strings and `${...}` templates) are
    skipped, so a `//` inside a string is not a comment."""
    out: list[tuple[int, str]] = []
    modes: list[list] = [["code", 0]]  # ["code", template brace depth] | ["str"] | ["raw"] | ["char"]
    i, n, line = 0, len(text), 1
    while i < n:
        mode = modes[-1]
        ch = text[i]
        if mode[0] == "code":
            if text.startswith("//", i):
                end = text.find("\n", i)
                end = n if end < 0 else end
                out.append((line, text[i + 2:end]))
                i = end
                continue
            if text.startswith("/*", i):
                depth, j, start, start_line = 1, i + 2, i + 2, line
                while j < n and depth:
                    if nested and text.startswith("/*", j):
                        depth, j = depth + 1, j + 2
                    elif text.startswith("*/", j):
                        depth, j = depth - 1, j + 2
                    else:
                        j += 1
                body = text[start:j - 2 if depth == 0 else j]
                for k, part in enumerate(body.split("\n")):
                    out.append((start_line + k, part))
                line += body.count("\n")
                i = j
                continue
            if templates and text.startswith('"""', i):
                modes.append(["raw"])
                i += 3
                continue
            if templates and ch == "`":  # a backticked name may hold quotes and apostrophes
                end = text.find("`", i + 1)
                if end > 0 and "\n" not in text[i:end]:
                    i = end + 1
                    continue
            if ch == '"':
                modes.append(["str"])
            elif ch == "'":
                modes.append(["char"])
            elif ch == "{" and len(modes) > 1:
                mode[1] += 1
            elif ch == "}" and len(modes) > 1:
                if mode[1] == 0:
                    modes.pop()
                else:
                    mode[1] -= 1
            elif ch == "\n":
                line += 1
            i += 1
            continue
        if mode[0] in ("str", "char"):
            quote = '"' if mode[0] == "str" else "'"
            if ch == "\\":
                if i + 1 < n and text[i + 1] == "\n":
                    line += 1
                i += 2
                continue
            if templates and mode[0] == "str" and text.startswith("${", i):
                modes.append(["code", 0])
                i += 2
                continue
            if ch == quote or ch == "\n":  # an unterminated literal ends at the line
                if ch == "\n":
                    line += 1
                modes.pop()
            i += 1
            continue
        # raw string
        if text.startswith('"""', i):
            while text.startswith('""""', i):  # extra quotes belong to the content
                i += 1
            modes.pop()
            i += 3
            continue
        if text.startswith("${", i):
            modes.append(["code", 0])
            i += 2
            continue
        if ch == "\n":
            line += 1
        i += 1
    return out


def python_comments(text: str) -> list[tuple[int, str]]:
    """`#` comments and string statements (docstrings and bare strings used as comments)."""
    out: list[tuple[int, str]] = []
    try:
        for token in tokenize.generate_tokens(io.StringIO(text).readline):
            if token.type == tokenize.COMMENT:
                out.append((token.start[0], token.string[1:]))
    except (tokenize.TokenError, SyntaxError):
        pass
    try:
        tree = ast.parse(text)
    except SyntaxError:
        return out
    lines = text.split("\n")
    for node in ast.walk(tree):
        if isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant) and isinstance(node.value.value, str):
            for number in range(node.lineno, (node.end_lineno or node.lineno) + 1):
                out.append((number, lines[number - 1]))
    return sorted(out)


def xml_comments(text: str) -> list[tuple[int, str]]:
    out: list[tuple[int, str]] = []
    for match in re.finditer(r"<!--(.*?)-->", text, re.DOTALL):
        start_line = text.count("\n", 0, match.start()) + 1
        for k, part in enumerate(match.group(1).split("\n")):
            out.append((start_line + k, part))
    return out


def hash_comments(text: str) -> list[tuple[int, str]]:
    out: list[tuple[int, str]] = []
    for number, raw in enumerate(text.split("\n"), start=1):
        quote = None
        for k, ch in enumerate(raw):
            if quote:
                if ch == quote:
                    quote = None
            elif ch in "\"'":
                quote = ch
            elif ch == "#" and (k == 0 or raw[k - 1].isspace()):
                out.append((number, raw[k + 1:]))
                break
    return out


def comments_of(path: str, text: str) -> list[tuple[int, str]] | None:
    name = Path(path).name
    suffix = Path(path).suffix
    if suffix in C_LIKE:
        return c_like_comments(text, suffix in NESTED_BLOCK_COMMENTS, suffix in TEMPLATE_STRINGS)
    if suffix == ".py":
        return python_comments(text)
    if suffix == ".xml":
        return xml_comments(text)
    if suffix in HASH_COMMENTS or name in HASH_COMMENT_NAMES:
        return hash_comments(text)
    return None


# ---- checks -------------------------------------------------------------------------------------

def exempt(path: str) -> bool:
    return any(path == prefix or path.startswith(prefix) for prefix in EXEMPT)


def marker_findings(path: str, text: str) -> list[Finding]:
    if exempt(path):
        return []
    if path.endswith(".md"):
        lines = list(enumerate(text.split("\n"), start=1))
        rules = MARKER_RULES
    else:
        found = comments_of(path, text)
        if found is None:
            return []
        lines = found
        rules = MARKER_RULES + COMMENT_ONLY_RULES
    out = []
    for number, content in lines:
        for rule, pattern in rules:
            if pattern.search(content):
                out.append(Finding(path, number, rule, content))
                break
    return out


def headings(doc: Path, cache: dict[Path, set[str]]) -> set[str]:
    if doc not in cache:
        cache[doc] = set(HEADING_NUMBER.findall(doc.read_text(encoding="utf-8"))) if doc.is_file() else set()
    return cache[doc]


def link_findings(root: Path, path: str, text: str, cache: dict[Path, set[str]]) -> list[Finding]:
    out = []
    here = (root / path).parent
    for number, content in enumerate(text.split("\n"), start=1):
        for match in DOC_MENTION.finditer(content):
            if not (root / "docs" / match.group(1)).is_file():
                out.append(Finding(path, number, "missing document", f"docs/{match.group(1)}"))
        for match in SECTION_REF.finditer(content):
            name, section = match.group(1), match.group(2)
            candidates = [root / name] if name.startswith("docs/") else [here / name, root / "docs" / name]
            doc = next((c for c in candidates if c.is_file()), None)
            if doc is None:
                out.append(Finding(path, number, "missing document", name))
            elif section not in headings(doc, cache):
                out.append(Finding(path, number, "missing section", f"{name} §{section}"))
        if path.endswith(".md"):
            for match in MD_LINK.finditer(content):
                target = match.group(1).split("#", 1)[0]
                if not target or re.match(r"^[a-z][a-z0-9+.-]*:", target):
                    continue
                if not (here / target).exists():
                    out.append(Finding(path, number, "broken link", match.group(1)))
    return out


def docs_findings(root: Path, files: list[str], allowed: frozenset[str] = DOCS) -> list[Finding]:
    out = []
    tracked = set(files)
    for path in sorted(f for f in files if f.startswith("docs/") and f.endswith(".md")):
        relative = path[len("docs/"):]
        stem = relative[:-len(".zh-CN.md")] if relative.endswith(".zh-CN.md") else relative[:-len(".md")]
        twin = f"docs/{stem}.md" if relative.endswith(".zh-CN.md") else f"docs/{stem}.zh-CN.md"
        if "/" not in relative and stem not in allowed:
            out.append(Finding(path, 1, "unlisted document",
                               "docs/ holds reference material only; list it in DOCS if it is that"))
        if twin not in tracked:
            out.append(Finding(path, 1, "missing twin", twin))
    return out


def scan(root: Path, files: list[str], allowed: frozenset[str] = DOCS) -> list[Finding]:
    findings: list[Finding] = []
    cache: dict[Path, set[str]] = {}
    for path in files:
        full = root / path
        suffix = full.suffix
        if path in TEST_DATA or not (suffix in TEXT_FOR_LINKS or full.name in HASH_COMMENT_NAMES) or not full.is_file():
            continue
        try:
            text = full.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        findings += marker_findings(path, text)
        findings += link_findings(root, path, text, cache)
    findings += docs_findings(root, files, allowed)
    return findings


def tracked_files(root: Path) -> list[str]:
    result = subprocess.run(["git", "ls-files", "-z"], cwd=root, check=True, capture_output=True)
    return [f for f in result.stdout.decode("utf-8").split("\0") if f]


def main() -> int:
    findings = scan(ROOT, tracked_files(ROOT))
    for finding in findings:
        print(finding)
    if findings:
        print(
            f"\nverify-no-process-history: {len(findings)} finding(s). Say what the code does and why; "
            "put milestones, plan ids, PR numbers, review rounds and dates in the commit message or PR "
            "description. Point at documents that exist.",
            file=sys.stderr,
        )
        return 1
    print("verify-no-process-history: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
