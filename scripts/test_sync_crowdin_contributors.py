#!/usr/bin/env python3
"""Tests for the Crowdin contributor sync. Run: python3 -m unittest discover -s scripts -p 'test_*.py'"""

import unittest

from sync_crowdin_contributors import (
    crowdin_handle,
    display_name,
    parse_report,
    render_block,
    split_languages,
)

# Crowdin display name -> BCP-47 tag, as derived from the live androidCode field.
NAME_TO_TAG = {
    "Arabic": "ar-SA",
    "Chinese Simplified": "zh-CN",
    "French": "fr-FR",
    "German": "de-DE",
    "Greek": "el-GR",
    "Italian": "it-IT",
    "Japanese": "ja-JP",
    "Portuguese": "pt-PT",
    "Portuguese, Brazilian": "pt-BR",
    "Spanish": "es-ES",
    "Spanish, Latin America": "es-419",
    "Ukrainian": "uk-UA",
}

ALLOWED = [
    "zh-CN", "fr-FR", "de-DE", "el-GR", "it-IT",
    "ja-JP", "pt-BR", "pt-PT", "es-ES", "uk-UA",
]

# Verbatim from the Crowdin top members export.
REPORT = '''Name,Languages,"Translated (Words)","Target Words","Approved (Words)",Voted,"""+"" votes received","""-"" votes received","Winning (Words)",Joined
"Christophe Coquelet (pawndev)",French,1369,1635,0,24,0,0,1363,"2026-07-26 05:34:21"
"Bobby Chiang (detecti1914)","Chinese Simplified",1345,2606,0,0,0,0,1306,"2026-07-25 20:41:56"
frysee,German,396,398,0,0,0,0,396,"2026-07-25 14:10:33"
Raadon,Spanish,81,91,0,0,0,0,81,"2026-07-27 08:33:34"
"ramsay nieve (ramsay)",Spanish,27,29,0,0,0,0,27,"2026-07-26 09:51:12"
"Dominic Drolet (drolet.dominic)",French,22,22,0,0,18,0,19,"2026-07-25 13:59:12"
"MeEdytor (Edytor_Studio_Core)",Ukrainian,17,17,0,0,0,0,17,"2026-07-25 14:02:57"
"Christian Paolo Ramos (chrip.ramos)",Portuguese,7,7,0,0,0,0,7,"2026-07-30 12:15:30"
"Luke Nance (SkyWalker541)",Spanish,1,1,0,0,0,0,1,"2026-07-25 14:35:25"
'''


class DisplayNameTest(unittest.TestCase):
    def test_real_name_wins_over_handle(self):
        self.assertEqual("Christophe Coquelet", display_name("Christophe Coquelet (pawndev)"))

    def test_bare_handle_is_kept(self):
        self.assertEqual("frysee", display_name("frysee"))

    def test_handle_with_dots(self):
        self.assertEqual("Dominic Drolet", display_name("Dominic Drolet (drolet.dominic)"))


class CrowdinHandleTest(unittest.TestCase):
    def test_handle_comes_from_the_parentheses(self):
        self.assertEqual("pawndev", crowdin_handle("Christophe Coquelet (pawndev)"))

    def test_bare_handle_is_its_own_handle(self):
        self.assertEqual("frysee", crowdin_handle("frysee"))

    def test_case_is_folded(self):
        self.assertEqual("brandonkowalski", crowdin_handle("Brandon Kowalski (BrandonKowalski)"))


class SplitLanguagesTest(unittest.TestCase):
    def setUp(self):
        self.known = NAME_TO_TAG

    def test_single(self):
        self.assertEqual((["French"], ""), split_languages("French", self.known))

    def test_multiple(self):
        self.assertEqual(
            (["French", "Italian"], ""),
            split_languages("French; Italian", self.known),
        )

    # Crowdin separates languages with ";" precisely because a name may contain ",".
    def test_comma_inside_a_language_name(self):
        self.assertEqual(
            (["Portuguese, Brazilian"], ""),
            split_languages("Portuguese, Brazilian", self.known),
        )

    def test_comma_inside_a_name_alongside_another(self):
        self.assertEqual(
            (["Portuguese, Brazilian", "German"], ""),
            split_languages("Portuguese, Brazilian; German", self.known),
        )

    # Verbatim from the export that broke the comma-splitting version.
    def test_every_language_at_once(self):
        cell = (
            "Ukrainian; Portuguese; French; German; Chinese Simplified; "
            "Spanish; Italian; Portuguese, Brazilian; Greek; Japanese"
        )
        self.assertEqual(
            (
                [
                    "Ukrainian", "Portuguese", "French", "German", "Chinese Simplified",
                    "Spanish", "Italian", "Portuguese, Brazilian", "Greek", "Japanese",
                ],
                "",
            ),
            split_languages(cell, self.known),
        )

    def test_plain_portuguese_still_resolves(self):
        self.assertEqual((["Portuguese"], ""), split_languages("Portuguese", self.known))

    def test_unknown_language_is_reported_as_leftover(self):
        found, leftover = split_languages("Klingon", self.known)
        self.assertEqual([], found)
        self.assertEqual("Klingon", leftover)

    def test_known_languages_survive_an_unknown_neighbour(self):
        found, leftover = split_languages("French; Klingon; German", self.known)
        self.assertEqual(["French", "German"], found)
        self.assertEqual("Klingon", leftover)


