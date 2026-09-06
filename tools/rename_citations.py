"""Rewrites plan citations and moves the plan files, from `rename.tsv`.

A MIGRATION, not a tool: deleted once P2 lands. See `plan/meta-plans-repo.md` §3.

    python tools/rename_citations.py --only plan.md      # P1
    python tools/rename_citations.py --area engine       # P2, once per area
    python tools/rename_citations.py --count             # what would change, no writes

Citations name a FILE and never a path, which is what lets a plan be re-parented later without
touching a single one of them. So the rewrite is `plan_m6.md` -> `plan/engine-port.md`: the new
name, under the `plan/` prefix that says what kind of document it is.
"""
import argparse
import io
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# A full second copy of the tree lives in .claude/worktrees/ and TRIPLES every count taken without
# this. `plan/` is excluded because it is the destination, and build outputs because they are derived.
SKIP_DIRS = {".git", "build", "node_modules", ".gradle", "plan"}
SKIP_PATH = (os.sep + ".claude" + os.sep, os.sep + "build" + os.sep)
EXTENSIONS = (".java", ".kts", ".gradle", ".css", ".md", ".json", ".txt")


def rows():
    out = []
    with io.open(os.path.join(ROOT, "rename.tsv"), encoding="utf-8") as f:
        for line in f:
            if line.strip():
                out.append(line.rstrip("\n").split("\t"))
    return out


def files(repo_root):
    """Every file a citation could be in, worktrees and build outputs excluded."""
    for base, dirs, names in os.walk(repo_root):
        dirs[:] = [d for d in dirs
                   if d not in SKIP_DIRS and not d.startswith(".claude")
                   # A NESTED GIT REPOSITORY IS SOMEBODY ELSE'S HISTORY. CrystalGraphics, taffy and
                   # the harness all live inside this tree and are none of this repo's business.
                   and not os.path.exists(os.path.join(base, d, ".git"))]
        if any(p in base + os.sep for p in SKIP_PATH):
            continue
        for name in names:
            if name.endswith(EXTENSIONS):
                yield os.path.join(base, name)


def pattern_for(old):
    """`plan.md` needs a left boundary; every `plan_*.md` is unambiguous as a literal."""
    if old == "plan.md":
        return re.compile(r"(?<![A-Za-z0-9_/])plan\.md")
    return re.compile(re.escape(old))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--area")
    ap.add_argument("--only")
    ap.add_argument("--count", action="store_true")
    ap.add_argument("--no-move", action="store_true", help="rewrite citations, leave the files")
    args = ap.parse_args()

    selected = []
    for old, new, dest, repo in rows():
        if args.only and old != args.only:
            continue
        if args.area and not new.startswith(args.area + "-"):
            continue
        selected.append((old, new, dest, repo))
    if not selected:
        print("nothing selected", file=sys.stderr)
        return 1

    # THE MAPPING FILE IS THE RECORD OF THE RENAME, so its "old name" column must survive it.
    record = os.path.join(ROOT, "plan_plans.md")
    subs = [(pattern_for(old), "plan/" + new) for old, new, _, _ in selected]

    changed, hits = 0, 0
    for path in files(ROOT):
        if os.path.abspath(path) == record:
            continue
        try:
            text = io.open(path, encoding="utf-8", newline="").read()
        except (UnicodeDecodeError, OSError):
            continue
        out = text
        for pat, replacement in subs:
            out, n = pat.subn(replacement, out)
            hits += n
        if out != text:
            changed += 1
            if not args.count:
                io.open(path, "w", encoding="utf-8", newline="").write(out)

    print(f"{hits} citations in {changed} files"
          + (" (dry run)" if args.count else ""))

    if args.count or args.no_move:
        return 0
    for old, new, _, repo in selected:
        base = os.path.join(ROOT, "CrystalGraphics") if repo == "cg" else ROOT
        src = os.path.join(base, old)
        if not os.path.exists(src):
            continue
        dst = os.path.join(base, os.path.basename(new))
        subprocess.check_call(["git", "mv", old, os.path.basename(new)], cwd=base)
        print(f"  {old} -> {os.path.basename(new)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
