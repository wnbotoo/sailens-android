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
3. that every `.md` path written in a comment or in Markdown names a file that exists (relative to
   the file, to the repository root or to docs/), that every `docs/<name>.md` in any text -- strings
   included -- exists, and that `<name>.md §N` names a numbered heading of that document;
4. that docs/ holds only the listed reference documents, each with its Chinese twin, and that every
   experiment record under docs/experiments/ has its twin.

It is a guard over the forms this repository writes, not a parser. Paths are checked as written,
whatever the Markdown syntax around them, so a placeholder is written `<name>.md`, never as a real
looking name; a path with characters outside `[A-Za-z0-9_.-/]` is not checked. Unusual forms may slip
through; blocking legitimate text is a bug. A false positive is fixed by narrowing a pattern or by
adding a path exemption below, with its reason, in the same change. There is no per-line escape hatch.

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

# ASCII word boundaries: Python's Unicode \b sees no boundary between a CJK character and a letter, so
# "按M3a方案" would slip through \b.
_A = r"(?<![A-Za-z0-9_])"
_Z = r"(?![A-Za-z0-9_])"

# (rule, pattern). Applied to comment text and to Markdown. These forms only ever name process items;
# ids are matched as families (any milestone Mn with a letter, any dotted or lettered plan item), not as
# the list of ids used so far.
MARKER_RULES = [
    ("milestone id", re.compile(_A + r"M\d{1,2}[abo]" + _Z)),
    ("plan id", re.compile(_A + r"D\d{1,2}(?:\.\d+(?:-\d+)?|[a-c](?:-\d+)?)" + _Z)),
    ("plan name", re.compile(_A + r"(?i:det-first)" + _Z + r"|det\s*优先|" + _A + r"plan v\d")),
    ("PR number", re.compile(
        _A + r"PR[ -]?#?\d+" + _Z + "|" + _A + r"PR-(?:\d[a-z]?|D\d)" + _Z + r"|\(#\d+\)|" + _A + r"pull/\d+"
    )),
    # A model name alone is not review history; only review wording is.
    ("review round", re.compile(
        r"(?i:" + _A + r"review(?:ed)?\s+round" + _Z + "|" + _A + r"review\s+finding|" + _A + r"round\s+\d+\s*[,:;)]"
        + "|" + _A + r"GPT[- ]?review)|GPT\s*评审|第\s*[0-9一二三四五六七八九十]+\s*轮\s*(?:评审|审)"
    )),
    # Not inside a file name: experiment records are named after the month they were made in.
    ("date", re.compile(
        r"(?<![A-Za-z0-9_/.-])20\d\d-(?:0[1-9]|1[0-2])(?:-(?:0[1-9]|[12]\d|3[01]))?(?![A-Za-z0-9_-]|\.[A-Za-z0-9])"
    )),
]
# A bare M14, D3 or P1-2 is as likely a device model, a layer or a page number. These count only with
# process context on the same line (PROCESS_WORD), as the label the comment or heading starts with
# ("D5: ...", "### M4 — ..."), or alone in parentheses ("(M4)").
AMBIGUOUS_RULES = [
    ("milestone id", r"M\d{1,2}"),
    ("plan id", r"D\d{1,2}"),
    ("review finding id", r"P[0-3]-\d+"),
]
PROCESS_WORD = re.compile(
    r"(?i:" + _A + r"(?:milestones?|roadmaps?|reviews?|reviewed|findings?)" + _Z + "|" + _A + r"round\s+\d)"
    r"|里程碑|路线图|评审|轮次"
)
AMBIGUOUS_PATTERNS = [
    (rule, re.compile(_A + core + _Z), re.compile(r"^[\s*#>/•-]*(?:\*\*)?" + core + r"(?:\*\*)?\s*[:,—–]"),
     re.compile(r"\(\s*" + core + r"\s*[),;]"))
    for rule, core in AMBIGUOUS_RULES
]
# Comments only: Markdown uses "#1" for list items and anchors.
COMMENT_ONLY_RULES = [
    ("PR number", re.compile(r"(?<![A-Za-z0-9_&#/])#\d{1,4}" + _Z)),
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

KOTLIN = {".kt", ".kts", ".gradle"}
JAVA = {".java"}
C_FAMILY = {".c", ".cc", ".cpp", ".h", ".hpp"}
C_LIKE = KOTLIN | JAVA | C_FAMILY
HASH_COMMENTS = {".pro", ".toml", ".properties", ".yml", ".yaml", ".cmake", ".sh", ".gitignore", ".gitattributes",
                 ".editorconfig"}
HASH_COMMENT_NAMES = {"CMakeLists.txt", ".gitignore", ".gitattributes", ".editorconfig"}
TEXT_FOR_LINKS = C_LIKE | HASH_COMMENTS | {".py", ".xml", ".md", ".txt", ".json"}

DOC_MENTION = re.compile(r"(?<![\w.-])docs/([\w./-]+?\.md)\b")
# A path to a Markdown file as written in prose or a comment: plain characters only.
MD_PATH = re.compile(
    r"(?<![A-Za-z0-9_./\\:<>$-])((?:\.{1,2}/)*(?:[A-Za-z0-9_-][A-Za-z0-9_.-]*/)*[A-Za-z0-9_-][A-Za-z0-9_.-]*\.md)"
    r"(?![A-Za-z0-9_])"
)
SECTION_REF = re.compile(r"(?<![\w.-])((?:docs/)?(?:experiments/)?[\w-]+(?:\.zh-CN)?\.md)`?\)?\s*§\s*(\d+(?:\.\d+)*)")
# A full URL names someone else's file, never this repository's. It ends where a string, a link or
# code would: at whitespace, a quote, a bracket, a parenthesis or a backtick.
URL = re.compile(r"""[A-Za-z][A-Za-z0-9+.-]*://[^\s"'<>()\[\]`]+""")
# CommonMark fences: an opening backtick fence's info string has no backtick; a closing fence is the
# same character, at least as long, and nothing but spaces after it. Block-quote markers come first.
MD_QUOTE = re.compile(r"^(?: {0,3}>[ ]?)*")
MD_FENCE_OPEN = re.compile(r"^ {0,3}(?:(`{3,})[^`]*|(~{3,}).*)$")
MD_FENCE_CLOSE = re.compile(r"^ {0,3}(`{3,}|~{3,})[ \t]*$")
HEADING_NUMBER = re.compile(r"^ {0,3}#{1,6}\s+(\d+(?:\.\d+)*)\.?\s")


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    rule: str
    text: str

    def __str__(self) -> str:
        return f"{self.path}:{self.line}: {self.rule}: {self.text.strip()}"


# ---- comment extraction: each yields (line number, comment text of that line) -------------------

# A d-char is any source character but space, parentheses, backslash and the controls (tab, vertical
# tab, form feed, newline): a double quote is allowed.
CPP_RAW_STRING = re.compile(r'(?:u8|u|U|L)?R"([^()\\ \t\v\f\n]{0,16})\(')


def _starts_number(text: str, i: int) -> bool:
    """Whether the token ending just before `i` is a number literal, so the `'` at `i` is a C++14
    digit separator (`1'000`, `0xFF'FF`, `.1'25`) and not the start of a character literal (`u8'a'`)."""
    j = i
    while j > 0 and (text[j - 1].isalnum() or text[j - 1] in "_'."):
        j -= 1
    while j < i and text[j] == "." and not (j + 1 < i and text[j + 1].isdigit()):
        j += 1  # a member access or range dot before the token is not part of it
    return j < i and (text[j].isdigit() or (text[j] == "." and j + 1 < i and text[j + 1].isdigit()))


def c_like_comments(text: str, lang: str) -> list[tuple[int, str]]:
    """Comments of a Kotlin, Java or C/C++ file. Literals are skipped, so a `//` inside one is not a
    comment: Kotlin raw strings with `${...}` templates and backticked names, Java text blocks, C++
    raw strings and C++14 digit separators."""
    kotlin, java, cpp = lang == "kotlin", lang == "java", lang == "c"
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
                    if kotlin and text.startswith("/*", j):  # Kotlin block comments nest
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
            if (kotlin or java) and text.startswith('"""', i):
                modes.append(["raw"])
                i += 3
                continue
            if cpp and (i == 0 or not (text[i - 1].isalnum() or text[i - 1] == "_")):
                raw = CPP_RAW_STRING.match(text, i)
                if raw:
                    close = text.find(")" + raw.group(1) + '"', raw.end())
                    end = n if close < 0 else close + len(raw.group(1)) + 2
                    line += text.count("\n", i, end)
                    i = end
                    continue
            if kotlin and ch == "`":  # a backticked name may hold quotes and apostrophes
                end = text.find("`", i + 1)
                if end > 0 and "\n" not in text[i:end]:
                    i = end + 1
                    continue
            if ch == '"':
                modes.append(["str"])
            elif ch == "'" and cpp and _starts_number(text, i):
                pass  # a digit separator, not a character literal
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
            if kotlin and mode[0] == "str" and text.startswith("${", i):
                modes.append(["code", 0])
                i += 2
                continue
            if ch == quote or ch == "\n":  # an unterminated literal ends at the line
                if ch == "\n":
                    line += 1
                modes.pop()
            i += 1
            continue
        # Kotlin raw string or Java text block
        if java and ch == "\\":  # text blocks process escapes; \""" is not the end
            if i + 1 < n and text[i + 1] == "\n":
                line += 1
            i += 2
            continue
        if text.startswith('"""', i):
            while text.startswith('""""', i):  # extra quotes belong to the content
                i += 1
            modes.pop()
            i += 3
            continue
        if kotlin and text.startswith("${", i):
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
        return c_like_comments(text, "kotlin" if suffix in KOTLIN else "java" if suffix in JAVA else "c")
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


def commented_lines(path: str, text: str) -> list[tuple[int, str]] | None:
    """What the marker and path rules read: every line of Markdown, the comments of anything else."""
    if path.endswith(".md"):
        return list(enumerate(text.split("\n"), start=1))
    return comments_of(path, text)


def ambiguous_finding(content: str) -> str | None:
    """The rule a bare M14 / D3 / P1-2 breaks, if the line gives it process context."""
    for rule, bare, label, parenthesised in AMBIGUOUS_PATTERNS:
        if bare.search(content) and (PROCESS_WORD.search(content) or label.match(content)
                                     or parenthesised.search(content)):
            return rule
    return None


def marker_findings(path: str, text: str) -> list[Finding]:
    if exempt(path):
        return []
    lines = commented_lines(path, text)
    if lines is None:
        return []
    rules = MARKER_RULES if path.endswith(".md") else MARKER_RULES + COMMENT_ONLY_RULES
    out = []
    for number, content in lines:
        rule = next((name for name, pattern in rules if pattern.search(content)), None) or ambiguous_finding(content)
        if rule:
            out.append(Finding(path, number, rule, content))
    return out


def markdown_prose_lines(text: str) -> list[tuple[int, str]]:
    """The lines of a Markdown file outside fenced code blocks, block-quote markers removed. A fence
    opened inside a block quote also ends where the quote does."""
    out: list[tuple[int, str]] = []
    fence, fence_depth = None, 0
    for number, line in enumerate(text.split("\n"), start=1):
        quote = MD_QUOTE.match(line).group(0)
        depth, body = quote.count(">"), line[len(quote):]
        if fence is not None:
            close = MD_FENCE_CLOSE.match(body)
            if depth < fence_depth:
                fence = None  # the container ended, and the code block with it
            elif close and close.group(1)[0] == fence[0] and len(close.group(1)) >= len(fence):
                fence = None
                continue
            else:
                continue
        opening = MD_FENCE_OPEN.match(body)
        if opening:
            fence, fence_depth = opening.group(1) or opening.group(2), depth
            continue
        out.append((number, body))
    return out


def headings(doc: Path, cache: dict[Path, set[str]]) -> set[str]:
    """The section numbers of a document's ATX headings, code blocks excluded."""
    if doc not in cache:
        found: set[str] = set()
        if doc.is_file():
            for _, line in markdown_prose_lines(doc.read_text(encoding="utf-8")):
                match = HEADING_NUMBER.match(line)
                if match:
                    found.add(match.group(1))
        cache[doc] = found
    return cache[doc]


def link_findings(root: Path, path: str, text: str, cache: dict[Path, set[str]]) -> list[Finding]:
    out = []
    here = (root / path).parent
    for number, raw in enumerate(text.split("\n"), start=1):
        content = URL.sub(" ", raw)
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
    # Any Markdown path written in prose or a comment, whatever the link syntax around it.
    for number, content in commented_lines(path, text) or []:
        for match in MD_PATH.finditer(URL.sub(" ", content)):
            name = match.group(1)
            if name.startswith("docs/") and DOC_MENTION.fullmatch(name):
                continue  # already checked above, on the whole line
            if not any((base / name).exists() for base in (here, root, root / "docs")):
                out.append(Finding(path, number, "missing document", name))
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
