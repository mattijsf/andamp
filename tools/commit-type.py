#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""
Keep feat:, change: and fix: for what a listener notices.

release-please builds the changelog and the release notes from commit types, so
a type decides what somebody who installed Andamp gets told. A change to a build
file or to this script is not news to them.

So a feat, change or fix has to touch something that ships. Everything else is
chore, which stays out of the changelog.
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

# What ends up on a phone: any path under a `src/main/` directory, and the
# `.wsz` and `.zip` files directly in skin/dist (the skins and their runtime
# templates), which :app:bundledSkins packages. The previews and checksums
# beside them do not ship.
SHIPS = re.compile(r"(^|/)src/main/|^skin/dist/[^/]+\.(wsz|zip)$")
TELLS = re.compile(r"^(feat|change|fix)(\([^)]*\))?!?:", re.I)


def staged() -> list[str]:
    done = subprocess.run(
        ["git", "diff", "--cached", "--name-only"],
        capture_output=True,
        text=True,
        check=False,
    )
    return [line for line in done.stdout.splitlines() if line.strip()]


def main() -> int:
    if len(sys.argv) < 2:
        return 0
    subject = Path(sys.argv[1]).read_text(encoding="utf-8").lstrip().splitlines()
    if not subject:
        return 0
    first = subject[0]
    # a merge writes its own message and stages nothing of its own
    if first.startswith("Merge ") or not TELLS.match(first):
        return 0
    files = staged()
    if not files or any(SHIPS.search(name) for name in files):
        return 0
    print(f"\n  ✗ {first}", file=sys.stderr)
    print(
        "\n  feat:, change: and fix: go into the release notes, and this commit changes\n"
        "  nothing that ships:\n",
        file=sys.stderr,
    )
    for name in files[:10]:
        print(f"      {name}", file=sys.stderr)
    if len(files) > 10:
        print(f"      ...and {len(files) - 10} more", file=sys.stderr)
    print(
        "\n  Call it chore: instead - it stays out of the changelog, which is\n"
        "  where a listener reads what changed for them.\n",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
