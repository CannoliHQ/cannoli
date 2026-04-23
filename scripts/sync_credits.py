#!/usr/bin/env python3
"""Sync credits.json to CreditsOverlay.kt, taskfile.yml, and release.yml."""

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main():
    credits = json.loads((ROOT / "credits.json").read_text(encoding="utf-8"))
    sync_credits_overlay(credits)
    sync_taskfile(credits)
    sync_workflow(credits)
    print("Synced credits.json \u2192 CreditsOverlay.kt, taskfile.yml, release.yml")


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
