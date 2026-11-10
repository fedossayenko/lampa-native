#!/usr/bin/env python3
"""IMDb's ratings export (imdb.com → Your Ratings → ⋯ → Export) into the grades lampa-native and Lampa share (D1 grades).

    WORKER=https://… python3 -I worker/imdb-import.py ratings.csv            # dry run: what would change, and IMDb score vs grade
    WORKER=https://… python3 -I worker/imdb-import.py ratings.csv --write    # merge it in

Each title is found by its IMDb id (the worker's /find). A grade counts as watched: the film leaves My list, Finish later
and Continue browsing. Per title the newer one wins, as /grades does: IMDb's "Date Rated" against the grade's time,
so a grade given in Lampa after the IMDb rating (or removed since) stays. Runs again safely with a newer export.
The new grades go in as one SQL file whose rows each replace only an older one (a grade given on the TV while this runs
stays); the account is the one that has rows (open Home once after a deploy, which moves grades kept in KV).
"""
import csv
import json
import os
import subprocess
import sys
import tempfile
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

WORKER = os.environ['WORKER']  # https://<name>.<account>.workers.dev
NPX = os.environ.get('NPX', 'npx')
# the stick's play token (tools/connect.sh keeps it here): /find spends the worker's TMDB key, so it asks for it
TOKEN = Path('~/.config/lampa-native/play_token').expanduser().read_text(encoding='utf-8').strip()


# IMDb's 1-10 → lampa-native's grades (Cub.GRADES): 2 Loved, 1 Good, 0 OK, -1 Bad, the mapping that agreed best
# with titles graded in both places.
def grade(score):
    return 2 if score >= 10 else 1 if score >= 7 else 0 if score >= 5 else -1


def find(imdb):
    # Cloudflare turns away Python's own User-Agent (403)
    ask = urllib.request.Request(f'{WORKER}/find/{imdb}', headers={'User-Agent': 'imdb-import', 'X-Play-Token': TOKEN})
    with urllib.request.urlopen(ask, timeout=30) as r:
        return json.load(r)['card']


def q(text):
    return "'" + text.replace("'", "''") + "'"  # an SQL string literal


def sql(*args):
    """wrangler's D1 on the worker's database (the name wrangler.toml holds); the rows of a SELECT."""
    here = os.path.dirname(os.path.abspath(__file__))  # beside wrangler.toml
    name = next(line.split('"')[1] for line in open(os.path.join(here, 'wrangler.toml'), encoding='utf-8')
                if line.startswith('database_name'))
    out = subprocess.run([NPX, 'wrangler', 'd1', 'execute', name, '--remote', '--json', *args],
                         check=True, capture_output=True, text=True, cwd=here).stdout
    return json.loads(out)[0]['results']


def main():
    rows = list(csv.DictReader(open(sys.argv[1], encoding='utf-8-sig')))
    write = '--write' in sys.argv
    keys = [r['who'] for r in sql('--command', 'SELECT DISTINCT who FROM grades')]
    if len(keys) != 1:
        sys.exit(f'expected one account with grades, found {keys}')
    who = keys[0]
    have = {r['card']: json.loads(r['v']) for r in sql('--command', f"SELECT card, v FROM grades WHERE who = '{who}'")}
    with ThreadPoolExecutor(8) as pool:
        cards = list(pool.map(lambda r: find(r['Const']), rows))
    found, missing, kept, compare = {}, [], 0, Counter()
    for r, c in zip(rows, cards):
        if c is None:
            missing.append(f"{r['Const']} {r['Title']} ({r['Year']}, {r['Title Type']})")
            continue
        key = f'{c[1]}_{c[0]}'
        score = int(r['Your Rating'])
        t = int(datetime.strptime(r['Date Rated'], '%Y-%m-%d').replace(tzinfo=timezone.utc).timestamp() * 1000)
        old = have.get(key)
        if old is not None:
            compare[(score, str(old.get('g')))] += 1
            if old.get('t', 0) >= t:
                kept += 1
                continue
        tv = c[1] == 'tv'
        original = r['Original Title'] or r['Title']
        card = {'id': c[0], 'name' if tv else 'title': c[2], 'original_name' if tv else 'original_title': original,
                'source': 'tmdb'}
        if c[3]:
            card['first_air_date' if tv else 'release_date'] = c[3]
        if c[4]:
            card['poster_path'] = c[4]
        found[key] = {'g': grade(score), 't': t, 'card': card}
    print(f'{len(rows)} IMDb ratings: {len(found)} to add, {kept} already graded in Lampa (kept), {len(missing)} not on TMDB')
    print('IMDb score vs your grade in Lampa on the same title:', dict(sorted(compare.items())))
    print('new grades:', dict(Counter(v['g'] for v in found.values())))
    for m in missing:
        print('  not found:', m)
    if write and found:
        with tempfile.NamedTemporaryFile('w', suffix='.sql') as f:
            for key, v in found.items():
                f.write(f"INSERT INTO grades (who, card, t, v) VALUES ({q(who)}, {q(key)}, {v['t']}, {q(json.dumps(v))}) "
                        'ON CONFLICT (who, card) DO UPDATE SET t = excluded.t, v = excluded.v WHERE excluded.t > grades.t;\n')
            f.flush()
            sql('--file', f.name)
        print(f'written: {len(found)} grades for {who}')


if __name__ == '__main__':
    main()
