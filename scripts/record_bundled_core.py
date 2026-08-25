#!/usr/bin/env python3
"""Record what a bundled core actually is, at the moment it is bundled.

The buildbot keeps no history: one unversioned `latest` directory, and stable releases carry no
cores at all. So Cannoli cannot ask for a build by name, and instead records the build identity it
received. `task cores` calls this after each successful fetch.

Two fields, both only knowable here:

  etag     what the server called that build, so the app can ask "changed?" instead of guessing
           from a download date and re-fetching a core it already has

  built    the date the buildbot wrote that binary, from Last-Modified. Deliberately not the
           core's display_version: that field is hand-maintained upstream and moves on a different
           clock, so gambatte has declared v0.5.0 since 2022 while its binary is rebuilt nightly.
           The build date is what actually distinguishes two installs.

Only cores actually fetched are recorded. `task cores` skips one already on disk, and stamping that
with today's etag would claim we hold a build we do not, which would then answer 304 forever and
never update.
"""

import argparse
import email.utils
import pathlib
import sys

FIELDS = 4


def iso_date(last_modified: str) -> str:
    """Last-Modified is RFC 1123. Only the day is shown, so the wall clock does not matter."""
    if not last_modified.strip():
        return "?"
    try:
        return email.utils.parsedate_to_datetime(last_modified.strip()).date().isoformat()
    except (TypeError, ValueError):
        return "?"


def upsert(manifest: pathlib.Path, abi: str, core: str, etag: str, built: str) -> None:
    header = [
        "# What each bundled core was when it was bundled, written by scripts/record_bundled_core.py.",
        "#",
        "# The etag lets the app ask the buildbot whether a build changed rather than re-downloading",
        "# to find out. The date is when that binary was built, which is the only honest way to say",
        "# which build a core from the APK actually is.",
        "#",
        "# <abi> <core> <etag> <built>",
    ]
    rows: dict[tuple[str, str], str] = {}
    if manifest.is_file():
        for line in manifest.read_text().splitlines():
            if not line.strip() or line.startswith("#"):
                continue
            parts = line.split(None, FIELDS - 1)
            if len(parts) == FIELDS:
                rows[(parts[0], parts[1])] = f"{parts[2]} {parts[3]}"
    rows[(abi, core)] = f"{etag} {built}"

    body = [f"{abi} {core} {rest}" for (abi, core), rest in sorted(rows.items())]
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text("\n".join(header + body) + "\n")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--abi", required=True)
    ap.add_argument("--core", required=True, help="core id without the _libretro suffix")
    ap.add_argument("--etag", required=True)
    ap.add_argument("--last-modified", default="")
    args = ap.parse_args()

    etag = args.etag.strip()
    if not etag:
        # No validator means nothing to record. The app falls back to an unconditional fetch, which
        # is correct but slow, rather than to a wrong answer.
        print(f"NOETAG {args.abi}/{args.core}", file=sys.stderr)
        return 0

    built = iso_date(args.last_modified)
    upsert(pathlib.Path(args.manifest), args.abi, f"{args.core}_libretro", etag, built)
    print(f"RECORD {args.abi}/{args.core} {etag} {built}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
