#!/usr/bin/env python3
"""Finds series' opening titles by sound, for the worker's skip times (/segments, D1 intros).

The titles' music is the same in every episode: in the first 4 minutes of two episodes of one release, the longest
stretch of near-identical audio is the intro, wherever a cold open or a recap puts it (Foundation S1 E9: 31.8-113.9 s
after a "Previously on"; the community databases said 0-84 s). Runs on a Mac at home every 10 minutes (launchd,
intro-detect.plist): takes the worker's jobs (/segments/jobs: a release's episode the stick played or asked
for, and the 5 after it), gets probe links from /play (the torrent's every episode; they mark nothing watched), and
posts what it found
(/segments/own). Python 3.9 + numpy + ffmpeg. Files in ~/.lampa-intros: play_token (the TV's, never printed),
fingerprints (cache/), log, worker (the worker's address).

Fingerprints: 32 bits every 0.1 s (Haitsma-Kalker: signs of band energy differences, 300-2000 Hz, frame to frame).
Match: for every shift, the longest run of frames differing in at most 10 bits.
Recap: "Previously on…" (en/ru/uk) in the release's text subtitles, before an intro that starts in the first 2 min.
"""
import fcntl
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request

import numpy as np

HOME = os.path.expanduser('~/.lampa-intros')
with open(os.path.join(HOME, 'worker'), encoding='utf-8') as f:  # https://<name>.<account>.workers.dev
    WORKER = f.read().strip()
SECONDS = 240           # of each episode's start
SR, HOP, WIN = 8000, 800, 2048
STEP = HOP / SR         # 0.1 s a fingerprint
MIN_S, MAX_S = 15, 200  # an intro's length
EPISODES = 6            # the one asked for and the 5 after it
FULL = SECONDS / STEP * 0.9  # fingerprints of a whole download
# ponytail: a recap runs from 0 to the intro, found by a "Previously on…" subtitle in the file's first 60 MB (6+ s even of
# an 80 Mbit/s remux). An intro later than 2 min may follow a cold open after the recap: no recap then, not guessed.
HEAD_BYTES = 60_000_000
RECAP = re.compile(r'previously on|ранее в|в предыдущих сери|раніше в|у попередніх сері', re.I)
RECAP_MS = (5_000, 120_000)  # the intro's start


def log(*a):
    print(time.strftime('%Y-%m-%d %H:%M:%S'), *a, flush=True)


def api(path, body=None):
    token = open(os.path.join(HOME, 'play_token')).read().strip()
    req = urllib.request.Request(WORKER + path, data=None if body is None else json.dumps(body).encode(),
                                 headers={'X-Play-Token': token, 'Content-Type': 'application/json',
                                          'User-Agent': 'lampa-intros/1'})  # Cloudflare turns Python-urllib away (403)
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read() or b'{}') if r.status != 204 else {}


def fingerprints(x):
    n = (len(x) - WIN) // HOP
    if n < 100:
        return np.zeros(0, dtype=np.uint32)
    frames = np.lib.stride_tricks.sliding_window_view(x, WIN)[::HOP][:n] * np.hanning(WIN)
    spec = np.abs(np.fft.rfft(frames, axis=1)) ** 2
    edges = np.geomspace(300, 2000, 34) * WIN / SR
    e = np.log(np.stack([spec[:, int(a):int(b)].sum(1) for a, b in zip(edges[:-1], edges[1:])], 1) + 1e-9)
    d = np.diff(e, axis=1)
    bits = (d[1:] - d[:-1]) > 0
    return np.packbits(bits, axis=1, bitorder='little').view('<u4')[:, 0].astype(np.uint32)


def episode_prints(url, name):
    path = os.path.join(HOME, 'cache', name + '.npy')
    if os.path.exists(path):
        return np.load(path)
    pcm = subprocess.run(['ffmpeg', '-v', 'error', '-t', str(SECONDS), '-i', url, '-map', '0:a:0', '-ac', '1',
                          '-ar', str(SR), '-f', 'f32le', 'pipe:1'], capture_output=True, timeout=1800).stdout
    fp = fingerprints(np.frombuffer(pcm, dtype='<f4'))
    if len(fp) > FULL:  # a cut-off download is asked again next time
        os.makedirs(os.path.dirname(path), exist_ok=True)
        np.save(path, fp)
    return fp


