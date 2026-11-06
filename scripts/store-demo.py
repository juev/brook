#!/usr/bin/env python3
"""Demo feeds for the store screenshots: four feeds with texts and pictures made here, so the
screenshots show nobody else's content.

  store-demo.py <dir> <base url of pictures>

writes <dir>/<feed>/feed.xml, <dir>/<feed>/icon.png and <dir>/img/*.png. Serve <dir> with
`python3 -m http.server 8099` on all addresses and subscribe freshgo to
http://127.0.0.1:8099/<feed>/feed.xml; pass http://127.0.0.1:8099 as the base: freshgo serves
the pictures to the app through its own proxy. The page of every feed links its icon. Pictures are drawn by ImageMagick from flat SVG shapes.
"""
import email.utils
import html
import pathlib
import random
import subprocess
import sys
import time

FEEDS = {
    "field-notes": {
        "title": "Field Notes",
        "color": "#3C7A4A",
        "letter": "F",
        "entries": [
            ("The first frost on the meadow", 0.4,
             ["The meadow was silver at seven. Every blade of grass held a rim of ice, and the path we "
              "walked yesterday had turned into a line of dark footprints.",
              "By ten the sun had taken it all back. Only the shade under the alders stayed white until noon."]),
            ("Where the herons wait", 5,
             ["Three grey herons stand in the shallows below the mill every morning, a few metres apart, "
              "as if they had agreed on the distance.",
              "They do not move when a walker passes on the bank. A dog is another matter."]),
            ("Counting oaks along the old road", 27,
             ["The old road to the village is lined with oaks planted, the locals say, the year the school "
              "was built. We counted forty-one.",
              "Two have fallen since spring. Their stumps already carry a crop of bright orange fungus."]),
            ("A week of fog", 52,
             ["Fog came in on Monday and did not lift until Sunday. The far bank of the river disappeared, "
              "then the near one.",
              "Sound carries strangely in it: a tractor two fields away seemed to be just behind the hedge."]),
        ],
    },
    "kitchen-shelf": {
        "title": "The Kitchen Shelf",
        "color": "#B5562F",
        "letter": "K",
        "entries": [
            ("Apple cake with brown butter", 1.5,
             ["Brown the butter until it smells of hazelnuts, let it cool, and only then beat it with the "
              "sugar. That is the whole secret.",
              "Slice the apples thin and press them into the batter in rows. Bake at 180 °C for 45 minutes."]),
            ("A pot of beans for the whole week", 9,
             ["Soak a kilo of white beans overnight, then simmer them with bay leaves, garlic and a strip "
              "of lemon peel for two hours.",
              "Monday they go into soup, Wednesday onto toast with greens, Friday into a gratin."]),
            ("Rye bread, the slow way", 30,
             ["A rye starter takes five days to wake up. After that it needs a spoon of flour and a little "
              "water every evening, and very little attention.",
              "The dough is sticky and will not hold a shape; bake it in a tin and let it rest a day."]),
        ],
    },
    "small-workshop": {
        "title": "Small Workshop",
        "color": "#6B5B95",
        "letter": "W",
        "entries": [
            ("Sharpening a plane iron by hand", 3,
             ["A coarse stone to flatten the back, a medium one for the bevel, a fine one to finish. "
              "Ten minutes, and the plane takes shavings you can read through.",
              "The mistake everyone makes once: rocking the iron on the stone and rounding the edge."]),
            ("A shelf from one board of pine", 26,
             ["One board, two metres long, makes a wall shelf with two sides, two shelves and a back rail. "
              "No screws show from the front.",
              "Housing joints hold it together; a little glue and a wooden wedge in each."]),
            ("Oil or wax for a kitchen table", 50,
             ["Oil soaks in and can be renewed with a rag in five minutes. Wax looks better on the first "
              "day and worse after the first hot cup.",
              "For a table that is used every day, oil wins."]),
        ],
    },
    "night-sky": {
        "title": "Night Sky Log",
        "color": "#1F4E79",
        "letter": "N",
        "entries": [
            ("Jupiter and the Moon, side by side", 2,
             ["Tonight the Moon passes three degrees from Jupiter. Binoculars show four tiny points beside "
              "the planet: its largest moons, in a line.",
              "Look east after nine; the pair will be high in the south by midnight."]),
            ("How to find the Andromeda galaxy", 28,
             ["Start from the square of Pegasus, hop two stars along the chain to the left, then turn up. "
              "A faint oval smudge is the farthest thing you can see without a telescope.",
              "Its light left it two and a half million years ago."]),
            ("Meteors after midnight", 49,
             ["The shower peaks after midnight, when our side of the Earth faces the direction of its orbit. "
              "Expect one meteor every few minutes far from city lights.",
              "Lie down, give your eyes twenty minutes, and do not look at the phone."]),
        ],
    },
}

