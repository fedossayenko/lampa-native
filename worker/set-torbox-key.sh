#!/bin/bash
# Stores the TorBox API key as the worker secret TORBOX_API_KEY (step 3: the worker checks TorBox's cache and
# asks for links), so the stick never holds it: never on disk, never in the APK, never in argv.
# On torbox.app → Settings, copy the API key, then run ./set-torbox-key.sh. It reads the clipboard on a Mac (else stdin),
# checks the key with TorBox, hands it to wrangler and clears the clipboard.
set -euo pipefail
FROM_CLIPBOARD=
if [ -t 0 ] && command -v pbpaste > /dev/null; then
    KEY=$(pbpaste); FROM_CLIPBOARD=1
elif [ -t 0 ]; then
    read -rsp 'TorBox API key: ' KEY; echo
else
    KEY=$(cat)
fi
KEY=$(printf %s "$KEY" | tr -d '[:space:]')

# Check the shape first so a wrong clipboard (a password with symbols, a URL) is never sent anywhere.
if [[ ! $KEY =~ ^[A-Za-z0-9-]{20,80}$ ]]; then
    echo "That doesn't look like a TorBox API key. Copy it again from torbox.app → Settings."
    exit 1
fi

CFG=$(printf 'url = "https://api.torbox.app/v1/api/user/me"\nheader = "Authorization: Bearer %s"' "$KEY")
code=$(printf '%s\n' "$CFG" | curl -s -o /dev/null -w '%{http_code}' -K -)
if [ "$code" != 200 ]; then
    echo "TorBox rejected it (HTTP $code). Copy it again."
    exit 1
fi
echo "TorBox accepted the key."

cd "$(dirname "$0")"
printf %s "$KEY" | ${NPX:-npx} wrangler@4 secret put TORBOX_API_KEY
if [ -n "$FROM_CLIPBOARD" ]; then pbcopy < /dev/null; fi
echo "Stored as worker secret TORBOX_API_KEY."
