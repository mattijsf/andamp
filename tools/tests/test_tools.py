# SPDX-License-Identifier: GPL-3.0-or-later

"""
Tests for the release tooling's decisions.

    python3 -m unittest discover -s tools/tests

They cover a version code Play has already seen, release notes that end on a
heading with nothing under it, a changelog entry with HTML entities in it, and
a commit that reaches the changelog and ships nothing. Nothing here touches
git, Gradle or a network, and the standard library is all it needs.
"""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

TOOLS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOLS))

import release  # noqa: E402


def load(name: str):
    """A script whose file name is not a module name: commit-type.py."""
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), TOOLS / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


commit_type = load("commit-type")


class VersionCode(unittest.TestCase):
    """Major * 10000 + minor * 100 + patch, as in app/build.gradle.kts: 1.2.3 is 10203."""

    def test_a_name_is_one_climbing_number(self):
        self.assertEqual(10203, release.version_code("1.2.3"))
        self.assertEqual(403, release.version_code("0.4.3"))

    def test_a_short_name_counts_its_missing_parts_as_zero(self):
        self.assertEqual(20000, release.version_code("2"))
        self.assertEqual(10500, release.version_code("1.5"))

    def test_a_suffix_does_not_change_the_number(self):
        self.assertEqual(10203, release.version_code("1.2.3-rc1"))

    def test_a_later_name_is_a_larger_number(self):
        names = ["0.9.9", "0.10.0", "0.10.1", "1.0.0"]
        codes = [release.version_code(name) for name in names]
        self.assertEqual(sorted(codes), codes)


class Fit(unittest.TestCase):
    ENTRIES = [("head", "Features"), ("item", "one"), ("item", "two"), ("head", "Fixes"), ("item", "three")]

    def test_everything_fits_when_there_is_room(self):
        out = release.fit(self.ENTRIES, 500, markdown=False)
        self.assertEqual("Features:\n- one\n- two\n\nFixes:\n- three\n", out)

    def test_markdown_bolds_a_heading(self):
        self.assertTrue(release.fit(self.ENTRIES, 500, markdown=True).startswith("**Features**\n"))

    def test_what_is_cut_is_counted_and_no_heading_is_left_with_nothing_under_it(self):
        out = release.fit(self.ENTRIES, 62, markdown=False)
        self.assertIn("- one", out)
        self.assertNotIn("Fixes", out)
        self.assertIn("more.", out)

    def test_it_never_runs_past_the_limit(self):
        many = [("head", "Features")] + [("item", f"change number {n} with words in it") for n in range(60)]
        self.assertLessEqual(len(release.fit(many, 500, markdown=False)), 500)


class Section(unittest.TestCase):
    """One version's entries, read from a changelog as release-please writes it."""

    CHANGELOG = """# Changelog

## [9.9.9](https://example.org/compare/v9.9.8...v9.9.9) (2026-01-01)


### New

* load a list from LIST &gt; LOAD LIST, a &lt;name&gt; &amp; more ([#1](https://example.org/issues/1)) ([abc1234](https://example.org/commit/abc1234))

## [9.9.8](https://example.org/compare/v9.9.7...v9.9.8) (2025-12-31)


### Fixed

* something older ([def5678](https://example.org/commit/def5678))
"""

    def section(self, name: str):
        with tempfile.TemporaryDirectory() as folder:
            changelog = Path(folder) / "CHANGELOG.md"
            changelog.write_text(self.CHANGELOG, encoding="utf-8")
            with mock.patch.object(release, "CHANGELOG", changelog):
                return release.section(name)

    def test_it_holds_one_version_without_the_trailing_links(self):
        self.assertEqual(
            [("head", "New"), ("item", "Load a list from LIST > LOAD LIST, a <name> & more")],
            self.section("9.9.9"),
        )

    def test_no_html_entity_reaches_either_text(self):
        entries = self.section("9.9.9")
        for markdown in (False, True):
            self.assertNotIn("&gt;", release.fit(entries, 500, markdown=markdown))


class CommitTypes(unittest.TestCase):
    def test_main_sources_and_built_skins_ship(self):
        for path in (
            "app/src/main/java/nl/mattix/andamp/MainActivity.kt",
            "core/dsp/src/main/kotlin/nl/mattix/andamp/core/dsp/GraphEngine.kt",
            "skin/dist/AndAmp Dark.wsz",
            "skin/dist/andamp-dark-template.zip",
        ):
            self.assertTrue(commit_type.SHIPS.search(path), path)

    def test_tests_tools_docs_and_previews_do_not(self):
        for path in (
            "app/src/test/java/nl/mattix/andamp/FooTest.kt",
            "tools/release.py",
            "docs/extend.md",
            "skin/dist/preview/dark.png",
            "build.gradle.kts",
        ):
            self.assertFalse(commit_type.SHIPS.search(path), path)

    def test_only_the_three_types_reach_the_changelog(self):
        for subject in ("feat: a", "fix(player): b", "change!: c", "FEAT: d"):
            self.assertTrue(commit_type.TELLS.match(subject), subject)
        for subject in ("chore: a", "docs: b", "refactor(x): c", "feature: d", "a fix: e"):
            self.assertFalse(commit_type.TELLS.match(subject), subject)


if __name__ == "__main__":
    unittest.main()
