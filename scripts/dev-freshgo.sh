#!/usr/bin/env bash
# Starts a throwaway freshgo for Brook development: user alice (password brook-dev-pw),
# one local test feed, listening on 0.0.0.0:8080 so the emulator reaches it as 10.0.2.2:8080.
#
#   scripts/dev-freshgo.sh <path to freshgo binary> [state dir]
#
# New entries: scripts/dev-feed.py add <state dir>/feed [n], then wait for the refresh (30s).
set -euo pipefail

freshgo=$1
state=${2:-${TMPDIR:-/tmp}/brook-freshgo}
here=$(dirname "$0")

mkdir -p "$state"
export FRESHGO_DATABASE_URL="sqlite://$state/freshgo.sqlite"
export FRESHGO_FETCH_ALLOWLIST="127.0.0.1:8099"

if [ ! -f "$state/freshgo.sqlite" ]; then
    python3 "$here/dev-feed.py" init "$state/feed" 30
fi

python3 -m http.server 8099 --bind 127.0.0.1 --directory "$state/feed" >"$state/feed-server.log" 2>&1 &
feed_server=$!
trap 'kill $feed_server' EXIT
sleep 1

# Not `user list | grep -q`: with pipefail the early exit of grep fails the pipeline by SIGPIPE.
users=$("$freshgo" user list 2>/dev/null || true)
if ! grep -q '^alice:' <<<"$users"; then
    echo brook-dev-pw | "$freshgo" user create alice
    "$freshgo" feed add -user alice -category Dev http://127.0.0.1:8099/feed.xml
fi

"$freshgo" serve -listen 0.0.0.0:8080 -refresh-interval 30s