class ParseReportTest(unittest.TestCase):
    def parse(self, text=REPORT):
        return parse_report(text, NAME_TO_TAG, ALLOWED)

    def test_matches_the_hand_written_credits(self):
        self.assertEqual(
            {
                "zh-CN": ["Bobby Chiang"],
                "fr-FR": ["Christophe Coquelet", "Dominic Drolet"],
                "de-DE": ["frysee"],
                "pt-PT": ["Christian Paolo Ramos"],
                "es-ES": ["Luke Nance", "Raadon", "ramsay nieve"],
                "uk-UA": ["MeEdytor"],
            },
            self.parse(),
        )

    def test_sections_follow_catalog_order(self):
        self.assertEqual(
            ["zh-CN", "fr-FR", "de-DE", "pt-PT", "es-ES", "uk-UA"],
            list(self.parse()),
        )

    def test_contributors_are_alphabetical_case_insensitively(self):
        # "ramsay nieve" sorts after "Raadon" only if case is folded.
        self.assertEqual(["Luke Nance", "Raadon", "ramsay nieve"], self.parse()["es-ES"])

    def test_languages_with_no_contributors_are_absent(self):
        for tag in ("el-GR", "it-IT", "ja-JP", "pt-BR"):
            self.assertNotIn(tag, self.parse())

    def test_zero_word_contributors_are_dropped(self):
        text = REPORT + '"Drive By (driveby)",Italian,0,10,0,0,0,0,0,"2026-07-25 14:35:25"\n'
        self.assertNotIn("it-IT", self.parse(text))

    def test_language_outside_the_catalog_is_skipped(self):
        text = REPORT + '"Someone (someone)",Arabic,50,60,0,0,0,0,50,"2026-07-25 14:35:25"\n'
        parsed = self.parse(text)
        self.assertNotIn("ar-SA", parsed)
        self.assertEqual(self.parse(), parsed)

    def test_excluded_handle_is_dropped(self):
        text = REPORT + (
            '"Brandon Kowalski (BrandonKowalski)","German; Greek",900,900,0,0,0,0,900,'
            '"2026-08-01 10:00:00"\n'
        )
        parsed = self.parse(text)
        self.assertEqual(self.parse(), parsed)
        self.assertNotIn("el-GR", parsed)

    def test_excluded_handle_is_dropped_without_a_real_name(self):
        text = REPORT + 'BRANDONKOWALSKI,Greek,900,900,0,0,0,0,900,"2026-08-01 10:00:00"\n'
        self.assertNotIn("el-GR", self.parse(text))

    def test_a_contributor_in_two_languages_lands_in_both(self):
        text = REPORT + '"Poly Glot (polyglot)","German; Italian",40,40,0,0,0,0,40,"2026-07-25 14:35:25"\n'
        parsed = self.parse(text)
        self.assertEqual(["frysee", "Poly Glot"], parsed["de-DE"])
        self.assertEqual(["Poly Glot"], parsed["it-IT"])

    def test_thousands_separator_in_word_count(self):
        text = REPORT.replace("1369", '"1,369"')
        self.assertIn("Christophe Coquelet", self.parse(text)["fr-FR"])


class RenderBlockTest(unittest.TestCase):
    def test_renders_the_committed_shape(self):
        rendered = render_block({"fr-FR": ["Christophe Coquelet", "Dominic Drolet"]})
        self.assertEqual(
            '  "localization": [\n'
            '    { "language": "fr-FR", "contributors": ["Christophe Coquelet", "Dominic Drolet"] }\n'
            "  ]",
            rendered,
        )

    def test_non_ascii_names_are_not_escaped(self):
        rendered = render_block({"de-DE": ["Jörg Müller"]})
        self.assertIn("Jörg Müller", rendered)


if __name__ == "__main__":
    unittest.main()
