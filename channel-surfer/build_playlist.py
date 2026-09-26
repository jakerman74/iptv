#!/usr/bin/env python3
"""Build a small M3U from the public IPTV-org US playlist and local rules."""
import json
import re
import urllib.request
from pathlib import Path

BASE = Path(__file__).resolve().parent
RULES = BASE / "rules.json"
OUTPUT = BASE / "lineup.m3u"
MAX_BYTES = 25_000_000


def entries(source: str):
    pending = []
    for line in source.splitlines():
        line = line.strip()
        if line.startswith("#EXTINF:"):
            pending = [line]
        elif line.startswith("#") and pending:
            pending.append(line)
        elif line.startswith("http://") or line.startswith("https://"):
            if pending:
                yield pending, line
            pending = []


def build(source: str, rules: dict) -> tuple[str, int]:
    categories = set(rules["categories"])
    include = set(rules["include_ids"])
    exclude = set(rules["exclude_ids"])
    exclude_urls = set(rules["exclude_urls"])
    seen = set()
    result = ["#EXTM3U"]
    count = 0
    for metadata, url in entries(source):
        if not url.startswith("https://") or url in exclude_urls or url in seen:
            continue
        header = metadata[0]
        id_match = re.search(r'\btvg-id="([^"]*)"', header)
        group_match = re.search(r'\bgroup-title="([^"]*)"', header)
        channel_id = id_match.group(1) if id_match else ""
        groups = set(group_match.group(1).split(";")) if group_match else set()
        if channel_id in exclude:
            continue
        if channel_id not in include and not groups.intersection(categories):
            continue
        seen.add(url)
        result.extend(metadata)
        result.append(url)
        count += 1
    return "\n".join(result) + "\n", count


def main():
    rules = json.loads(RULES.read_text(encoding="utf-8"))
    url = rules["source"]
    if not url.startswith("https://"):
        raise SystemExit("Source must use HTTPS")
    request = urllib.request.Request(url, headers={"User-Agent": "ChannelSurfer/0.1"})
    with urllib.request.urlopen(request, timeout=30) as response:
        data = response.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise SystemExit("Source playlist is too large")
    output, count = build(data.decode("utf-8-sig"), rules)
    if count == 0:
        raise SystemExit("No matching channels; refusing to overwrite the existing list")
    OUTPUT.write_text(output, encoding="utf-8")
    print(f"Wrote {count} channels to {OUTPUT}")


if __name__ == "__main__":
    main()
