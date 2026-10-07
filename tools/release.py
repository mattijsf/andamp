#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""
Ship the tag that release-please made, and write what changed.

    python3 tools/release.py                 # check, and print both texts
    python3 tools/release.py --upload        # ...and send the bundle to Play

release-please owns the version, the CHANGELOG and the tag: merging its release
PR bumps `versionName`, writes CHANGELOG.md and tags. This script does the
rest, and runs locally so the signing password (RT_STORE_PASSWORD) is never
given to a CI runner.

Play caps "what's new" at 500 characters and Discord a message at 2000, so the
same changelog section is trimmed once for each.
"""

from __future__ import annotations

import argparse
import html
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHANGELOG = ROOT / "CHANGELOG.md"
GRADLE = ROOT / "app" / "build.gradle.kts"
PLAY_NOTES = ROOT / "app" / "src" / "main" / "play" / "release-notes" / "en-US" / "default.txt"
# Optional: the store's "what's new" written by hand, used in place of the
# trimmed changelog. The file is not in the repository; create it to use it.
# Its first line is the version the words are for (see hand_written).
WRITTEN_BY_HAND = ROOT / "docs" / "play-notes.txt"

PLAY_LIMIT = 500
DISCORD_LIMIT = 2000
# Play takes an hour or two to hand an upload out, and the channel is read as
# soon as the post appears.
DISCORD_TAIL = "-# It can take an hour or two before the update reaches the Play Store.\n"


def run(*args: str) -> str:
    return subprocess.run(args, cwd=ROOT, capture_output=True, text=True, check=False).stdout.strip()


def fail(why: str) -> None:
    print(f"  ✗ {why}", file=sys.stderr)
    sys.exit(1)


def version_name() -> str:
    text = GRADLE.read_text(encoding="utf-8")
    found = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not found:
        fail("no versionName in app/build.gradle.kts")
    return found.group(1)


def version_code(name: str) -> int:
    parts = [int(re.match(r"\d*", p).group() or 0) for p in name.split(".")]
    while len(parts) < 3:
        parts.append(0)
    return parts[0] * 10_000 + parts[1] * 100 + parts[2]


def hand_written(name: str) -> str:
    """
    The notes written for [name], or "" when the file holds none for it.

    The file's first line names the version its words are for. When it names
    another version, or none, the file is ignored and the trimmed changelog
    is used, so last release's words are not published for this one.
    """
    if not WRITTEN_BY_HAND.exists():
        return ""
    where = WRITTEN_BY_HAND.relative_to(ROOT)
    first, _, rest = WRITTEN_BY_HAND.read_text(encoding="utf-8").strip().partition("\n")
    first = first.strip()
    if first != name:
        said = f"for {first}" if re.fullmatch(r"\d+\.\d+\.\d+", first) else "for no version (its first line names none)"
        print(f"  ! {where} is {said}, not {name}: the changelog stands in for it")
        print(f"    write the notes for {name} under a first line of {name} to say it in your own words")
        return ""
    return rest.strip()


def preflight(name: str, shipping: bool) -> None:
    """
    Checks the tree is clean and the release tag exists.

    The tag has to exist either way, because the changelog is cut against it.
    Only an upload has to be at that commit: the notes for a release can be
    written or posted from a branch that has moved on since.
    """
    if run("git", "status", "--porcelain", "--untracked-files=no"):
        fail("working tree has changes; a release must be a commit that exists")
    run("git", "fetch", "origin", "--tags")
    tag = f"v{name}"
    if not run("git", "tag", "--list", tag):
        fail(f"no tag {tag}. Merge release-please's PR first - it makes the tag.")
    here = run("git", "rev-parse", "HEAD")
    at_tag = here == run("git", "rev-list", "-n1", tag)
    branch = run("git", "rev-parse", "--abbrev-ref", "HEAD")
    if not shipping:
        if branch != "main" and not at_tag:
            fail(f"on {branch}, which is neither main nor {tag}")
        print(f"  ✓ {tag} exists, clean")
        return
    # An upload needs this commit to be the tag, and the tag to be public. A
    # branch name is not checked: checking out a tag detaches HEAD.
    if not at_tag:
        fail(f"{tag} is not this commit; `git checkout {tag}` to ship exactly what it names")
    if not run("git", "branch", "-r", "--contains", tag):
        fail(f"{tag} is on no remote branch; push first, so it names something public")
    print(f"  ✓ {tag}, public, clean")


def section(name: str) -> list[tuple[str, str]]:
    """
    This version's CHANGELOG entries as ("head"|"item", text).

    Kept as kinds, because the two outputs format them differently: Discord
    takes markdown, and Play's "what's new" is plain text.

    release-please writes `<`, `>` and `&` as HTML entities. Neither output is
    HTML, so each entry is given back with the characters themselves.
    """
    if not CHANGELOG.exists():
        fail("no CHANGELOG.md - release-please writes it")
    text = CHANGELOG.read_text(encoding="utf-8")
    # release-please writes "## [0.2.0](compare-url) (date)"; take to the next h2
    start = re.search(rf"^##\s*\[?{re.escape(name)}\]?.*$", text, re.M)
    if not start:
        fail(f"CHANGELOG.md has no section for {name}")
    rest = text[start.end():]
    end = re.search(r"^##\s", rest, re.M)
    body = rest[: end.start()] if end else rest
    out = []
    for line in body.splitlines():
        line = html.unescape(line.strip())
        if line.startswith("###"):
            out.append(("head", line.lstrip("# ").strip()))
        elif line.startswith("*") or line.startswith("-"):
            # drop release-please's trailing links: the commit, and the PR
            # number a squash merge adds before it
            entry = line.lstrip("*- ").strip()
            while (trimmed := re.sub(r"\s*\(\[[^\]]+\]\([^)]*\)\)\s*$", "", entry)) != entry:
                entry = trimmed
            if entry:
                out.append(("item", f"{entry[0].upper()}{entry[1:]}"))
    if not out:
        fail(f"nothing user-facing in {name}; is it all docs and chores?")
    return out


def since(name: str, earlier: str) -> list[tuple[str, str]]:
    """
    Every version's entries from [name] back to but not including [earlier],
    merged under one set of headings.

    For two releases cut close together, which read as one to a listener. The
    entries are merged under one New and one Fixed heading, which is the shape
    `fit` trims.
    """
    text = CHANGELOG.read_text(encoding="utf-8")
    published = re.findall(r"^##\s*\[?([0-9][^\]\s]*)\]?.*$", text, re.M)
    wanted: list[str] = []
    for version in published:
        if version == earlier.lstrip("v"):
            break
        wanted.append(version)
    if not wanted:
        fail(f"CHANGELOG.md has nothing between {earlier} and {name}")
    if wanted[-1] == published[-1] and earlier.lstrip("v") not in published:
        fail(f"CHANGELOG.md has no section for {earlier}")
    under: dict[str, list[str]] = {}
    for version in wanted:
        head = ""
        for kind, entry in section(version):
            if kind == "head":
                head = entry
                under.setdefault(head, [])
            elif head and entry not in under[head]:
                # the same fix can be listed twice when a release is re-cut
                under[head].append(entry)
    # headings in the order the oldest release put them, so New leads even when
    # the newest release is a lone fix. Items stay newest first.
    order: list[str] = []
    for version in reversed(wanted):
        for kind, entry in section(version):
            if kind == "head" and entry not in order:
                order.append(entry)
    out: list[tuple[str, str]] = []
    for head in order:
        out.append(("head", head))
        out.extend(("item", entry) for entry in under[head])
    return out


def fit(
    entries: list[tuple[str, str]],
    limit: int,
    markdown: bool,
    head: str = "",
) -> str:
    """
    As much of the changelog as fits, whole lines only, with a count of what
    was cut.

    A heading whose items were all dropped is dropped with them.
    """
    kept: list[tuple[str, str]] = []
    used = len(head)
    dropped = 0
    for kind, text in entries:
        line = (f"**{text}**" if markdown else f"{text}:") if kind == "head" else f"- {text}"
        # a blank line before a heading that follows a list
        if kind == "head" and kept:
            line = "\n" + line
        if used + len(line) + 1 > limit - 40:
            if kind == "item":
                dropped += 1
            continue
        kept.append((kind, line))
        used += len(line) + 1
    while kept and kept[-1][0] == "head":
        kept.pop()
    out = head + "\n".join(line for _, line in kept)
    if dropped:
        out += f"\n...and {dropped} more."
    return out.rstrip() + "\n"


def webhook() -> str:
    """
    The channel to post in: the webhook address in the DISCORD_WEBHOOK
    environment variable. Keep it out of the repository.
    """
    hook = os.environ.get("DISCORD_WEBHOOK", "").strip()
    if not hook:
        fail("DISCORD_WEBHOOK is not set: the webhook address of the channel the release is announced in")
    if not hook.startswith("https://discord.com/api/webhooks/"):
        fail("DISCORD_WEBHOOK does not look like a Discord webhook url")
    return hook.rstrip("/")


def ask_discord(url: str, message: str, method: str) -> dict:
    """
    One call to the webhook, with what it answered.

    A User-Agent header is required: Discord sits behind Cloudflare, which
    refuses a request without one.
    """
    body = json.dumps({"content": message, "allowed_mentions": {"parse": []}}).encode()
    request = urllib.request.Request(
        url,
        data=body,
        method=method,
        headers={"Content-Type": "application/json", "User-Agent": "andamp-release"},
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as answer:
            if answer.status not in (200, 204):
                fail(f"Discord answered {answer.status}")
            return json.loads(answer.read() or b"{}")
    except urllib.error.HTTPError as bad:
        fail(f"Discord refused it: {bad.code} {bad.read().decode('utf-8', 'replace')[:200]}")
    except urllib.error.URLError as bad:
        fail(f"could not reach Discord: {bad.reason}")
    return {}


def post_to_discord(message: str) -> None:
    """
    Put the changelog in the channel, once.

    Posting has its own flag, apart from `--upload`, because it cannot be
    undone: a bundle on the internal track can be replaced by the next one, a
    message in a channel has been read.

    The message id is not stored; `--edit` asks for it.
    """
    ask_discord(webhook(), message, "POST")
    print(f"  ✓ posted to Discord, {len(message)} chars")


def edit_on_discord(message: str, at: str | None) -> None:
    """
    The post this release already made, rewritten in place, for fixing its
    wording without adding a second message.
    """
    if not at:
        fail("--edit needs --message-id, the number the post's Copy Message Link ends with")
    ask_discord(f"{webhook()}/messages/{at}", message, "PATCH")
    print(f"  ✓ edited message {at} on Discord, {len(message)} chars")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--upload", action="store_true", help="send the bundle to Play's internal track")
    ap.add_argument("--track", default="internal")
    ap.add_argument(
        "--since",
        metavar="VERSION",
        help="cover every release back to this one, for when two were cut close together",
    )
    ap.add_argument(
        "--discord",
        action="store_true",
        help="post the changelog to the Discord channel behind DISCORD_WEBHOOK",
    )
    ap.add_argument(
        "--edit",
        action="store_true",
        help="rewrite this release's Discord post instead of making another one",
    )
    ap.add_argument(
        "--message-id",
        metavar="ID",
        help="which message --edit rewrites; the post's Copy Message Link ends with it",
    )
    args = ap.parse_args()

    name = version_name()
    code = version_code(name)
    print(f"Andamp {name} (version code {code})\n")
    preflight(name, shipping=args.upload)

    lines = since(name, args.since) if args.since else section(name)
    if args.since:
        print(f"  covering {args.since} to {name}")
    written = hand_written(name)
    if written:
        play = written + "\n"
        source = f"{WRITTEN_BY_HAND.relative_to(ROOT)}"
    else:
        play = fit(lines, PLAY_LIMIT, markdown=False)
        source = "the changelog"
    if len(play) > PLAY_LIMIT:
        fail(f"{WRITTEN_BY_HAND.relative_to(ROOT)} is {len(play)} chars; Play takes {PLAY_LIMIT}")
    PLAY_NOTES.parent.mkdir(parents=True, exist_ok=True)
    PLAY_NOTES.write_text(play, encoding="utf-8")
    print(f"  ✓ Play notes from {source}, {len(play)}/{PLAY_LIMIT} chars -> {PLAY_NOTES.relative_to(ROOT)}")
    if not written and not WRITTEN_BY_HAND.exists():
        print(f"    (a store listing is a summary, not a changelog - write {WRITTEN_BY_HAND.name}, its first line {name}, to say it in your own words)")

    tail = DISCORD_TAIL if args.upload else ""
    discord = fit(lines, DISCORD_LIMIT - len(tail), markdown=True, head=f"## Andamp {name}\n\n")
    if tail:
        discord += "\n" + tail
    print(f"  ✓ Discord text, {len(discord)}/{DISCORD_LIMIT} chars\n")
    print("─" * 60)
    print(discord.rstrip())
    print("─" * 60)

    if args.edit and not args.discord:
        fail("--edit rewrites the Discord post, so it goes with --discord")

    # upload first: a bundle on the internal track can be replaced by the next
    # one, and a post in the channel cannot be taken back
    if args.upload:
        if not os.environ.get("PLAY_JSON_KEY"):
            fail("PLAY_JSON_KEY is not set: the path of the Play service account's key file")
        if not os.environ.get("RT_STORE_PASSWORD"):
            fail("RT_STORE_PASSWORD is not set; the bundle would be unsigned")
        print(f"\nUploading to the {args.track} track...")
        done = subprocess.run(
            ["./gradlew", ":app:publishReleaseBundle", f"-Ptrack={args.track}"],
            cwd=ROOT,
            check=False,
        )
        if done.returncode != 0:
            fail("the upload failed, so nothing was posted")

    if args.discord and args.edit:
        edit_on_discord(discord, args.message_id)
    elif args.discord:
        post_to_discord(discord)
    elif not args.upload:
        print("\nNothing sent. --discord posts the above; --upload publishes to Play.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
