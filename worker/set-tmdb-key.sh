#!/bin/bash
# Stores the TMDB key as the worker secret TMDB_API_KEY: never on disk, never in the APK, never in argv.
# On themoviedb.org → Settings → API, copy either the "API Read Access Token" (preferred) or the "API Key",
# then run ./set-tmdb-key.sh. It reads the clipboard on a Mac (else stdin), checks the key with TMDB, hands it to
# wrangler and clears the clipboard. The worker tells the two forms apart the same way: 32 hex = v3 key.
set -euo pipefail
FROM_CLIPBOARD=
if [ -t 0 ] && command -v pbpaste > /dev/null; then
    KEY=$(pbpaste); FROM_CLIPBOARD=1
elif [ -t 0 ]; then
    read -rsp 'TMDB API key or read access token: ' KEY; echo
else
    KEY=$(cat)
fi
KEY=$(printf %s "$KEY" | tr -d '[:space:]')

# Check the shape first so a wrong clipboard (a password, a URL) is never sent anywhere.
API=https://api.themoviedb.org/3/configuration
if [[ $KEY =~ ^[0-9a-f]{32}$ ]]; then
    CFG="url = \"$API?api_key=$KEY\""
elif [[ $KEY =~ ^eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$ ]]; then
    CFG=$(printf 'url = "%s"\nheader = "Authorization: Bearer %s"' "$API" "$KEY")
else
    echo "That doesn't look like a TMDB key or read access token. Copy it again from themoviedb.org → Settings → API."
    exit 1
fi

code=$(printf '%s\n' "$CFG" | curl -s -o /dev/null -w '%{http_code}' -K -)
if [ "$code" != 200 ]; then
    echo "TMDB rejected it (HTTP $code). Copy it again."
    exit 1
fi
echo "TMDB accepted the key."

cd "$(dirname "$0")"
printf %s "$KEY" | ${NPX:-npx} wrangler@4 secret put TMDB_API_KEY
if [ -n "$FROM_CLIPBOARD" ]; then pbcopy < /dev/null; fi
echo "Stored as worker secret TMDB_API_KEY."
