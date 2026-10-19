#!/usr/bin/env python3
"""A test RSS feed on disk for a local freshgo.

  dev-feed.py init <dir> [count]   writes <dir>/feed.xml with <count> entries (default 30)
  dev-feed.py add <dir> [count]    adds <count> new entries on top (default 1)

Serve <dir> with `python3 -m http.server` and subscribe freshgo to it.
"""
import email.utils
import pathlib
import sys
import time

def item(n: int, ts: float) -> str:
    date = email.utils.formatdate(ts)
    return f"""  <item>
    <title>Entry {n}</title>
    <link>https://example.org/entries/{n}</link>
    <guid isPermaLink="false">brook-dev-{n}</guid>
    <pubDate>{date}</pubDate>
    <description><![CDATA[<p>Text of entry {n}. <a href="https://example.org/">A link</a>.</p>
<p><img src="https://picsum.photos/seed/brook{n}/640/360" alt="picture {n}"></p>]]></description>
  </item>
"""

def write(path: pathlib.Path, numbers: list[int]) -> None:
    now = time.time()
    items = "".join(item(n, now - (max(numbers) - n) * 600) for n in sorted(numbers, reverse=True))
    path.write_text(f"""<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0"><channel>
  <title>Brook dev feed</title>
  <link>https://example.org/</link>
  <description>Entries for testing Brook</description>
{items}</channel></rss>
""")

def main() -> None:
    command, directory = sys.argv[1], pathlib.Path(sys.argv[2])
    count = int(sys.argv[3]) if len(sys.argv) > 3 else (30 if command == "init" else 1)
    directory.mkdir(parents=True, exist_ok=True)
    state = directory / "count"
    if command == "init":
        total = count
    elif command == "add":
        total = int(state.read_text()) + count
    else:
        sys.exit(__doc__)
    state.write_text(str(total))
    write(directory / "feed.xml", list(range(1, total + 1)))
    print(f"{directory / 'feed.xml'}: {total} entries")

if __name__ == "__main__":
    main()