PALETTES = [
    ("#9FD3E6", "#F6E7B0", ["#5E9E6E", "#3F7D54", "#2C5E3F"]),
    ("#F3C9A4", "#FFF1D6", ["#C77D58", "#9E5B3C", "#6E3B26"]),
    ("#C9C3E6", "#FBEFD5", ["#8C7FBF", "#6B5B95", "#4A3D70"]),
    ("#13294B", "#F2F2E8", ["#2A4D7A", "#1F3A5F", "#122640"]),
    ("#BFE0D2", "#FFF7DA", ["#7FB59A", "#5A9478", "#3B7058"]),
]


def picture(path: pathlib.Path, seed: int) -> None:
    rng = random.Random(seed)
    sky, sun, hills = PALETTES[seed % len(PALETTES)]
    width, height = 1280, 720
    shapes = [f'<rect width="{width}" height="{height}" fill="{sky}"/>',
              f'<circle cx="{rng.randint(200, 1080)}" cy="{rng.randint(110, 260)}" r="{rng.randint(50, 90)}" fill="{sun}"/>']
    for layer, color in enumerate(hills):
        base = 330 + layer * 120
        points = [(x, base + rng.randint(-70, 70)) for x in range(-200, width + 400, 320)]
        d = f"M{points[0][0]},{points[0][1]}"
        for (x0, y0), (x1, y1) in zip(points, points[1:]):
            d += f" Q{x0 + 160},{(y0 + y1) // 2 + rng.randint(-60, 60)} {x1},{y1}"
        d += f" L{width + 400},{height} L-200,{height} Z"
        shapes.append(f'<path d="{d}" fill="{color}"/>')
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}">{"".join(shapes)}</svg>'
    path.with_suffix(".svg").write_text(svg)
    subprocess.run(["magick", "-density", "96", path.with_suffix(".svg"), "-quality", "90", path], check=True)
    path.with_suffix(".svg").unlink()


def icon(path: pathlib.Path, color: str, letter: str) -> None:
    subprocess.run(["magick", "-size", "128x128", f"xc:{color}",
                    "-font", "/System/Library/Fonts/Supplemental/Arial Bold.ttf", "-pointsize", "84",
                    "-fill", "white", "-gravity", "center", "-annotate", "+0+4", letter, path], check=True)


def main() -> None:
    root, base = pathlib.Path(sys.argv[1]), sys.argv[2].rstrip("/")
    (root / "img").mkdir(parents=True, exist_ok=True)
    now = time.time()
    seed = 0
    for slug, feed in FEEDS.items():
        directory = root / slug
        directory.mkdir(exist_ok=True)
        icon(directory / "icon.png", feed["color"], feed["letter"])
        items = []
        for title, hours_ago, paragraphs in feed["entries"]:
            seed += 1
            picture(root / "img" / f"{seed}.png", seed)
            body = f'<p><img src="{base}/img/{seed}.png" alt=""></p>' + "".join(f"<p>{html.escape(p)}</p>" for p in paragraphs)
            items.append(f"""  <item>
    <title>{html.escape(title)}</title>
    <link>http://10.0.2.2:8099/{slug}/{seed}</link>
    <guid isPermaLink="false">brook-store-{slug}-{seed}</guid>
    <pubDate>{email.utils.formatdate(now - hours_ago * 3600)}</pubDate>
    <description><![CDATA[{body}]]></description>
  </item>
""")
        (directory / "feed.xml").write_text(f"""<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0"><channel>
  <title>{feed["title"]}</title>
  <link>http://127.0.0.1:8099/{slug}/</link>
  <description>{feed["title"]}</description>
  <image><url>http://127.0.0.1:8099/{slug}/icon.png</url><title>{feed["title"]}</title><link>http://127.0.0.1:8099/{slug}/</link></image>
{"".join(items)}</channel></rss>
""")
        (directory / "index.html").write_text(
            f'<!doctype html><title>{feed["title"]}</title><link rel="icon" href="icon.png">')
        print(directory / "feed.xml")


if __name__ == "__main__":
    main()
