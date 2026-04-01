#!/usr/bin/env python3
"""Sync credits.json to CreditsOverlay.kt, README.md, taskfile.yml, and release.yml."""

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main():
    credits = json.loads((ROOT / "credits.json").read_text(encoding="utf-8"))
    sync_credits_overlay(credits)
    sync_readme(credits)
    sync_taskfile(credits)
    sync_workflow(credits)
    print("Synced credits.json \u2192 CreditsOverlay.kt, README.md, taskfile.yml, release.yml")


def sync_credits_overlay(credits):
    path = ROOT / "app/src/main/java/dev/cannoli/scorza/ui/components/CreditsOverlay.kt"
    text = path.read_text(encoding="utf-8")

    entries = []
    for item in credits["inspiration"]:
        entries.append(f'    CreditEntry("{item["name"]}", "{item["detail"]}")')
    for item in credits["fonts"]:
        entries.append(f'    CreditEntry("{item["name"]}", "{item["license"]}")')
    for item in credits["libraries"]:
        entries.append(f'    CreditEntry("{item["name"]}", "{item["license"]}")')
    for item in credits["cores"]:
        entries.append(f'    CreditEntry("{item["name"]}", "{item["license"]}")')
    for item in credits["shaders"]:
        entries.append(f'    CreditEntry("{item["shader"]} by {item["name"]}", "{item["license"]}")')

    block = ",\n".join(entries) + ","

    def replace_credits(m):
        return m.group(1) + block + "\n)"

    text = re.sub(
        r'(val CREDITS: List<CreditEntry> = listOf\(\n).*?\n\)',
        replace_credits,
        text,
        flags=re.DOTALL,
    )
    path.write_text(text, encoding="utf-8")


def sync_readme(credits):
    path = ROOT / "README.md"
    text = path.read_text(encoding="utf-8")

    def make_table(headers, rows):
        widths = [len(h) for h in headers]
        for row in rows:
            for i, cell in enumerate(row):
                widths[i] = max(widths[i], len(cell))
        lines = []
        lines.append("| " + " | ".join(h.ljust(w) for h, w in zip(headers, widths)) + " |")
        lines.append("|" + "|".join("-" * (w + 2) for w in widths) + "|")
        for row in rows:
            lines.append("| " + " | ".join(c.ljust(w) for c, w in zip(row, widths)) + " |")
        return "\n".join(lines)

    tables = {
        "Cores": make_table(
            ["Name", "License"],
            [[c["name"], c["license"]] for c in credits["cores"]],
        ),
        "Shaders": make_table(
            ["Name", "License"],
            [[s["shader"], s["license"]] for s in credits["shaders"]],
        ),
        "Libraries": make_table(
            ["Name", "License"],
            [[lib["name"], lib["license"]] for lib in credits["libraries"]],
        ),
        "Fonts": make_table(
            ["Name", "License"],
            [[font["name"], font["license"]] for font in credits["fonts"]],
        ),
    }

    for section, table in tables.items():
        pattern = rf'(### {re.escape(section)}\n\n)\|[^\n]*\n\|[^\n]*\n(?:\|[^\n]*\n)*'

        def make_replacer(t):
            return lambda m: m.group(1) + t + "\n"

        text = re.sub(pattern, make_replacer(table), text)

    path.write_text(text, encoding="utf-8")


def sync_taskfile(credits):
    path = ROOT / "taskfile.yml"
    text = path.read_text(encoding="utf-8")

    ids = "\n".join(f"        - {c['id']}" for c in credits["cores"])

    def replace_cores(m):
        return m.group(1) + ids + "\n"

    text = re.sub(
        r'(      CORES:\n)(?:        - .*\n)+',
        replace_cores,
        text,
    )
    path.write_text(text, encoding="utf-8")


def sync_workflow(credits):
    path = ROOT / ".github/workflows/release.yml"
    text = path.read_text(encoding="utf-8")

    ids = [c["id"] for c in credits["cores"]]
    lines = []
    line = "   "
    for id_ in ids:
        if len(line) + 1 + len(id_) > 80:
            lines.append(line)
            line = "    " + id_
        else:
            line += " " + id_
    lines.append(line)
    block = "\n".join(lines)

    def replace_cores(m):
        return m.group(1) + block + "\n"

    text = re.sub(
        r'(  CORES: >-\n)(?:    .*\n)+',
        replace_cores,
        text,
    )
    path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
