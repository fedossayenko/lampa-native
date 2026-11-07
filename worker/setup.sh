#!/bin/bash
# Once per Cloudflare account (after `npx wrangler@4 login`): makes the worker's KV namespace and D1 database, writes
# wrangler.toml (not in git) from wrangler.example.toml, makes the D1 tables (safe to run again), and deploys.
# NAME=<name> calls the worker something else than lampa-native: https://<name>.<account>.workers.dev
set -euo pipefail
cd "$(dirname "$0")"
read -r -a W <<< "${NPX:-npx} wrangler@4"
NAME=${NAME:-lampa-native}
if [ ! -f wrangler.toml ]; then
    kv=$("${W[@]}" kv namespace create "$NAME-grades" | grep -oE '[0-9a-f]{32}' | head -1 || true)
    db=$("${W[@]}" d1 create "$NAME" | grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | head -1 || true)
    if [ -z "$kv" ] || [ -z "$db" ]; then
        echo "Couldn't make the KV namespace or the D1 database (signed in? npx wrangler@4 login)." >&2
        exit 1
    fi
    sed -e "s/^name = .*/name = \"$NAME\"/" -e "s/<KV_ID>/$kv/" -e "s/<D1_NAME>/$NAME/" -e "s/<D1_ID>/$db/" \
        wrangler.example.toml > wrangler.toml
fi
db=$(sed -n 's/^database_name = "\(.*\)"$/\1/p' wrangler.toml)
for f in *.sql; do
    "${W[@]}" d1 execute "$db" --remote --yes --file "$f" > /dev/null
done
"${W[@]}" deploy
cat <<'TXT'

Deployed. Now its secrets (README, Setup):
  ./set-tmdb-key.sh                                 TMDB's API key (required)
  ./set-torbox-key.sh                               TorBox's API key (required to play)
  npx wrangler@4 secret put STREAM_ADDON            a Torrentio-style Stremio addon's address, with no key in it:
                                                    the TV may fetch it itself (required for sources)
  npx wrangler@4 secret put CUB_ACCOUNTS            CUB user ids allowed, comma-separated (optional: CUB features)
  npx wrangler@4 secret put MDBLIST_API_KEY         MDBList's key (optional: RT, Metacritic, Letterboxd scores)
  ./set-theintrodb-key.sh                           TheIntroDB's key (optional: more skip times)
Then connect each TV: WORKER=https://<name>.<account>.workers.dev STICK=<tv ip>:5555 ../tools/connect.sh
TXT
