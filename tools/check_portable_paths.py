"""Reject developer-machine paths in tracked source, including initialized submodules."""
import argparse
from pathlib import Path
import re
import subprocess


PATTERNS = {
    "developer home directory": re.compile(r"/(?:Users|home)/[\w.-]+/"),
    "Windows user directory": re.compile(r"[a-z]:[\\/]Users[\\/][^\s\\/]+[\\/]", re.I),
    "fixed Homebrew installation": re.compile(r"/opt/homebrew/"),
    "local workspace mount": re.compile(r"/(?:data|mnt|Volumes)/agent-os(?:/|\b)"),
    "absolute placeholder": re.compile(r"/(?:absolute/)?path/to(?:/|\b)"),
}


def check(root):
    tracked = subprocess.check_output(["git", "-C", str(root), "ls-files", "--recurse-submodules", "-z"])
    failures = []
    for name in tracked.decode().split("\0"):
        path = root / name
        if not name or not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeError:
            continue
        if "\0" in text:
            continue
        for number, line in enumerate(text.splitlines(), 1):
            for label, pattern in PATTERNS.items():
                if pattern.search(line):
                    failures.append(f"{name}:{number}: {label}")
    return failures


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    failures = check(args.root)
    for failure in failures:
        print(failure)
    if failures:
        raise SystemExit(1)
    print("Tracked source contains no developer-machine paths")
