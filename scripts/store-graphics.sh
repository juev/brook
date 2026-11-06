#!/usr/bin/env bash
# Renders the store icon and feature graphics from docs/store/*.svg into fastlane/metadata/android.
# ImageMagick drops SVG gradients, so the SVG is drawn by headless Chrome at twice the size and
# scaled down by ImageMagick. Needs Google Chrome and ImageMagick 7.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
chrome=${CHROME:-"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"}
work=$(mktemp -d "${TMPDIR:-/tmp}/brook-store-XXXXXX")
trap 'rm -rf "$work"' EXIT

# render <svg> <width> <height> <png>
render() {
    local svg=$1 width=$2 height=$3 out=$4
    {
        echo '<!doctype html><meta charset="utf-8"><style>html,body{margin:0}svg{display:block}</style>'
        sed -e "1{/^<!--/d;}" "$svg"
    } > "$work/page.html"
    # Headless Chrome does not always exit after the screenshot; the alarm ends it.
    perl -e 'alarm 30; exec @ARGV' "$chrome" --headless=new --disable-gpu --hide-scrollbars \
        --force-device-scale-factor=2 --window-size="$width,$height" \
        --screenshot="$work/shot.png" "file://$work/page.html" > /dev/null 2>&1 || test -s "$work/shot.png"
    mkdir -p "$(dirname "$out")"
    # Play takes a 32-bit PNG icon and a 24-bit feature graphic without alpha; both drop alpha here.
    magick "$work/shot.png" -resize "${width}x${height}!" -background white -alpha remove -alpha off \
        -strip PNG24:"$out"
    rm -f "$work/shot.png"
}

meta=$root/fastlane/metadata/android
render "$root/docs/store/icon.svg" 512 512 "$meta/en-US/images/icon.png"
for lang in en-US ru; do
    render "$root/docs/store/feature-graphic-$lang.svg" 1024 500 "$meta/$lang/images/featureGraphic.png"
done