def has_recap(url):
    """A "Previously on…" in one of the release's text subtitles, from the file's head on disk (ffmpeg reading only
    subtitle tracks from a link keeps downloading: some have no line for many minutes)."""
    req = urllib.request.Request(url, headers={'Range': f'bytes=0-{HEAD_BYTES - 1}', 'User-Agent': 'lampa-intros/1'})
    with tempfile.TemporaryDirectory(dir=HOME) as d:
        head = os.path.join(d, 'head.mkv')
        with urllib.request.urlopen(req, timeout=600) as r, open(head, 'wb') as f:
            f.write(r.read(HEAD_BYTES))  # at most that, should a server ignore the range
        subs = json.loads(subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 's', '-show_entries',
                                          'stream=index,codec_name', '-of', 'json', head],
                                         capture_output=True, timeout=120).stdout or b'{}').get('streams', [])
        out = [a for s in subs if s.get('codec_name') in ('subrip', 'ass', 'ssa', 'webvtt')
               for a in ('-map', f'0:{s["index"]}', '-f', 'srt', os.path.join(d, f'{s["index"]}.srt'))]
        if out:
            subprocess.run(['ffmpeg', '-v', 'error', '-i', head, *out], capture_output=True, timeout=300)
        return any(RECAP.search(open(os.path.join(d, n), encoding='utf-8', errors='replace').read())
                   for n in os.listdir(d) if n.endswith('.srt'))


def popcount(v):
    v = v - ((v >> 1) & 0x55555555)
    v = (v & 0x33333333) + ((v >> 2) & 0x33333333)
    return (((v + (v >> 4)) & 0x0F0F0F0F) * 0x01010101 & 0xffffffff) >> 24


def match(a, b):
    """The longest stretch of near-identical frames: (frames, start in a, start in b)."""
    best = (0, 0, 0)
    for shift in range(-len(b) + 50, len(a) - 50):
        ia = np.arange(max(0, shift), min(len(a), len(b) + shift))
        ok = popcount(a[ia] ^ b[ia - shift]) <= 10
        ok = np.convolve(ok, np.ones(5), 'same') >= 3  # bridge short dropouts
        # runs of True: their ends and lengths
        edges = np.flatnonzero(np.diff(np.concatenate(([0], ok.astype(np.int8), [0]))))
        if len(edges):
            starts, ends = edges[::2], edges[1::2]
            i = np.argmax(ends - starts)
            if ends[i] - starts[i] > best[0]:
                best = (int(ends[i] - starts[i]), int(ia[starts[i]]), int(ia[starts[i]] - shift))
    return best


def run_job(job):
    hash_, season, episode = job['key'].split('/')
    q = urllib.parse.urlencode({'file': job['file'], 'name': job['name'], 'series': job['series'] or 'x', 'probe': 1})
    pl = api(f'/play/{hash_}?{q}').get('playlist') or []  # probe: the torrent's every episode
    at = next((i for i, x in enumerate(pl) if [x[0], x[1]] == [int(season), int(episode)]), None)
    found = []
    for i in range(at, min(at + EPISODES, len(pl))) if at is not None else ():
        x = pl[i]
        if f'{x[0]}:{x[1]}' in job['have']:
            continue
        a = episode_prints(x[4], f'{hash_}-{x[0]}-{x[1]}')
        whole = len(a) > FULL
        # not the next one first: its recap is clips of this one, maybe from its first minutes; a finale may have
        # its own opening: up to 3 others
        for j in [j for j in (i + 2, i - 1, i + 1, i - 2, i + 3) if 0 <= j < len(pl)][:3]:
            b = episode_prints(pl[j][4], f'{hash_}-{pl[j][0]}-{pl[j][1]}')
            frames, sa, sb = match(a, b)
            log(f'{hash_[:8]} S{x[0]}E{x[1]} vs E{pl[j][1]}: {frames * STEP:.1f} s at {sa * STEP:.1f} s')
            if MIN_S <= frames * STEP <= MAX_S:
                start, end = round(sa * STEP * 1000), round((sa + frames) * STEP * 1000)
                recap = RECAP_MS[0] <= start <= RECAP_MS[1] and has_recap(x[4])
                if recap:
                    log(f'{hash_[:8]} S{x[0]}E{x[1]} recap: 0-{start / 1000:.1f} s')
                found.append({'hash': hash_, 'season': x[0], 'episode': x[1], 'start': start, 'end': end,
                              **({'recap': start} if recap else {})})
                break
            whole = whole and len(b) > FULL
        else:
            if whole:  # no titles shared (a finale's own opening): 0-0, so its plays don't queue it again
                found.append({'hash': hash_, 'season': x[0], 'episode': x[1], 'start': 0, 'end': 0})
    api('/segments/own', {'intros': found, 'done': [job['key']]})


def main():
    os.makedirs(HOME, exist_ok=True)
    with open(os.path.join(HOME, 'lock'), 'w') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return  # the last run still downloading
        for job in api('/segments/jobs').get('jobs', []):
            try:
                run_job(job)
            except Exception as e:  # the next run tries again
                log('job', job['key'][:8], job['key'][40:], 'failed:', e)


if __name__ == '__main__':
    if len(sys.argv) == 3:  # intro-detect.py a.f32 b.f32: match two decoded files (8 kHz mono float), for a check
        fa, fb = (fingerprints(np.fromfile(p, dtype='<f4')) for p in sys.argv[1:])
        f, sa, sb = match(fa, fb)
        print(f'{f * STEP:.1f} s: a {sa * STEP:.1f}-{(sa + f) * STEP:.1f} s, b {sb * STEP:.1f}-{(sb + f) * STEP:.1f} s')
    else:
        main()
