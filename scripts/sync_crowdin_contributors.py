#!/usr/bin/env python3
"""Refresh the localization contributors in credits.json from the Crowdin top members report.

Crowdin is the source of truth for translators: the localization block is regenerated wholly,
so a contributor hand-added here will be dropped on the next run.
"""

import argparse
import csv
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
API = "https://api.crowdin.com/api/v2"
CATALOG = os.path.join(ROOT, "app/src/main/java/dev/cannoli/scorza/i18n/LanguageCatalog.kt")
CREDITS = os.path.join(ROOT, "credits.json")


def request(url, token=None, method="GET", payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as response:
            return json.loads(response.read().decode())
    except urllib.error.HTTPError as err:
        sys.exit(f"Crowdin API {method} {url} failed: {err.code} {err.read().decode(errors='replace')}")


def catalog_tags():
    """Ordered BCP-47 tags offered by LanguageCatalog, minus English."""
    text = open(CATALOG, encoding="utf-8").read()
    tags = re.findall(r'LanguageOption\("([^"]+)"', text)
    if not tags:
        sys.exit(f"no LanguageOption entries found in {CATALOG}")
    return [tag for tag in tags if tag != "en"]


def language_map():
    """Crowdin display name -> BCP-47 tag, derived from Crowdin's own androidCode."""
    mapping = {}
    offset = 0
    while True:
        page = request(f"{API}/languages?limit=500&offset={offset}")
        rows = page.get("data", [])
        for row in rows:
            language = row["data"]
            code = language.get("androidCode")
            if code:
                mapping[language["name"]] = code.replace("-r", "-", 1)
        if len(rows) < 500:
            return mapping
        offset += len(rows)


def generate_report(token, project_id):
    body = {"name": "top-members", "schema": {"unit": "words", "format": "csv"}}
    created = request(f"{API}/projects/{project_id}/reports", token, "POST", body)
    return created["data"]["identifier"]


def wait_for_report(token, project_id, report_id, timeout=300):
    deadline = time.time() + timeout
    while time.time() < deadline:
        status = request(f"{API}/projects/{project_id}/reports/{report_id}", token)["data"]
        if status["status"] == "finished":
            return
        if status["status"] in ("canceled", "failed"):
            sys.exit(f"Crowdin report {report_id} ended as {status['status']}")
        time.sleep(5)
    sys.exit(f"Crowdin report {report_id} did not finish within {timeout}s")


def download_report(token, project_id, report_id):
    url = request(f"{API}/projects/{project_id}/reports/{report_id}/download", token)["data"]["url"]
    with urllib.request.urlopen(url) as response:
        return response.read().decode("utf-8-sig")


def split_languages(cell, known_names):
    """Split a Languages cell into Crowdin language names.

    Longest match first, because several names contain the separator itself
    ("Portuguese, Brazilian", "Spanish, Latin America").
    """
    found = []
    rest = cell.strip()
    while rest:
        match = next(
            (name for name in known_names if rest == name or rest.startswith(name + ", ")),
            None,
        )
        if match is None:
            return found, rest
        found.append(match)
        rest = rest[len(match):].lstrip(", ").strip()
    return found, ""


def display_name(raw):
    """Real name when Crowdin has one, otherwise the bare handle."""
    return raw.split(" (")[0].strip() if " (" in raw else raw.strip()


def parse_report(csv_text, name_to_tag, allowed_tags):
    reader = csv.DictReader(io.StringIO(csv_text))
    for column in ("Name", "Languages"):
        if column not in (reader.fieldnames or []):
            sys.exit(f"Crowdin report is missing the {column!r} column; got {reader.fieldnames}")
    words_column = next(
        (c for c in reader.fieldnames if c.startswith("Translated")),
        None,
    )
    if words_column is None:
        sys.exit(f"Crowdin report has no Translated column; got {reader.fieldnames}")

    known_names = sorted(name_to_tag, key=len, reverse=True)
    by_tag = {}
    skipped = set()
    for row in reader:
        try:
            translated = int((row.get(words_column) or "0").replace(",", ""))
        except ValueError:
            translated = 0
        if translated <= 0:
            continue
        languages, leftover = split_languages(row["Languages"], known_names)
        if leftover:
            sys.exit(f"unrecognized Crowdin language in {row['Languages']!r} (stuck at {leftover!r})")
        for language in languages:
            tag = name_to_tag[language]
            if tag not in allowed_tags:
                skipped.add(language)
                continue
            by_tag.setdefault(tag, set()).add(display_name(row["Name"]))

    for language in sorted(skipped):
        print(f"skipping {language}: not offered by LanguageCatalog")

    return {
        tag: sorted(by_tag[tag], key=str.lower)
        for tag in allowed_tags
        if by_tag.get(tag)
    }


def render_block(localization):
    lines = ['  "localization": [']
    entries = []
    for tag, contributors in localization.items():
        names = ", ".join(json.dumps(name, ensure_ascii=False) for name in contributors)
        entries.append(f'    {{ "language": "{tag}", "contributors": [{names}] }}')
    lines.append(",\n".join(entries))
    lines.append("  ]")
    return "\n".join(lines)


def update_credits(localization):
    """Rewrite only the localization block so the hand-formatted file keeps its shape."""
    text = open(CREDITS, encoding="utf-8").read()
    block = render_block(localization)
    updated, count = re.subn(
        r'  "localization": \[.*?\n  \]',
        lambda _: block,
        text,
        flags=re.DOTALL,
    )
    if count != 1:
        sys.exit(f'expected exactly one "localization" block in credits.json, found {count}')
    if updated == text:
        return False
    open(CREDITS, "w", encoding="utf-8").write(updated)
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--from-csv", help="parse a saved report instead of calling the API")
    parser.add_argument("--dry-run", action="store_true", help="print the block without writing")
    args = parser.parse_args()

    if args.from_csv:
        csv_text = open(args.from_csv, encoding="utf-8-sig").read()
    else:
        token = os.environ.get("CROWDIN_PERSONAL_TOKEN")
        project_id = os.environ.get("CROWDIN_PROJECT_ID")
        if not token or not project_id:
            sys.exit("set CROWDIN_PERSONAL_TOKEN and CROWDIN_PROJECT_ID, or pass --from-csv")
        report_id = generate_report(token, project_id)
        wait_for_report(token, project_id, report_id)
        csv_text = download_report(token, project_id, report_id)

    localization = parse_report(csv_text, language_map(), catalog_tags())
    if not localization:
        sys.exit("Crowdin returned no contributors for any offered language; refusing to empty credits.json")

    if args.dry_run:
        print(render_block(localization))
        return

    total = sum(len(names) for names in localization.values())
    if update_credits(localization):
        print(f"Updated credits.json: {total} contributors across {len(localization)} languages")
    else:
        print(f"credits.json already current: {total} contributors across {len(localization)} languages")


if __name__ == "__main__":
    main()
