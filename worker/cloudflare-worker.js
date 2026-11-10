/**
 * Lampa Native's worker (Cloudflare Workers, free plan): one small JSON answer per screen from TMDB, MDBList,
 * CUB and TorBox, the keys kept here as secrets (README: Worker). Also the Lampa plugin's pass-through (?url=).
 * Deploy: ./setup.sh once, then npx wrangler@4 deploy. Tests: node --test 'native.test.js'.
 */
import VOICE from './voice-prompt.json' with { type: 'json' };

const TORBOX_HOST = 'api.torbox.app';
const METHODS = ['GET', 'POST', 'HEAD'];

// Every call out gives up after a time (Cloudflare's own wall limit is far longer): a hanging TMDB, CUB mirror or
// TorBox then fails like a refused one, which each caller already handles, instead of holding the screen.
const within = (ms = 8000) => ({ signal: AbortSignal.timeout(ms) });

// CORS goes on every answer, a 4xx or 5xx as much as a 200, so the Lampa plugin can read what went wrong; a
// preflight is answered with the headers it asked to send.
function corsHeaders(request) {
  const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': `${METHODS.join(', ')}, OPTIONS`,
    'Access-Control-Max-Age': '7200' }; // Chrome keeps a preflight 2 hours at most
  const asked = request.headers.get('Access-Control-Request-Headers');
  if (asked) cors['Access-Control-Allow-Headers'] = asked;
  return cors;
}

const corsError = (request, message, status) => new Response(message, { status, headers: corsHeaders(request) });

// The Lampa TorBox plugin's ?url=<address> (PASS_THROUGH=1): https only, to TorBox's API and the Jacred parsers
// jr.maxvol.pro (the plugin's) and jacred.xyz. Its X-Api-Key goes to TorBox alone, as the Bearer token; besides that only three plain
// headers go along (no cookies, nothing else of the browser's).
const PASS_TO = new Map([[TORBOX_HOST, true], ['jr.maxvol.pro', false], ['jacred.xyz', false]]); // host → gets the key
const PASS_KEEP = ['accept', 'content-type', 'range'];

// The torrent id a passed-through TorBox requestdl asks a link for (the Lampa plugin starting a play), else null.
function pluginPlay(request) {
  try {
    const to = new URL(new URL(request.url).searchParams.get('url'));
    const id = Number(to.searchParams.get('torrent_id'));
    return to.hostname === TORBOX_HOST && to.pathname.endsWith('/requestdl') && Number.isInteger(id) ? id : null;
  } catch {
    return null;
  }
}

async function passThrough(request) {
  let to = null;
  try {
    to = new URL(new URL(request.url).searchParams.get('url'));
  } catch { /* none, or not an address */ }
  if (to?.protocol !== 'https:' || !PASS_TO.has(to.hostname)) {
    return corsError(request, '?url= takes an https address of TorBox\'s API or a Jacred parser.', 400);
  }
  const headers = new Headers();
  for (const name of PASS_KEEP) if (request.headers.has(name)) headers.set(name, request.headers.get(name));
  if (PASS_TO.get(to.hostname) && request.headers.has('X-Api-Key')) {
    headers.set('Authorization', `Bearer ${request.headers.get('X-Api-Key')}`);
  }
  const body = ['GET', 'HEAD'].includes(request.method) ? null : request.body;
  const answer = await fetch(to.href, { method: request.method, headers, body }).catch((e) => e);
  if (answer instanceof Error) return corsError(request, `${to.hostname} didn't answer: ${answer.message}`, 502);
  const out = new Response(answer.body, answer); // its status and headers, plus CORS
  for (const [name, value] of Object.entries(corsHeaders(request))) out.headers.set(name, value);
  return out;
}

// The streams addon (a Stremio addon's address, Torrentio's or any: secret STREAM_ADDON, README) for one title. The TV
// gets the address too (/addon, when the addon refuses the worker): it must carry no key.
const addonStreams = (env, kind, id) => `${String(env.STREAM_ADDON).replace(/\/+$/, '')}/stream/${kind}/${id}.json`;

// The Lampa plugin's film scores from MDBList, the key stays here (secret MDBLIST_API_KEY).
// GET /mdblist/movie/<tmdb id> or /mdblist/show/<tmdb id> -> { "tomatoes": 96, "metacritic": 78, ... }
// POST /mdblist/movie or /mdblist/show with {"ids": [<tmdb id>, ...]} (up to 200) -> { "<id>": { ratings }, ... }
// X-RateLimit-Remaining is passed on so the plugin can stop poster lookups before the daily limit.
// Letterboxd `value` is 0-5 in a single answer but 0-10 in a batch answer; its `score` (0-100) is the same in both.
function ratingsOf(media) {
  const ratings = {};
  for (const r of media.ratings || []) {
    if (r.value != null) ratings[r.source] = r.source === 'letterboxd' && r.score != null ? r.score / 20 : r.value;
  }
  return ratings;
}

// The plugin can't hold our token, so these two are open and spend the key that /card's scores use (MDBList's 1,000
// a day). A title's scores are kept a day at this Cloudflare location (the Cache API), a batch by its ids, and no more
// than MDBLIST_BATCHES new batches a day go to MDBList from one location: a loop of ever new ids can't empty the quota
// the TV's film pages share (the rest get 429, scores missing, never a failed page).
const MDBLIST_BATCHES = 100;
// Counts today's uses of `name` at this location (the Cache API has no atomic add: close enough for a cap); true while under max.
async function underCap(name, max) {
  const key = new Request(`https://cap.invalid/${name}/${new Date().toISOString().slice(0, 10)}`);
  const used = Number(await caches.default.match(key).then((r) => (r ? r.text() : 0))) || 0;
  if (used >= max) return false;
  await caches.default.put(key, new Response(String(used + 1), { headers: { 'Cache-Control': 'max-age=86400' } }));
  return true;
}

async function mdblist(request, env, type, id, ctx) {
  const scores = (res, body) => new Response(JSON.stringify(body), {
    headers: {
      'Content-Type': 'application/json',
      'Cache-Control': 'max-age=86400',
      'X-RateLimit-Remaining': res.headers.get('X-RateLimit-Remaining') || '',
      'Access-Control-Expose-Headers': 'X-RateLimit-Remaining',
      ...corsHeaders(request),
    },
  });
  if (id) {
    return edgeCached(request, ctx, async () => {
      const res = await fetch(`https://api.mdblist.com/tmdb/${type}/${id}?apikey=${env.MDBLIST_API_KEY}`, within());
      return res.ok ? scores(res, ratingsOf(await res.json())) : corsError(request, `MDBList ${res.status}`, res.status);
    });
  }
  let ids;
  try {
    ids = (await request.json()).ids;
  } catch {}
  if (!Array.isArray(ids) || !ids.length || ids.length > 200 || !ids.every((x) => /^\d+$/.test(String(x)))) {
    return corsError(request, 'Bad request: "ids" must be 1-200 TMDB ids.', 400);
  }
  const ask = async () => {
    const res = await fetch(`https://api.mdblist.com/tmdb/${type}?apikey=${env.MDBLIST_API_KEY}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ids: ids.map(String) }),
      ...within(),
    });
    if (!res.ok) return corsError(request, `MDBList ${res.status}`, res.status);
    const json = await res.json();
    const body = {};
    for (const m of Array.isArray(json) ? json : []) if (m.ids && m.ids.tmdb) body[m.ids.tmdb] = ratingsOf(m);
    return scores(res, body);
  };
  if (typeof caches === 'undefined') return ask();
  const key = new Request(`https://cap.invalid/mdblist/${type}/${[...new Set(ids.map(Number))].sort((a, b) => a - b).join(',')}`);
  const hit = await caches.default.match(key);
  if (hit) return hit;
  if (!(await underCap('mdblist', MDBLIST_BATCHES))) return corsError(request, 'MDBList: no more batches today.', 429);
  const res = await ask();
  if (res.ok) ctx ? ctx.waitUntil(caches.default.put(key, res.clone())) : await caches.default.put(key, res.clone());
  return res;
}

// The Lampa plugin's film grades, shared by every Lampa signed in to the same CUB account + profile (KV GRADES).
// The client sends its CUB sign-in (X-Cub-Token, X-Cub-Profile); we only ask CUB who it is, never store it.
// Only accounts listed in the CUB_ACCOUNTS secret may use it, so this worker isn't free storage for others.
const CUB_DOMAINS = ['cub.best', 'cub.black', 'cubnotrip.top'];
// CUB's API with the client's sign-in: the first mirror that answers JSON (its errors too: {error, code}), or null.
// form: a POST as Lampa sends it.
async function cubApi(request, path, form) {
  const token = request.headers.get('X-Cub-Token');
  const profile = request.headers.get('X-Cub-Profile') || '';
  if (!token || !/^\d*$/.test(profile)) return null;
  for (const domain of CUB_DOMAINS) {
    try {
      return await (await fetch(`https://${domain}/api/${path}`, form
        ? { method: 'POST', headers: { token, profile }, body: new URLSearchParams(form), ...within(4000) }
        : { headers: { token, profile }, ...within(4000) })).json();
    } catch {
      // mirror down, try the next
    }
  }
  return null;
}

async function cubUser(request, env) {
  const json = await cubApi(request, 'users/get');
  const id = json && json.user && json.user.id;
  if (!id) return null;
  if (!String(env.CUB_ACCOUNTS || '').split(',').map((x) => x.trim()).includes(String(id))) { // "1, 2" works as "1,2"
    console.log(`CUB account ${id} isn't in CUB_ACCOUNTS`); // setup: npx wrangler@4 tail shows whose to add
    return null;
  }
  return `${id}:${request.headers.get('X-Cub-Profile') || ''}`;
}

// POST /grades {"grades": {"movie_603": {g, t, card}, ...}} -> all grades of this account, merged:
// per film the newer `t` wins; g null = grade removed (kept so the removal reaches other devices).
const GRADES_BODY_MAX = 2e6; // a few thousand graded films with their cards; a KV value may hold 25 MB
async function grades(request, env) {
  const who = await cubUser(request, env);
  if (!who) return corsError(request, 'CUB sign-in required.', 401);
  if (Number(request.headers.get('Content-Length')) > GRADES_BODY_MAX) return corsError(request, 'Too large.', 413);
  let mine;
  try {
    mine = (await request.json()).grades;
  } catch {}
  if (!mine || typeof mine !== 'object' || Array.isArray(mine)) return corsError(request, 'Bad request: "grades" object expected.', 400);
  const all = (await env.GRADES.get(who, 'json')) || {};
  let changed = false;
  for (const [k, v] of Object.entries(mine)) {
    if (!/^(movie|tv)_\d+$/.test(k) || !v || typeof v.t !== 'number' || (all[k] && all[k].t >= v.t)) continue;
    all[k] = v;
    changed = true;
  }
  if (changed) await env.GRADES.put(who, JSON.stringify(all)); // KV free tier: 1,000 writes a day
  return new Response(JSON.stringify(all), { headers: { 'Content-Type': 'application/json', ...corsHeaders(request) } });
}

// lampa-native: the TV's home screen in one small response; the TMDB key
// stays here (secret TMDB_API_KEY: a 32-hex v3 key or a v4 read access token).
// GET /home -> {"rows": [[title, [[id, "movie"|"tv", name, year, poster_path, score, backdrop_path], ...]], ...]}
// Same rows as Lampa's TMDB home, all in English (TMDB en-US); score = TMDB × 10.
// Only what a card draws is sent, plus the backdrop so a film page loads it together with /card
// (TMDB's lists are ~10× bigger, see X-Raw-Bytes), and the stick's HTTP cache keeps it for an hour.
const HOME_ROWS = [
  ['Watching now', 'movie/now_playing'],
  ['Today in trend', 'trending/all/day'],
  ['Trending for the week', 'trending/all/week'],
  ['Watch in cinemas', 'movie/upcoming'],
  ['Popular films', 'movie/popular'],
  ['Popular TV shows', 'tv/popular'],
  ['Top movies', 'movie/top_rated'],
  ['Top series', 'tv/top_rated'],
];

function tmdb(env, path, query = '', language = 'en-US') {
  const key = env.TMDB_API_KEY;
  const v3 = /^[0-9a-f]{32}$/.test(key);
  const url = `https://api.themoviedb.org/3/${path}?language=${language}${query}${v3 ? `&api_key=${key}` : ''}`;
  return fetch(url, v3 ? within() : { headers: { Authorization: `Bearer ${key}` }, ...within() });
}

// A Home card: [id, type, name, year, poster_path, score, backdrop_path]. Never null: the stick's org.json turns it
// into the text "null".
function cardOf(m, type) {
  return [m.id, type, m.title || m.name || '', (m.release_date || m.first_air_date || '').slice(0, 4),
    m.poster_path, Math.round((m.vote_average || 0) * 10), m.backdrop_path || ''];
}

// A GET answer kept at this Cloudflare location for its max-age (Cache API): asked again, nothing is fetched. The
// stick asks for a film's /card ahead, while focus rests on its card: a film costs one MDBList lookup a day.
async function edgeCached(request, ctx, make) {
  if (typeof caches === 'undefined') return make(); // node tests without it
  // The key is the address with only the parameters an answer depends on, in order: an extra ?x=<random> would
  // otherwise miss the cache every time and spend a TMDB call (or more) each.
  const url = new URL(request.url);
  const kept = [...url.searchParams].filter(([k]) => CACHE_PARAMS.includes(k)).sort(([a], [b]) => (a < b ? -1 : 1));
  const key = new Request(`${url.origin}${url.pathname}?${new URLSearchParams(kept)}`);
  const hit = await caches.default.match(key);
  if (hit) return hit;
  const res = await make();
  if (res.ok) {
    const put = caches.default.put(key, res.clone());
    if (ctx) ctx.waitUntil(put);
    else await put;
  }
  return res;
}
const CACHE_PARAMS = ['v', 'lang', 'type', 'years', 'country', 'score', 'sort', 'page'];

// lampa-native's search predictions (titles.sql): ~1000 well-known films and series, refreshed daily by two crons
// because the free plan allows 50 calls a run: English names with the original titles, then the Russian names.
const TITLE_CRONS = { '7 3 * * *': 'en-US', '37 3 * * *': 'ru-RU' };
const TITLE_LISTS = [ // [path, query, pages]: 45 calls
  ['discover/movie', '&sort_by=vote_count.desc', 20], ['discover/tv', '&sort_by=vote_count.desc', 10],
  ['movie/popular', '', 5], ['tv/popular', '', 5],
  ['discover/movie', '&sort_by=vote_count.desc&with_original_language=ru', 3],
  ['discover/tv', '&sort_by=vote_count.desc&with_original_language=ru', 2],
];

// Search words: lower case, ё as е, letters and digits only, a space on each side: LIKE '% word%' = a word's start.
const words = (s) => ` ${String(s || '').toLowerCase().replace(/ё/g, 'е').replace(/[^\p{L}\p{N}]+/gu, ' ').trim()} `;

async function refreshTitles(env, language) {
  const calls = TITLE_LISTS.flatMap(([path, query, pages]) =>
    Array.from({ length: pages }, (_, i) => [path, `${query}&page=${i + 1}`]));
  const found = new Map();
  await Promise.all(calls.map(async ([path, query]) => {
    try {
      const res = await tmdb(env, path, query, language);
      if (!res.ok) return;
      const type = path.includes('tv') ? 'tv' : 'movie';
      for (const m of (await res.json()).results || []) if (m.poster_path) found.set(`${type}:${m.id}`, { type, m });
    } catch {
      // one page less until tomorrow
    }
  }));
  const rows = [...found.values()];
  if (language === 'ru-RU') { // Russian names for the titles the English run stored; it adds none
    await env.DB.prepare(`UPDATE titles SET ru = j.value->>'ru' FROM json_each(?) AS j
      WHERE titles.type = j.value->>'type' AND titles.id = j.value->>'id'`)
      .bind(JSON.stringify(rows.map(({ type, m }) => ({ type, id: m.id, ru: words(m.title || m.name) })))).run();
    return;
  }
  const now = Date.now();
  await env.DB.prepare(`INSERT INTO titles (type, id, card, votes, seen, en)
      SELECT value->>'type', value->>'id', value->>'card', value->>'votes', ?2, value->>'en' FROM json_each(?1) WHERE true
      ON CONFLICT (type, id) DO UPDATE SET card = excluded.card, votes = excluded.votes, seen = excluded.seen,
        en = excluded.en`)
    .bind(JSON.stringify(rows.map(({ type, m }) => ({ type, id: m.id, card: JSON.stringify(cardOf(m, type)),
      votes: m.vote_count || 0, en: words(`${m.title || m.name} ${m.original_title || m.original_name || ''}`) }))), now)
    .run();
  await env.DB.prepare('DELETE FROM titles WHERE seen < ?').bind(now - 30 * 86400e3).run(); // off every list a month
}

// GET /search?q=<text> -> lampa-native's search, as Home cards: well-known titles with a word starting so (the
// index above, most voted first: one letter is enough), then from three letters TMDB's own search, each title once.
// A Cyrillic query finds the Russian names. An hour in caches (TMDB may have failed); nothing found isn't kept.
async function search(request, env) {
  const q = (new URL(request.url).searchParams.get('q') || '').trim().slice(0, 100);
  const w = words(q).trim();
  const [known, found] = !w ? [[], []] : await Promise.all([
    env.DB ? env.DB.prepare(`SELECT card FROM titles WHERE en || ru LIKE ? ORDER BY votes DESC LIMIT ?`)
      .bind(`% ${w}%`, w.length >= 3 ? 10 : 20).all().then((r) => r.results.map((x) => JSON.parse(x.card)))
      .catch(() => []) : [],
    w.length < 3 ? [] : tmdb(env, 'search/multi', `&include_adult=false&query=${encodeURIComponent(q)}`)
      .then((res) => (res.ok ? res.json() : {})).then((o) => (o.results || [])
        .filter((m) => (m.media_type === 'movie' || m.media_type === 'tv') && m.poster_path)
        .map((m) => cardOf(m, m.media_type))).catch(() => []),
  ]);
  const seen = new Set();
  const results = [...known, ...found].filter((c) => !seen.has(`${c[1]}:${c[0]}`) && seen.add(`${c[1]}:${c[0]}`))
    .slice(0, 20);
  return new Response(JSON.stringify({ results }), {
    headers: { 'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': results.length ? 'max-age=3600' : 'no-store', ...corsHeaders(request) },
  });
}

async function home(request, env) {
  let rawBytes = 0;
  const rows = await Promise.all(HOME_ROWS.map(async ([title, path]) => {
    try {
      const res = await tmdb(env, path);
      if (!res.ok) return null;
      const text = await res.text();
      rawBytes += new TextEncoder().encode(text).length;
      const items = [];
      for (const m of JSON.parse(text).results || []) {
        const type = m.media_type || path.split('/')[0]; // trending mixes in people: skipped below
        if ((type === 'movie' || type === 'tv') && m.poster_path) items.push(cardOf(m, type));
      }
      return [title, items];
    } catch {
      return null; // one failed row shouldn't blank the whole screen
    }
  }));
  const ok = rows.filter(Boolean);
  if (!ok.length) return corsError(request, 'TMDB unavailable.', 502);
  return new Response(JSON.stringify({ rows: ok }), {
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'max-age=3600',
      'X-Raw-Bytes': String(rawBytes),
      ...corsHeaders(request),
    },
  });
}

// GET /card/movie/<tmdb id> or /card/tv/<tmdb id> -> lampa-native's film page, ready to draw (native.test.js):
// {name, original, line, overview, scores: [[label, text, 0-100]], seasons: [[label, text, 0-100]], imdb,
//  episodes: [[season, count]], people, cast: [[name, character, profile_path]], collection, parts: [Home card], trailer,
//  runtime, similar: [Home card]}
// cast: the first 12 with a photo. parts: the other films of its collection (TMDB, one more call), oldest first;
// a film not out yet without a poster is left out. trailer: its YouTube video ("" none). runtime: a film's minutes
// (0 unknown, a series): the page's "ends at 23:41". similar: More like this, TMDB's recommendations of it (the same
// call: appended), out already, with a poster, 50+ votes; at most 14.
// The verdict comes first in scores: the same weighted average as ratings-patch.js (keep the two in step).
const NORM = { tomatoes: 1, metacritic: 1, rogerebert: 25, imdb: 10, letterboxd: 20, kp: 10, trakt: 1, tmdb: 1, metacriticuser: 10 };
const WEIGHTS = { tomatoes: 4, metacritic: 3, imdb: 3, kp: 2, rogerebert: 1, trakt: 1, tmdb: 1, metacriticuser: 1, letterboxd: 0.5 };
const VERDICTS = [[80, 'Excellent'], [70, 'Good'], [60, 'Decent'], [50, 'Mixed'], [0, 'Poor']];
const SHOW = [['imdb', 'IMDb', (v) => v.toFixed(1)], ['tomatoes', 'RT', (v) => v + '%'], ['metacritic', 'MC', String],
  ['letterboxd', 'LB', (v) => v.toFixed(1)]];

async function filmCard(request, env, type, id) {
  const mdbType = type === 'tv' ? 'show' : 'movie';
  const [res, r] = await Promise.all([
    tmdb(env, `${type}/${id}`, '&append_to_response=credits,external_ids,videos,recommendations'),
    fetch(`https://api.mdblist.com/tmdb/${mdbType}/${id}?apikey=${env.MDBLIST_API_KEY}`, within())
      .then((x) => (x.ok ? x.json() : {})).then(ratingsOf).catch(() => ({})), // no scores beats no page
  ]);
  if (!res.ok) return corsError(request, `TMDB ${res.status}`, res.status === 404 ? 404 : 502);
  const m = await res.json();

  const scores = [];
  const got = Object.keys(WEIGHTS).filter((k) => r[k] > 0);
  if (got.length >= 3) {
    const sum = (f) => got.reduce((s, k) => s + f(k), 0);
    const v = Math.round(sum((k) => r[k] * NORM[k] * WEIGHTS[k]) / sum((k) => WEIGHTS[k]));
    scores.push([VERDICTS.find(([min]) => v >= min)[1], String(v), v]);
  }
  if (m.vote_average > 0) scores.push(['TMDB', m.vote_average.toFixed(1), Math.round(m.vote_average * 10)]);
  for (const [k, label, f] of SHOW) if (r[k] > 0) scores.push([label, f(r[k]), Math.round(r[k] * NORM[k])]);

  const runtime = m.runtime > 0 ? `${m.runtime >= 60 ? Math.floor(m.runtime / 60) + 'h ' : ''}${m.runtime % 60}m` : '';
  const seasonCount = m.number_of_seasons > 0 ? `${m.number_of_seasons} season${m.number_of_seasons > 1 ? 's' : ''}` : '';
  const names = (list) => (list || []).map((p) => p.name).join(', ');
  const makers = type === 'tv' ? ['Created by', names(m.created_by)]
    : ['Director', names((m.credits?.crew || []).filter((p) => p.job === 'Director'))];
  const cast = names((m.credits?.cast || []).slice(0, 6));
  const name = m.title || m.name || '';
  const original = m.original_title || m.original_name || '';
  const set = m.belongs_to_collection;
  const collection = set ? await tmdb(env, `collection/${set.id}`).then((x) => (x.ok ? x.json() : {})).catch(() => ({})) : {};
  const parts = (collection.parts || []).filter((p) => p.poster_path && p.id !== Number(id))
    .sort((a, b) => (a.release_date || '9').localeCompare(b.release_date || '9')).map((p) => cardOf(p, 'movie'));

  // Its trailer (on each film's page, not a row of them; OK plays it in SmartTube): TMDB's YouTube
  // videos, an official Trailer first, then any Trailer, then a Teaser; the newest of the kind (a series' newest
  // season's). "" none.
  const kind = (v) => (v.type === 'Trailer' ? (v.official ? 0 : 1) : v.type === 'Teaser' ? 2 : 9);
  const trailer = (m.videos?.results || []).filter((v) => v.site === 'YouTube' && v.key && kind(v) < 9)
    .sort((a, b) => kind(a) - kind(b) || Date.parse(b.published_at) - Date.parse(a.published_at))[0]?.key || '';

  // the director first (follow a person, a director too), then the cast (a director who also
  // acts once, as the director)
  const directors = type === 'movie' ? (m.credits?.crew || []).filter((p) => p.job === 'Director') : [];
  const photos = [...directors.map((p) => ({ ...p, character: 'Director' })),
    ...(m.credits?.cast || []).filter((p) => !directors.some((d) => d.id === p.id))].filter((p) => p.profile_path)
    .slice(0, 12).map((p) => [p.name || '', p.character || '', p.profile_path, p.id || 0]); // id: OK opens /person
  return new Response(JSON.stringify({
    name,
    original: original === name ? '' : original,
    line: [(m.release_date || m.first_air_date || '').slice(0, 4), runtime, seasonCount, names(m.genres)]
      .filter(Boolean).join(' · '),
    overview: m.overview || '',
    scores,
    seasons: (m.seasons || []).filter((s) => s.season_number > 0 && s.vote_average > 0)
      .map((s) => [`S${s.season_number}`, s.vote_average.toFixed(1), Math.round(s.vote_average * 10)]),
    imdb: m.imdb_id || m.external_ids?.imdb_id || '', // for /streams; TV details carry it only in external_ids
    episodes: type === 'tv' ? (m.seasons || []).filter((s) => s.season_number > 0 && s.episode_count > 0)
      .map((s) => [s.season_number, s.episode_count]) : [],
    // the cast's names only when no one has a photo: else the photos row names them
    people: [makers[1] && `${makers[0]} ${makers[1]}`, !photos.length && cast && `Cast ${cast}`].filter(Boolean)
      .join(' · '),
    cast: photos,
    collection: parts.length ? collection.name || '' : '',
    parts,
    trailer,
    runtime: type === 'movie' && m.runtime > 0 ? m.runtime : 0,
    similar: (m.recommendations?.results || []).filter((p) => p.poster_path && (p.vote_count || 0) >= 50
      && (p.release_date || p.first_air_date || '9') <= new Date().toISOString().slice(0, 10))
      .slice(0, 14).map((p) => cardOf(p, p.media_type || type)),
  }), {
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'max-age=86400', // a day: MDBList allows 1,000 lookups a day
      ...corsHeaders(request),
    },
  });
}

// GET /person/<tmdb id> -> lampa-native's actor page (OK on a cast photo; its name and photo come from the cast):
// {line, about, rows: [[title, [Home card]]]}. Rows: Best rated, then the films and the series, newest first; a row with nothing
// is left out. Rows, not a sort switch: both orders on one page, the remote only goes down. Best rated is IMDb's
// weighted rating: votes pull a score toward 6.5 until there are a few hundred, so a 9.5 from 12 votes doesn't top
// a 7.0 from 4,000. Left out everywhere: not out yet (nothing to watch; they led Scarlett Johansson's films five
// deep), playing themselves or archive footage, talk, news and reality shows, a series they are in once (a guest:
// The Simpsons topped Pierce Brosnan's best), no poster, a title twice (two characters). One TMDB call.
const NOT_A_ROLE = /^(self|himself|herself|themselves)\b|archive footage/i;
const TALK = [10763, 10764, 10767]; // TMDB's News, Reality, Talk

// GET /season/<tv id>/<n> -> lampa-native's episode rows: {episodes: [[episode, name,
// runtime minutes, overview, still_path]]}, TMDB's en-US, kept a day at the edge. ?lang=RU (Story so far in the audio
// language): that language's name and overview for each episode TMDB has the overview translated for, else en-US.
async function seasonPage(request, env, id, n) {
  const code = new URL(request.url).searchParams.get('lang') || '';
  const lang = /^[A-Z]{2}$/.test(code) && code !== 'EN' ? new Intl.Locale(`und-${code}`).maximize().language : 'en';
  // the language's call failing must not take the en-US answer with it
  const [res, own] = await Promise.all([tmdb(env, `tv/${id}/season/${n}`), lang === 'en' ? null
    : tmdb(env, `tv/${id}/season/${n}`, '', lang).then((r) => (r.ok ? r.json() : null)).catch(() => null)]);
  if (!res.ok) return corsError(request, `TMDB ${res.status}`, res.status === 404 ? 404 : 502);
  const told = new Map((own?.episodes || []).filter((e) => e.overview).map((e) => [e.episode_number, e]));
  // air_date: lampa-native's "Airs Tuesday, 10 Nov" on an episode not out yet (TMDB names it "Episode 4" till then)
  const episodes = ((await res.json()).episodes || []).map((e) => {
    const t = told.get(e.episode_number) || e;
    return [e.episode_number, t.name || e.name || '', e.runtime || 0, t.overview || '', e.still_path || '', e.air_date || ''];
  });
  // en-US only because the language's call failed: kept 5 minutes, not a day
  const age = lang !== 'en' && !own ? 300 : 86400;
  return new Response(JSON.stringify({ episodes }), {
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': `max-age=${age}`, ...corsHeaders(request) },
  });
}

// A person's credits (TMDB's person/<id> with combined_credits): their roles, and for one known for directing (TMDB's
// known_for_department: Denis Villeneuve) what they directed first; not themselves or archive footage, no talk shows,
// not a series they are in once. Each title once (directed, when both).
function creditsOf(p) {
  const directed = p.known_for_department === 'Directing' ? (p.combined_credits?.crew || []).filter((m) => m.job === 'Director') : [];
  return [...new Map([...directed, ...(p.combined_credits?.cast || []).filter((m) => !NOT_A_ROLE.test(m.character || ''))]
    .filter((m) => !(m.genre_ids || []).some((g) => TALK.includes(g)) && !(m.media_type === 'tv' && m.episode_count < 2))
    .reverse().map((m) => [m.media_type + m.id, { ...m, directs: m.job === 'Director' }])).values()].reverse();
}
const creditDate = (m) => m.release_date || m.first_air_date || '';
// Their films dated after today, soonest first, [date, card, how]: directed ("directs"), or a lead role (TMDB's cast
// order 0-3, "stars"): what Coming up shows for a person you follow. Undated ones (actors' lists are full of projects
// that never come) wait for a date.
function upcomingOf(p, today) {
  return creditsOf(p).filter((m) => m.media_type === 'movie' && creditDate(m) > today && (m.directs || m.order <= 3))
    .sort((a, b) => creditDate(a).localeCompare(creditDate(b)))
    .map((m) => [creditDate(m), cardOf({ ...m, poster_path: m.poster_path || '' }, 'movie'), m.directs ? 'directs' : 'stars']);
}

async function personPage(request, env, id) {
  const res = await tmdb(env, `person/${id}`, '&append_to_response=combined_credits');
  if (!res.ok) return corsError(request, `TMDB ${res.status}`, res.status === 404 ? 404 : 502);
  const p = await res.json();
  const today = new Date().toISOString().slice(0, 10);
  const all = creditsOf(p).filter((m) => m.poster_path);
  const roles = all.filter((m) => creditDate(m) && creditDate(m) <= today);
  const date = (m) => creditDate(m) || '9999';
  const newest = (type) => roles.filter((m) => m.media_type === type)
    .sort((a, b) => date(b).localeCompare(date(a))).map((m) => cardOf(m, type));
  const weighted = (m) => (m.vote_count * m.vote_average + 500 * 6.5) / (m.vote_count + 500);
  const best = roles.filter((m) => m.vote_count > 0).sort((a, b) => weighted(b) - weighted(a)).slice(0, 20)
    .map((m) => cardOf(m, m.media_type));
  // Coming ("Coming, then best rated"): dated after today, soonest first
  const coming = all.filter((m) => creditDate(m) > today).sort((a, b) => creditDate(a).localeCompare(creditDate(b)))
    .map((m) => cardOf(m, m.media_type));
  const born = p.birthday || '';
  const age = born && Number(today.slice(0, 4)) - Number(born.slice(0, 4)) - (today.slice(5) < born.slice(5) ? 1 : 0);
  const life = p.deathday ? `${born.slice(0, 4)}–${p.deathday.slice(0, 4)}` : born && `Born ${born.slice(0, 4)} (${age})`;
  return new Response(JSON.stringify({
    line: [p.known_for_department === 'Directing' && 'Director', life, p.place_of_birth].filter(Boolean).join(' · '),
    about: p.biography || '',
    rows: [['Coming', coming], ['Best rated', best], ['Films', newest('movie')], ['Series', newest('tv')]]
      .filter(([, cards]) => cards.length),
  }), {
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'max-age=86400', ...corsHeaders(request) },
  });
}

// POST /mine/follow/<person id> or /mine/unfollow/<person id> (the CUB sign-in) -> 204. Following asks TMDB at once, so their coming films show in Coming up now; the hourly cron asks
// again a day later (refreshFollows).
async function follow(request, env, on, person) {
  const who = await cubUser(request, env);
  if (!who) return corsError(request, 'CUB sign-in required.', 401);
  if (!env.DB) return corsError(request, 'No database.', 502);
  if (!on) {
    await env.DB.prepare('DELETE FROM follows WHERE who = ? AND person = ?').bind(who, Number(person)).run();
    return new Response(null, { status: 204, headers: corsHeaders(request) });
  }
  const res = await tmdb(env, `person/${person}`, '&append_to_response=combined_credits');
  if (!res.ok) return corsError(request, `TMDB ${res.status}`, res.status === 404 ? 404 : 502);
  const p = await res.json();
  const coming = upcomingOf(p, new Date().toISOString().slice(0, 10));
  // followed already (another device): kept as it is, what was seen too
  await env.DB.prepare(`INSERT INTO follows (who, person, name, coming, known, t) VALUES (?1, ?2, ?3, ?4, ?5, ?6)
      ON CONFLICT (who, person) DO NOTHING`)
    .bind(who, Number(person), p.name || '', JSON.stringify(coming), JSON.stringify(coming.map(([, c]) => c[0])), Date.now())
    .run();
  return new Response(null, { status: 204, headers: corsHeaders(request) });
}

// The hourly cron: each person followed asked of TMDB again once a day, FOLLOWS_FETCH a run (subrequests: For you takes
// 25 of the 50; not in the 04 UTC run, New in 4K's).
const FOLLOWS_FETCH = 10;
// "2026-12-18" → "18 Dec 2026" (fixed names: ICU's en-GB says "Sept" in some versions)
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const shortDate = (d) => `${Number(d.slice(8, 10))} ${MONTHS[Number(d.slice(5, 7)) - 1]} ${d.slice(0, 4)}`;
async function refreshFollows(env, now) {
  // the longest waiting first; each asked is stamped, so one TMDB keeps failing (gone, merged) waits a day too
  const due = (await env.DB.prepare('SELECT person FROM follows WHERE t < ? GROUP BY person ORDER BY MIN(t) LIMIT ?')
    .bind(now - 86400e3, FOLLOWS_FETCH).all()).results.map((r) => r.person);
  if (!due.length) return;
  await env.DB.prepare('UPDATE follows SET t = ?1 WHERE person IN (SELECT value FROM json_each(?2))')
    .bind(now, JSON.stringify(due)).run();
  const today = new Date(now).toISOString().slice(0, 10);
  const got = new Map((await Promise.all(due.map((id) => tmdb(env, `person/${id}`, '&append_to_response=combined_credits')
    .then((res) => (res.ok ? res.json() : null)).then((p) => p && [id, p]).catch(() => null)))).filter(Boolean));
  // by the id asked (TMDB may answer a merged person's own); 4 D1 queries in all (the free plan: 50 a run)
  const rows = (await env.DB.prepare('SELECT who, person, known FROM follows WHERE person IN (SELECT value FROM json_each(?))')
    .bind(JSON.stringify([...got.keys()])).all()).results;
  const told = [], updates = [];
  for (const r of rows) {
    const p = got.get(r.person);
    const coming = upcomingOf(p, today);
    // a film dated that wasn't seen before is news (What's new) for each account following them, once; known keeps
    // the ones before too (a film whose date goes and comes back isn't news twice)
    const known = new Set(JSON.parse(r.known || '[]'));
    for (const [date, c] of coming.filter(([, x]) => !known.has(x[0]))) {
      told.push({ who: r.who, key: `film:${c[0]}`, t: now, card: JSON.stringify(c), head: p.name || '',
        line: `New film: ${c[2]} · ${shortDate(date)}` });
      known.add(c[0]);
    }
    updates.push({ who: r.who, person: r.person, name: p.name || '', coming: JSON.stringify(coming), known: JSON.stringify([...known]) });
  }
  if (told.length) {
    await env.DB.prepare(`INSERT INTO news (who, key, t, card, head, line) SELECT value->>'who', value->>'key', value->>'t',
        value->>'card', value->>'head', value->>'line' FROM json_each(?) WHERE true ON CONFLICT (who, key) DO NOTHING`)
      .bind(JSON.stringify(told)).run();
  }
  if (updates.length) {
    await env.DB.prepare(`UPDATE follows SET name = u.value->>'name', coming = u.value->>'coming', known = u.value->>'known'
        FROM json_each(?) u WHERE follows.who = u.value->>'who' AND follows.person = u.value->>'person'`)
      .bind(JSON.stringify(updates)).run();
  }
}

// The genres /discover/<slug> takes: [TMDB's film genre id, its series genre id or a keyword]. TMDB keeps films' and
// series' genres apart (a series has Sci-Fi & Fantasy, no Horror): series of a genre they lack come by TMDB's keyword
// (horror: Supernatural, Stranger Things; thriller: You, Mindhunter), without anime (keywords bring Death Note to
// horror, Ranma to romance; Animation has its page). (The app's genre page once had a /genre/<slug> of its own, 30
// TMDB calls: /discover with its filters replaced it, and nothing called it.)
const GENRE_PAGES = {
  action: [28, 10759], adventure: [12, 10759], animation: [16, 16], comedy: [35, 35], crime: [80, 80],
  documentary: [99, 99], drama: [18, 18], family: [10751, 10751], fantasy: [14, 10765],
  history: [36, 'with_keywords=15126'], horror: [27, 'with_keywords=315058'], kids: [0, 10762],
  music: [10402, 'with_keywords=283297'], mystery: [9648, 9648], romance: [10749, 'with_keywords=9840'],
  'science-fiction': [878, 10765], thriller: [53, 'with_keywords=316362'], war: [10752, 10768], western: [37, 37],
};

// GET /discover/<genre slug>?type=movie|tv&years=<decade: 2010, or "older": before 1980>&country=<ISO code>&score=<60-90>
// &sort=best|popular|new&page=<n> -> {cards: [Home card], pages}: a genre with filters, TMDB's
// discover, 20 a page. Best rated: a floor of 300 votes (a decade or a country holds fewer well-known titles), popular: 50, new: the newest out first (10). Nothing not out yet, no talk shows; series of a genre
// TMDB lacks by keyword (GENRE_PAGES). A day in the edge cache.
const SORTS = { best: ['vote_average.desc', 300], popular: ['popularity.desc', 50], new: ['DATE.desc', 10] };
async function discover(request, env, slug) {
  const q = new URL(request.url).searchParams;
  const type = q.get('type') === 'tv' ? 'tv' : 'movie';
  const id = GENRE_PAGES[slug]?.[type === 'movie' ? 0 : 1];
  if (!id) return corsError(request, 'No such genre.', 404);
  const date = type === 'movie' ? 'primary_release_date' : 'first_air_date';
  const today = new Date().toISOString().slice(0, 10);
  const years = q.get('years') || '';
  const decade = /^(19[89]0|20[0-2]0)$/.test(years) ? Number(years) : 0;
  const lte = years === 'older' ? '1979-12-31' : decade ? [`${decade + 9}-12-31`, today].sort()[0] : today;
  const [sort, votes] = SORTS[Object.hasOwn(SORTS, q.get('sort')) ? q.get('sort') : 'best'];
  let query = `&include_adult=false&${typeof id === 'number' ? `with_genres=${id}` : id}&without_genres=`
    + `${(typeof id === 'number' ? TALK : [...TALK, 16]).join('|')}&${date}.lte=${lte}&sort_by=${sort.replace('DATE', date)}`
    + `&vote_count.gte=${votes}&page=${Math.min(500, Math.max(1, Number(q.get('page')) || 1))}`;
  if (decade) query += `&${date}.gte=${decade}-01-01`;
  if (/^[A-Z]{2}$/.test(q.get('country') || '')) query += `&with_origin_country=${q.get('country')}`;
  if (/^[5-9]0$/.test(q.get('score') || '')) query += `&vote_average.gte=${Number(q.get('score')) / 10}`;
  const res = await tmdb(env, `discover/${type}`, query);
  if (!res.ok) return corsError(request, `TMDB ${res.status}`, 502); // not cached: the next ask tries again
  const o = await res.json();
  return new Response(JSON.stringify({ cards: (o.results || []).filter((m) => m.poster_path).map((m) => cardOf(m, type)),
    pages: Math.min(500, o.total_pages || 0) }), {
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'max-age=86400', ...corsHeaders(request) },
  });
}

// GET /torbox (the stick's X-Play-Token) -> {plan: "Pro", torrents: 37}: the TorBox account the worker plays from,
// for the app's Settings. TorBox down → 502.
const PLANS = ['Free', 'Essential', 'Pro', 'Standard'];
async function torboxAccount(request, env) {
  if (!isStick(request, env)) return corsError(request, 'Forbidden.', 403);
  try {
    const [me, list] = await Promise.all([torboxApi(env, '../user/me'), torboxApi(env, 'mylist?bypass_cache=true')]);
    if (!me.data) throw new Error('no account');
    return new Response(JSON.stringify({ plan: PLANS[me.data.plan] || String(me.data.plan), torrents: (list.data || []).length }), {
      headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...corsHeaders(request) },
    });
  } catch {
    return corsError(request, 'TorBox unavailable.', 502);
  }
}

// POST /torbox/drop/<info hash> (the stick's token): the torrent with that hash leaves the TorBox account, for a tool
// that added one it no longer needs (a release it picked and then passed over). -> {deleted: true|false} (false: not
// in the account); TorBox refusing or down -> 502.
async function torboxDrop(request, env, hash) {
  if (!isStick(request, env)) return corsError(request, 'Forbidden.', 403);
  try {
    const t = (await accountTorrents(env)).find((x) => String(x.hash).toLowerCase() === hash);
    if (t) {
      const r = await torboxApi(env, 'controltorrent', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ torrent_id: t.id, operation: 'delete' }) });
      if (r?.success !== true) throw new Error('refused');
    }
    await env.DB?.prepare('DELETE FROM added WHERE hash = ?').bind(hash).run(); // the night has nothing left to do
    return new Response(JSON.stringify({ deleted: Boolean(t) }), {
      headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...corsHeaders(request) } });
  } catch {
    return corsError(request, 'TorBox unavailable.', 502);
  }
}

// GET /find/<IMDb id> -> {card: Home card | null}: the film or series with that IMDb id (TMDB's find), for
// importing IMDb's ratings export (imdb-import.py) into the grades. A day in the edge cache.
async function findImdb(request, env, imdb) {
  const res = await tmdb(env, `find/${imdb}`, '&external_source=imdb_id');
  if (!res.ok) return corsError(request, `TMDB ${res.status}`, 502);
  const o = await res.json();
  const m = (o.movie_results || [])[0] || (o.tv_results || [])[0];
  const card = m ? cardOf(m, (o.movie_results || [])[0] ? 'movie' : 'tv') : null;
  return new Response(JSON.stringify({ card }), {
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'max-age=86400', ...corsHeaders(request) },
  });
}

// GET /segments/movie/<tmdb id>/<imdb id> or /segments/tv/<tmdb id>/<imdb id>?e=1:9,1:10 (6 at most) -> what DDD can
// skip, in our DDD fork's "segments" format: {"1:9": "intro:0-58867;credits:3395489-"} (ms; no end: to the end of the
// video; a film's under "0:0"; none: left out). From IntroDB (introdb.app, by IMDb id) and TheIntroDB (theintrodb.org,
// by TMDB id): community timings, often one person's. Where they differ, the intro (recap) that ends first and the
// credits that start last: a wrong one leaves a few seconds of intro, never cuts the story. Kept in D1 (segments.sql)
// a week, a day when neither has any (they fill in); not when one was down. TheIntroDB without a key allows 5000
// asks a day per address, and Cloudflare's are shared (429): THEINTRODB_API_KEY (wrangler secret) lifts that. For the
// stick (X-Play-Token).
const SEGMENTS_MAX = 6;
const SEGMENTS_KEEP = 7 * 86400e3;
const SEGMENTS_NONE_KEEP = 86400e3;

// IntroDB's {intro, recap, outro: {start_ms, end_ms}} and TheIntroDB's {intro, recap, credits: [{start_ms, end_ms}]}
// (start null: from 0; end null: to the end) -> "intro:0-58867;credits:3395489-" ('' when none)
function mergeSegments(introdb, theintrodb) {
  const out = [];
  for (const kind of ['intro', 'recap', 'credits']) {
    const a = introdb?.[kind === 'credits' ? 'outro' : kind];
    const all = [...(a && typeof a === 'object' ? [a] : []), ...(Array.isArray(theintrodb?.[kind]) ? theintrodb[kind] : [])]
      .map((x) => [Math.round(Number(x.start_ms) || 0), x.end_ms == null ? null : Math.round(Number(x.end_ms))])
      .filter(([s, e]) => (kind === 'credits' ? s > 0 && (e === null || e > s) : e !== null && e > s));
    if (!all.length) continue;
    const [s, e] = all.reduce((m, x) => (kind === 'credits' ? (x[0] > m[0] ? x : m) : (x[1] < m[1] ? x : m)));
    out.push(`${kind}:${s}-${e ?? ''}`);
  }
  return out.join(';');
}

// One film's or episode's from both: {value, ok} (ok: both answered, 404 included: worth keeping)
async function fetchSegments(env, tmdb, imdb, season, episode) {
  const ask = (url, headers = {}) => fetch(url, { headers, signal: AbortSignal.timeout(4000) })
    .then((r) => (r.ok ? r.json() : r.status === 404 ? {} : (console.log('segments', r.status, url), null)))
    .catch((e) => (console.log('segments', String(e), url), null));
  const ep = `&season=${season}&episode=${episode}`;
  const [a, b] = await Promise.all([
    ask(`https://api.introdb.app/segments?imdb_id=${imdb}${season ? ep : '&is_movie=true'}`),
    ask(`https://api.theintrodb.org/v4/media?tmdb_id=${tmdb}${season ? ep : ''}`,
      env.THEINTRODB_API_KEY ? { Authorization: `Bearer ${env.THEINTRODB_API_KEY}` } : {}),
  ]);
  return { value: mergeSegments(a, b), ok: a !== null && b !== null };
}

async function segments(request, env, type, tmdb, imdb) {
  if (!isStick(request, env)) return corsError(request, 'Forbidden.', 403);
  const eps = type === 'movie' ? [[0, 0]] : (new URL(request.url).searchParams.get('e') || '').split(',')
    .map((x) => x.match(/^(\d{1,2}):(\d{1,3})$/)).filter(Boolean).map((m) => [Number(m[1]), Number(m[2])])
    .slice(0, SEGMENTS_MAX);
  const key = ([s, e]) => `${type}/${tmdb}/${s}/${e}`;
  const now = Date.now();
  const kept = new Map();
  if (env.DB && eps.length) {
    try {
      const rows = (await env.DB.prepare('SELECT key, value, t FROM segments WHERE key IN (SELECT value FROM json_each(?))')
        .bind(JSON.stringify(eps.map(key))).all()).results;
      for (const r of rows) if (r.t > now - (r.value ? SEGMENTS_KEEP : SEGMENTS_NONE_KEEP)) kept.set(r.key, r.value);
    } catch { /* no cache beats no answer */ }
  }
  const got = await Promise.all(eps.filter((x) => !kept.has(key(x)))
    .map(async (x) => ({ k: key(x), ...(await fetchSegments(env, tmdb, imdb, x[0], x[1])) })));
  const fresh = got.filter((g) => g.ok);
  if (env.DB && fresh.length) {
    await env.DB.prepare(`INSERT INTO segments (key, value, t) SELECT j.value->>'k', j.value->>'v', ?
      FROM json_each(?) AS j WHERE true ON CONFLICT (key) DO UPDATE SET value = excluded.value, t = excluded.t`)
      .bind(now, JSON.stringify(fresh.map((g) => ({ k: g.k, v: g.value })))).run().catch(() => {});
  }
  for (const g of got) kept.set(g.k, g.value);
  const own = type === 'tv' ? await ownIntros(request, env, eps, now) : new Map();
  const body = {};
  for (const x of eps) {
    const value = withOwnIntro(kept.get(key(x)) || '', own.get(`${x[0]}:${x[1]}`));
    if (value) body[`${x[0]}:${x[1]}`] = value;
  }
  return new Response(JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...corsHeaders(request) },
  });
}

// Our own intro times (D1 intros), found by home-server/intro-detect.py in the very release played: the stretch
// of identical sound two episodes share in their first 4 minutes, their opening titles. Community timings are often
// a guess (Foundation: "0-84 s" in every episode, while S1 E9's titles run 31.8-113.9 s after a recap). Asked with
// the release (?h=<hash>&f=<file>&n=<name>&o=<original series name>, lampa-native sends them); episodes with none
// yet queue a job (D1 intro_jobs) for the detector: the one played and the 5 after it.
async function ownIntros(request, env, eps, now) {
  const q = new URL(request.url).searchParams;
  const hash = q.get('h') || '';
  const own = new Map();
  if (!env.DB || !/^[0-9a-f]{40}$/.test(hash) || !eps.length) return own;
  try {
    const rows = (await env.DB.prepare('SELECT key, start_ms, end_ms, recap_ms FROM intros WHERE key IN (SELECT value FROM json_each(?))')
      .bind(JSON.stringify(eps.map(([s, e]) => `${hash}/${s}/${e}`))).all()).results;
    for (const r of rows) own.set(r.key.split('/').slice(1).join(':'), [r.start_ms, r.end_ms, r.recap_ms]);
    if (eps.some(([s, e]) => !own.has(`${s}:${e}`)) && q.get('n')) {
      const [s, e] = eps[0];
      await env.DB.prepare(`INSERT INTO intro_jobs (key, file, name, series, t) VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (key) DO UPDATE SET t = excluded.t`)
        .bind(`${hash}/${s}/${e}`, Number(q.get('f') ?? -1), q.get('n'), q.get('o') || '', now).run();
    }
  } catch { /* the community's times then */ }
  return own;
}

// "intro:0-84000;credits:3390000-" with our own intro [31800, 113900] -> "intro:31800-113900;credits:3390000-": theirs
// (and a recap overlapping ours) dropped. With our recap ([31800, 113900, 31800]: "Previously on…" in the release's
// subtitles, from 0 to the intro) -> "recap:0-31800;intro:31800-113900;credits:3390000-", theirs dropped.
function withOwnIntro(value, intro) {
  if (!intro?.[1]) return value; // [0, 0]: the detector found none (a finale's own opening), theirs then
  const [start, end, recap] = intro;
  const rest = value.split(';').filter((p) => p && !p.startsWith('intro:') && !(p.startsWith('recap:') && (recap
    || Number(p.slice(6).split('-')[0]) < end && Number(p.slice(6).split('-')[1]) > start)));
  return [...recap ? [`recap:0-${recap}`] : [], `intro:${start}-${end}`, ...rest].join(';');
}

// GET /segments/jobs -> {jobs: [{key: "<hash>/<season>/<episode>", file, name, series, have: ["1:9", …]}]}, the
// oldest 5; POST /segments/own {intros: [{hash, season, episode, start, end, recap?}] (ms; 0-0 when none found, so the
// episode isn't queued again; recap: a recap's end, from 0, at most the intro's start), done: [<job key>]} -> 204.
// The detector's (X-Play-Token).
async function introJobs(request, env) {
  if (!isStick(request, env)) return corsError(request, 'Forbidden.', 403);
  const json = { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...corsHeaders(request) };
  if (request.method === 'GET') {
    const jobs = (await env.DB.prepare('SELECT key, file, name, series FROM intro_jobs ORDER BY t LIMIT 5').all()).results;
    for (const j of jobs) {
      j.have = (await env.DB.prepare("SELECT key FROM intros WHERE key LIKE ?").bind(`${j.key.split('/')[0]}/%`).all())
        .results.map((r) => r.key.split('/').slice(1).join(':'));
    }
    return new Response(JSON.stringify({ jobs }), { headers: json });
  }
  const body = await request.json().catch(() => ({}));
  const intros = (Array.isArray(body.intros) ? body.intros : []).filter((x) => x && /^[0-9a-f]{40}$/.test(x.hash)
    && [x.season, x.episode, x.start, x.end].every(Number.isInteger) && x.start >= 0 && (x.end > x.start || x.end === 0));
  if (intros.length) {
    await env.DB.prepare(`INSERT INTO intros (key, start_ms, end_ms, recap_ms, t) SELECT j.value->>'k', j.value->>'s',
      j.value->>'e', j.value->>'r', ? FROM json_each(?) AS j WHERE true ON CONFLICT (key) DO UPDATE SET
      start_ms = excluded.start_ms, end_ms = excluded.end_ms, recap_ms = excluded.recap_ms, t = excluded.t`)
      .bind(Date.now(), JSON.stringify(intros.map((x) => ({ k: `${x.hash}/${x.season}/${x.episode}`, s: x.start, e: x.end,
        r: Number.isInteger(x.recap) && x.recap > 0 && x.recap <= x.start ? x.recap : null })))).run();
  }
  const done = (Array.isArray(body.done) ? body.done : []).filter((k) => typeof k === 'string');
  if (done.length) {
    await env.DB.prepare('DELETE FROM intro_jobs WHERE key IN (SELECT value FROM json_each(?))').bind(JSON.stringify(done)).run();
  }
  return new Response(null, { status: 204, headers: corsHeaders(request) });
}

// GET /streams/movie/<imdb id> or /streams/series/<imdb id>/<season>/<episode>[?lang=RU,UA][&from=6] -> lampa-native's
// sources: {streams: [[hash, fileIdx, title, badges, size, seeders, tracker, languages, cached, audio, maxvol]], next, total,
// languages: [[code, releases]]}. Torrentio's list (Cloudflare caches it: 3-8 ms from here), each torrent once,
// only the asked audio languages (none = all), biggest first, whole discs last. TorBox's cache is checked for the first FIRST only
// (5 rows fit on the stick's screen, measured; +1 half seen) so they come fast; the app asks from=FIRST for the rest.
// cached: 1 yes, 0 no, -1 unknown (no TORBOX_API_KEY secret, or TorBox down). audio: its tracks when read before
// (GET /audio), else null. maxvol: its tracks as MaxVol has them (maxvolAudio), else null: both kept side by side to
// compare which tells the truth.
// POST, the same path: Torrentio's answer in the body, from the stick. Torrentio answers here 429 at times
// (Cloudflare's IPs are shared and rate-limited) while the stick's home IP gets it: the app then asks Torrentio
// itself and posts that here (≤ 4 MB; the stick's X-Play-Token only: a list spends TorBox checks on any hashes).
const FIRST = 6;
const MAX = 100;
// One badge per group, the first that matches: resolution, Dolby Vision, HDR, REMUX, codec, audio.
const BADGES = [
  [['4K', /\b(2160p|4k|uhd)\b/i], ['1080p', /\b1080p\b/i], ['720p', /\b720p\b/i]],
  [['DV', /\b(dv|dovi|dolby[ .]?vision)\b/i]],
  [['HDR10+', /hdr10(\+|plus)/i], ['HDR', /\bhdr(10)?\b/i]],
  [['REMUX', /remux/i]],
  [['HEVC', /\b(hevc|[xh]\.?265)\b/i], ['AV1', /\bav1\b/i]],
  [['Atmos', /atmos/i]],
  [['TrueHD', /truehd/i]],
  [['DTS', /\bdts/i]],
  [['DD+', /\b(ddp|dd\+|e-?ac-?3)/i], ['DD', /\b(ac-?3|dd[ .]?[257]\.[01])\b/i]],
];
const UNIT = { K: 1e3, M: 1e6, G: 1e9, T: 1e12 };
// Whole Blu-ray discs (BDMV folders of .m2ts) don't play in DDD (UnrecognizedInputFormat, The Dark Knight's 67.9 GB
// "UHD Blu-ray disc"): listed last, badged "Disc". Torrentio gives no file list, so the name tells: said outright, or a
// scene name of an untouched disc ("2160p.BluRay.HEVC.DTS-HD.MA.5.1-TASTED": a disc's codec, not a remux, not encoded),
// which needs 15 GB+ (a "BluRay.HEVC.10bit" encode is a few GB). A single video file (Torrentio's file name) is never one.
// ponytail: names only; TorBox's file list (checkcached list_files) would be exact for cached torrents, if names miss.
const DISC = /blu-?ray[ ._-]?disc|full[ ._-]?blu-?ray|complete[ ._-]+(uhd[ ._-]+)?blu-?ray|\bbdmv\b|\bbd(25|50|66|100)\b|\.iso\b/i;
const isDisc = (text, bytes, filename) => !/\.(mkv|mp4|avi|webm|mov)$/i.test(filename || '') && (DISC.test(text)
  || (bytes >= 15e9 && /\bblu-?ray\b/i.test(text) && /\b(avc|hevc|vc-?1|mpeg-?2)\b/i.test(text)
    && !/remux|x26[45]|\bweb|rip\b/i.test(text)));

// Torrentio marks audio languages with country flags (🇬🇧 for English); no flags = an English release.
function languagesOf(line) {
  const codes = new Set();
  for (const [, a, b] of line.matchAll(/([\u{1F1E6}-\u{1F1FF}])([\u{1F1E6}-\u{1F1FF}])/gu)) {
    const code = String.fromCharCode(a.codePointAt(0) - 0x1f1a5, b.codePointAt(0) - 0x1f1a5);
    codes.add(code === 'GB' ? 'EN' : code);
  }
  return codes.size ? [...codes] : ['EN'];
}

async function cachedOnTorBox(env, hashes) {
  if (!env.TORBOX_API_KEY || !hashes.length) return null;
  try {
    const res = await fetch(`https://${TORBOX_HOST}/v1/api/torrents/checkcached?hash=${hashes.join(',')}` +
      '&format=list&list_files=false', { headers: { Authorization: `Bearer ${env.TORBOX_API_KEY}` }, ...within() }); // not in the URL
    if (!res.ok) return null;
    return new Set(((await res.json()).data || []).map((t) => String(t.hash).toLowerCase()));
  } catch {
    return null;
  }
}

async function sources(request, env, ctx, kind, imdb, season, episode) {
  // the TV's only: each answer spends TorBox's cache checks and the addon's patience
  if (!isStick(request, env)) return corsError(request, 'Forbidden.', 403);
  let list;
  if (request.method === 'POST') {
    if (Number(request.headers.get('Content-Length')) > 4e6) return corsError(request, 'Too big.', 413);
    const text = await request.text();
    if (text.length > 4e6) return corsError(request, 'Too big.', 413);
    try {
      list = JSON.parse(text).streams;
    } catch {
      // not JSON: refused below
    }
    if (!Array.isArray(list)) return corsError(request, 'Not the addon\'s answer.', 400);
  } else {
    if (!env.STREAM_ADDON) return corsError(request, 'No streams addon set: npx wrangler@4 secret put STREAM_ADDON.', 503);
    try {
      const id = kind === 'series' ? `${imdb}:${season}:${episode}` : imdb;
      const res = await fetch(addonStreams(env, kind, id), within(10000));
      if (!res.ok) throw new Error(String(res.status));
      list = (await res.json()).streams || [];
    } catch (e) {
      console.log('addon', kind, String(e)); // wrangler tail: its status (429: rate-limited) or the parse error
      return corsError(request, 'The streams addon is unavailable.', 502); // the app then asks it itself (/addon)
    }
  }
  const seen = new Set();
  const all = [];
  for (const st of list) {
    if (!st || typeof st !== 'object') continue; // the addon's answer, or the stick's post of it: not trusted
    const hash = String(st.infoHash || '').toLowerCase();
    if (!/^[0-9a-f]{40}$/.test(hash) || seen.has(hash)) continue;
    seen.add(hash);
    // title: "<torrent name>\n[<file in it>\n]👤 <seeders> 💾 <size> ⚙️ <tracker>[\n<language flags>]"
    const lines = String(st.title || '').split('\n');
    const at = lines.findIndex((l) => l.includes('👤'));
    const stats = at >= 0 ? lines[at] : '';
    const size = (stats.match(/💾\s*([\d.]+\s*[KMGT]B)/) || [])[1] || '';
    const filename = st.behaviorHints?.filename;
    const text = `${st.name || ''} ${lines[0]} ${filename || ''}`;
    const bytes = parseFloat(size) * (UNIT[(size.match(/([KMGT])B/) || [])[1]] || 0) || 0;
    const disc = isDisc(text, bytes, filename);
    const badges = [...disc ? ['Disc'] : [], ...BADGES.map((group) => group.find(([, re]) => re.test(text))?.[0])]
      .filter(Boolean);
    all.push({
      bytes,
      disc,
      row: [hash, st.fileIdx ?? -1, filename || lines[0], badges, size, Number((stats.match(/👤\s*(\d+)/) || [])[1]) || 0,
        ((stats.match(/⚙️\s*(.+)/) || [])[1] || '').trim(), languagesOf((at >= 0 && lines[at + 1]) || '')],
    });
  }
  const languages = new Map();
  for (const { row } of all) for (const code of row[7]) languages.set(code, (languages.get(code) || 0) + 1);
  const params = new URL(request.url).searchParams;
  const wanted = new Set((params.get('lang') || '').split(',').filter(Boolean));
  const shown = all.filter(({ row }) => !wanted.size || row[7].some((code) => wanted.has(code)))
    .sort((a, b) => a.disc - b.disc || b.bytes - a.bytes).slice(0, MAX); // stable: equal sizes keep Torrentio's order
  const from = Number(params.get('from')) || 0;
  const batch = shown.slice(from, from ? MAX : FIRST).map(({ row }) => row);
  const hashes = batch.map(([hash]) => hash);
  // MaxVol: D1's answer (a few ms) when it has one; a first ask of the day (~0.7-2 s) isn't waited for past 300 ms,
  // it goes on (waitUntil) and the next list carries it
  const mv = env.DB ? maxvolOf(env, imdb).catch(() => ({})) : Promise.resolve({});
  if (ctx) ctx.waitUntil(mv);
  const [cached, audioOf, maxvol] = await Promise.all([cachedOnTorBox(env, hashes), audioKnown(env, hashes),
    Promise.race([mv, new Promise((done) => setTimeout(() => done({}), 300))])]);
  for (const row of batch) row.push(cached ? Number(cached.has(row[0])) : -1);
  for (const row of batch) row.push(audioOf.get(row[0]) ?? null, maxvol[row[0]] ?? null);
  return new Response(JSON.stringify({
    streams: batch,
    next: !from && shown.length > FIRST ? FIRST : 0,
    total: shown.length,
    languages: [...languages].sort((a, b) => b[1] - a[1]),
  }), {
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'max-age=600', // what TorBox has cached changes slowly
      ...corsHeaders(request),
    },
  });
}

// GET /play/<info hash>?file=<Torrentio's fileIdx>&name=<its file name>&tl=<Lampa timeline hash> with header
// X-Play-Token -> {url, time, percent} for DDD (time: the resume point in seconds from /timeline, 0 = none), or
// 202 {progress} while TorBox is still downloading it. Adds the torrent to the TorBox account (one already there
// is found in the list), picks the file Torrentio meant (by name, else its index, else the biggest video) and asks
// for its link. Only for the stick: the route spends the account, so it needs the secret PLAY_TOKEN, which the app
// keeps in its private storage (tools/connect.sh). Links expire: never cached.
// &series=<original name> (an episode): also {playlist: [[season, episode, key, file name, link], ...]} from it on,
// and url is the first one's link (episodesFrom).
const TORBOX_API = 'https://api.torbox.app/v1/api/torrents/';
const VIDEO = /\.(mkv|mp4|m4v|avi|mov|webm|ts|m2ts|wmv)$/i;

function sameSecret(a, b) {
  if (!a || !b || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i); // no early exit: constant time
  return diff === 0;
}

// The request carries the stick's play token (the app's Net.open adds it to every worker address).
const isStick = (request, env) => sameSecret(request.headers.get('X-Play-Token'), env.PLAY_TOKEN);

// The account's torrents; throws unless TorBox answered with the list (a 401 or 429 is not an empty account).
async function accountTorrents(env) {
  const list = await torboxApi(env, 'mylist?bypass_cache=true');
  if (!Array.isArray(list?.data)) throw new Error('TorBox list');
  return list.data;
}

async function torboxApi(env, path, init = {}) {
  const res = await fetch(TORBOX_API + path, { ...within(10000), ...init, headers: { ...init.headers, Authorization: `Bearer ${env.TORBOX_API_KEY}` } });
  if (!res.ok && res.status >= 500) throw new Error(`TorBox ${res.status}`);
  return res.json();
}

async function resumePoint(env, tl) {
  if (!tl || !env.DB) return { time: 0, percent: 0 };
  try {
    const t = await env.DB.prepare('SELECT time, percent FROM timeline WHERE hash = ?').bind(tl).first();
    return { time: t?.time ?? 0, percent: t?.percent ?? 0 };
  } catch {
    return { time: 0, percent: 0 }; // no resume point beats no film
  }
}

// TorBox's link to a release's file: adds the torrent to the account (one already there is found in the list; mine:
// its id, known: not added) and picks the file Torrentio meant (by name, else its index, else the biggest video).
// {id, files, file, link}, {id, progress} while TorBox still downloads it, or {error, status}. Throws when TorBox is
// down.
async function torboxLink(env, hash, fileIdx, name, mine) {
  let id = mine;
  let answer;
  if (id === undefined) {
    const form = new FormData();
    form.append('magnet', `magnet:?xt=urn:btih:${hash}`);
    answer = await torboxApi(env, 'createtorrent', { method: 'POST', body: form });
    id = answer?.data?.torrent_id ?? answer?.data?.id;
  }
  if (id === undefined) { // already in the account (or refused): look for it there
    const all = await torboxApi(env, 'mylist?bypass_cache=true');
    id = (all?.data || []).find((t) => String(t.hash).toLowerCase() === hash)?.id;
  }
  if (id === undefined) return { error: answer?.detail || answer?.error || 'TorBox refused it.', status: 502 };
  try {
    return await linkIn(env, id, fileIdx, name);
  } catch (e) {
    if (mine === undefined) return { id, error: 'TorBox unavailable.', status: 503 }; // our add: still recorded
    throw e;
  }
}

async function linkIn(env, id, fileIdx, name) {
  const t = (await torboxApi(env, `mylist?id=${id}&bypass_cache=true`))?.data;
  if (!t) return { id, error: 'TorBox lost it.', status: 502 }; // id: an add of ours still leaves at night
  if (!t.download_present) return { id, progress: Math.round((t.progress || 0) * 100) };
  const files = t.files || [];
  const file = (name && files.find((f) => f.short_name === name || String(f.name).split('/').pop() === name))
    || files.find((f) => f.id === fileIdx && VIDEO.test(f.name))
    || files.filter((f) => VIDEO.test(f.name)).sort((a, b) => b.size - a.size)[0];
  if (!file) return { id, error: 'No video in it.', status: 404 };
  // requestdl takes the key as a parameter (it is built for browser redirects); server to server only
  const dl = await torboxApi(env, `requestdl?token=${encodeURIComponent(env.TORBOX_API_KEY)}&torrent_id=${id}&file_id=${file.id}`);
  if (typeof dl?.data !== 'string') return { id, error: dl?.detail || 'No link.', status: 502 };
  return { id, files, file, link: dl.data };
}

async function play(request, env, ctx, hash) {
  if (!isStick(request, env)) return corsError(request, 'Forbidden.', 403);
  const q = new URL(request.url).searchParams;
  const resume = resumePoint(env, q.get('tl')); // alongside TorBox's three calls
  // played: a torrent a probe added stays in the account (dropProbeAdds)
  const played = env.DB?.prepare('INSERT OR REPLACE INTO plays (hash, t) VALUES (?, ?)').bind(hash, Date.now()).run()
    .catch(() => {});
  try {
    const got = await torboxLink(env, hash, Number(q.get('file') ?? -1), q.get('name') || '');
    await played;
    if (got.error) return corsError(request, got.error, got.status);
    if (got.progress !== undefined) {
      return new Response(JSON.stringify({ progress: got.progress }), {
        status: 202, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...corsHeaders(request) },
      });
    }
    // its audio for the source chips, with the link in hand: no TorBox add spent on it
    // (in a call of its own when it can: up to 24 range reads and the frame walks on top of Play's work could pass the
    // free plan's 10 ms of CPU and fail Play itself)
    if (env.DB && ctx) {
      ctx.waitUntil(env.SELF ? env.SELF.fetch(`https://self/audio/${hash}?file=${got.file.id}&name=${encodeURIComponent(
        got.file.short_name || '')}`, { headers: { 'X-Play-Token': env.PLAY_TOKEN } }).catch(() => {})
        : rememberAudio(env, hash, got.link, true));
    }
    const playlist = q.has('series')
      ? await episodesFrom(request, env, ctx, got.id, got.files, got.file, q.get('series'), got.link, q.has('probe')) : [];
    return new Response(JSON.stringify(playlist.length
      ? { url: playlist[0][4], ...(await resume), playlist } : { url: got.link, ...(await resume) }), {
      headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...corsHeaders(request) },
    });
  } catch {
    return corsError(request, 'TorBox unavailable.', 502);
  }
}

// GET /audio/<info hash>?file=<Torrentio's fileIdx>&name=<its file name> with header X-Play-Token -> {tracks: [[lang,
// codec, channels, atmos, name], ...]} (audioTracks): a cached release's audio, for the chips on its source row. Read
// once and kept (the audio table; Play reads the one played for free). Reading one nobody played spends a TorBox add,
// so at most PROBES_MINUTE a minute and PROBES_HOUR an hour (TorBox allows ~10 a minute and ~50 an hour, and Play
// needs its own): 429 {later: true} past that.
const PROBES_MINUTE = 4;
const PROBES_HOUR = 20;
// and at most what the night's runs take out of the account again (3 runs x DROPS), so adds never pile up there
const PROBES_DAY = () => 3 * (DROPS - 1);

async function audio(request, env, hash) {
  if (!isStick(request, env)) return corsError(request, 'Forbidden.', 403);
  if (!env.DB) return corsError(request, 'No database.', 503);
  const json = (body, status = 200) => new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...corsHeaders(request) },
  });
  const now = Date.now();
  const known = await audioRow(env, hash);
  if (settled(known)) {
    return known.tracks !== 'null' ? json({ tracks: JSON.parse(known.tracks) })
      : corsError(request, 'Its audio couldn\'t be read today.', 502);
  }
  const q = new URL(request.url).searchParams;
  let got;
  let mine;
  try {
    // in the account already (played before): read as it is, no add (none of the budget), and left there
    mine = (await accountTorrents(env)).find((t) => String(t.hash).toLowerCase() === hash)?.id;
    if (mine === undefined) {
      const spent = await env.DB.prepare('SELECT SUM(t > ?) minute, SUM(t > ?) hour, COUNT(*) day FROM probes WHERE t > ?')
        .bind(now - 60_000, now - 3_600_000, now - 86_400_000).first();
      if ((spent?.minute || 0) >= PROBES_MINUTE || (spent?.hour || 0) >= PROBES_HOUR
        || (spent?.day || 0) >= PROBES_DAY()) return json({ later: true }, 429);
      await env.DB.prepare('DELETE FROM probes WHERE t <= ?').bind(now - 86_400_000).run();
    }
    got = await torboxLink(env, hash, Number(q.get('file') ?? -1), q.get('name') || '', mine);
  } catch {
    return corsError(request, 'TorBox unavailable.', 502);
  }
  // our add: out of the account again at night unless played by then (dropProbeAdds), not now: the user may be
  // pressing OK on this very row
  if (mine === undefined && got.id !== undefined) { // an add happened: it spends the budget
    await env.DB.prepare('INSERT INTO probes (t) VALUES (?)').bind(now).run();
    await env.DB.prepare('INSERT OR REPLACE INTO added (hash, id, t) VALUES (?, ?, ?)').bind(hash, got.id, now).run();
  }
  const tracks = got.link ? await rememberAudio(env, hash, got.link) : null;
  if (got.error && got.status !== 503) { // no video in it (a disc), lost, no link: unreadable, settled for a day
    await env.DB.prepare("INSERT OR REPLACE INTO audio (hash, tracks, t) VALUES (?, 'null', ?)").bind(hash, now).run();
  }
  if (!got.link) return corsError(request, got.error || 'Not on TorBox yet.', 502);
  return tracks ? json({ tracks }) : corsError(request, 'Couldn\'t read its audio.', 502);
}

const RETRY_UNREADABLE = 86_400_000; // a file whose audio couldn't be read: asked again a day later
const audioRow = (env, hash) => env.DB.prepare('SELECT tracks, t FROM audio WHERE hash = ?').bind(hash).first();
// read (tracks), or unreadable less than RETRY_UNREADABLE ago: not read again
const settled = (k) => Boolean(k) && (k.tracks !== 'null' || Date.now() - k.t < RETRY_UNREADABLE);

// The torrents probes added (audio), taken out of the account in the night's quiet hours (the hourly cron at 01-03
// UTC, DROPS a run beside For you's 25 and follows' FOLLOWS_FETCH of the 50 subrequests) unless played since: then
// they're the user's. A delete TorBox refused (or down) stays for the next run. ponytail: check, then delete; a Play
// of that very torrent in between (at 4 am) could lose its link.
const DROPS = 50 - 25 - FOLLOWS_FETCH - 2;
async function dropProbeAdds(env, now) {
  await env.DB.prepare('DELETE FROM added WHERE EXISTS (SELECT 1 FROM plays p WHERE p.hash = added.hash AND '
    + 'p.t >= added.t)').run(); // played since: the user's now
  await env.DB.prepare('DELETE FROM plays WHERE t < ?').bind(now - 7 * 86_400_000).run();
  const { results } = await env.DB.prepare(`SELECT hash, id FROM added WHERE t < ? ORDER BY t LIMIT ${DROPS - 1}`)
    .bind(now - 3_600_000).all();
  if (!results.length) return;
  // one subrequest for the account's list: a row whose torrent already left it (deleted by hand, /torbox/drop, expired)
  // is done, so it never blocks the queue (DROPS - 1 deletes beside it)
  const have = new Set((await accountTorrents(env)).map((t) => t.id));
  const done = await Promise.all(results.map(({ id }) => !have.has(id) || torboxApi(env, 'controltorrent', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ torrent_id: id, operation: 'delete' }),
  }).then((a) => a?.success === true, () => false)));
  for (const [i, { hash }] of results.entries()) {
    if (done[i]) await env.DB.prepare('DELETE FROM added WHERE hash = ?').bind(hash).run();
  }
}

// MaxVol (jr.maxvol.pro, a Jacred of Russian trackers: what Lampa's torbox plugin searches) lists TorrServer's ffprobe
// of most releases it has, so their audio tracks without reading anything: ~90% of Torrentio's Rutor/Rutracker rows,
// none of the others (Wakanda Forever 17/19 and 0/50, Dune: Part Two 41/44 and 0/97; 2026-11-09). No Atmos in it (a
// track named so only). A film's hash -> tracks (as audioTracks'), kept a day in D1 (an hour when it had none or
// failed). Its search wants a title: TMDB's find by the IMDb id, the original one when it's in Latin letters.
const MAXVOL = 'https://jr.maxvol.pro/api/v2.0/indexers/all/results';
const FF_CODECS = { truehd: 'TrueHD', eac3: 'DD+', ac3: 'DD', aac: 'AAC', flac: 'FLAC', opus: 'Opus', mp3: 'MP3',
  vorbis: 'Vorbis' };
const ffCodec = (st) => (st.codec_name === 'dts' ? (/HD/.test(st.profile || '') ? 'DTS-HD' : 'DTS')
  : /^pcm/.test(st.codec_name) ? 'PCM' : FF_CODECS[st.codec_name] || String(st.codec_name || '').toUpperCase());

async function maxvolOf(env, imdb) {
  const kept = await env.DB.prepare('SELECT tracks, t FROM maxvol WHERE imdb = ?').bind(imdb).first();
  if (kept && Date.now() - kept.t < (kept.tracks.length > 2 ? 86_400_000 : 3_600_000)) return JSON.parse(kept.tracks);
  // asked in a call of its own (SELF, GET /maxvol): MaxVol's parse (1.5-5 ms, measured on a Mac) on top of the list's
  // could pass the free plan's 10 ms and kill the list itself, before D1 keeps it (so on every opening)
  if (!env.SELF) return maxvolAudio(env, imdb); // no binding (node tests)
  try {
    const res = await env.SELF.fetch(`https://self/maxvol/${imdb}`, { headers: { 'X-Play-Token': env.PLAY_TOKEN } });
    if (!res.ok) throw new Error(`maxvol ${res.status}`);
    return await res.json();
  } catch { // that call failed (killed past its CPU, a bad binding): {} kept an hour, not asked on every opening
    await env.DB.prepare('INSERT OR REPLACE INTO maxvol (imdb, tracks, t) VALUES (?, ?, ?)').bind(imdb, '{}', Date.now())
      .run();
    return {};
  }
}

// GET /maxvol/<IMDb id> (the play token; the worker's own call, maxvolOf): MaxVol asked, its map kept and answered.
async function maxvolAudio(env, imdb) {
  const map = {};
  try {
    const o = await (await tmdb(env, `find/${imdb}`, '&external_source=imdb_id')).json();
    const m = (o.movie_results || [])[0] || (o.tv_results || [])[0];
    const original = m && (m.original_title || m.original_name || '');
    const title = m && (/^[\x20-\x7e]+$/.test(original) ? original : m.title || m.name);
    if (title) {
      const year = (m.release_date || m.first_air_date || '').slice(0, 4);
      const res = await fetch(`${MAXVOL}?${new URLSearchParams({ Query: `${title} ${year}`.trim(), title_original: title,
        year, Category: '2000,5000' })}`, within(10000));
      for (const r of res.ok ? (await res.json()).Results || [] : []) {
        const hash = (String(r.MagnetUri).match(/btih:([0-9a-f]{40})/i) || [])[1];
        if (!hash || !Array.isArray(r.ffprobe)) continue;
        map[hash.toLowerCase()] = r.ffprobe.filter((st) => st?.codec_type === 'audio').map((st) => {
          const name = String(st.tags?.title || '').slice(0, 40);
          // newer ffprobe says it in the profile ("Dolby TrueHD + Dolby Atmos"), older only a track title might
          const atmos = /atmos/i.test(`${name} ${st.profile || ''}`);
          return [langCode(st.tags?.language), ffCodec(st), Number(st.channels) || 0, Number(atmos), name];
        });
      }
    }
  } catch (e) {
    console.log('maxvol', imdb, String(e)); // wrangler tail
  }
  await env.DB.prepare('INSERT OR REPLACE INTO maxvol (imdb, tracks, t) VALUES (?, ?, ?)')
    .bind(imdb, JSON.stringify(map), Date.now()).run();
  return map;
}

// Reads a release's audio and keeps it: its tracks ([] = not MKV/MP4), or null when the file couldn't be read (kept
// as 'null': asked again after RETRY_UNREADABLE). Play's (once): not when read before. Never throws (Play's waitUntil).
async function rememberAudio(env, hash, link, once = false) {
  try {
    if (once && settled(await audioRow(env, hash))) return null;
    let tracks = null;
    try {
      tracks = (await audioTracks(link)) || [];
    } catch (e) {
      console.log('audio', hash, String(e)); // wrangler tail
    }
    await env.DB.prepare('INSERT OR REPLACE INTO audio (hash, tracks, t) VALUES (?, ?, ?)')
      .bind(hash, JSON.stringify(tracks), Date.now()).run();
    return tracks;
  } catch (e) {
    console.log('audio', hash, String(e));
    return null;
  }
}

// The audio of the rows asked: hash -> tracks for the ones read before (none without the table).
async function audioKnown(env, hashes) {
  if (!env.DB || !hashes.length) return new Map();
  try {
    const { results } = await env.DB.prepare(`SELECT hash, tracks, t FROM audio WHERE hash IN (${hashes.map(() => '?')})`)
      .bind(...hashes).all();
    // unreadable: [] (as not MKV/MP4: the stick doesn't ask) until it's asked again
    return new Map(results.filter(settled).map((r) => [r.hash, JSON.parse(r.tracks) ?? []]));
  } catch {
    return new Map(); // no table yet (audio.sql not applied): the rows come without
  }
}

// A video file's audio tracks from its first bytes, over a few Range requests (the free plan: 50 subrequests and
// 10 ms of CPU a request, so never a whole index): [[lang, codec, channels, atmos 0/1, name], ...] in the file's order.
// lang: the app's codes (Torrentio's flags: RU, UA, EN…; '' unknown); codec: TrueHD, DTS-HD, DTS, DD+, DD, AAC, FLAC,
// Opus, PCM…; atmos: TrueHD's 16-channel presentation or E-AC-3's JOC objects, read from the track's first frame as
// Media3's Ac3Util does (else a track named "Atmos"). null: neither MKV nor MP4 (AVI, TS: not read).
const LANGS = {
  ru: 'RU', rus: 'RU', uk: 'UA', ukr: 'UA', en: 'EN', eng: 'EN', be: 'BY', bel: 'BY', kk: 'KZ', kaz: 'KZ',
  de: 'DE', ger: 'DE', deu: 'DE', fr: 'FR', fre: 'FR', fra: 'FR', es: 'ES', spa: 'ES', it: 'IT', ita: 'IT',
  ja: 'JP', jpn: 'JP', ko: 'KR', kor: 'KR', zh: 'CN', chi: 'CN', zho: 'CN', pl: 'PL', pol: 'PL', pt: 'PT',
  por: 'PT', tr: 'TR', tur: 'TR', hi: 'IN', hin: 'IN',
};
const langCode = (l) => {
  const k = String(l || '').toLowerCase().split('-')[0];
  return !k || k === 'und' ? '' : LANGS[k] || k.slice(0, 2).toUpperCase();
};
const uint = (b, s, e) => {
  let v = 0;
  for (let i = s; i < e; i++) v = v * 256 + b[i];
  return v;
};
const ascii = (b, s, e) => String.fromCharCode(...b.subarray(s, e));
const utf8 = (b, s, e) => new TextDecoder().decode(b.subarray(s, e)).replace(/\0+$/, '').trim();
const findBytes = (b, pattern) => {
  outer: for (let i = 0; i + pattern.length <= b.length; i++) {
    for (let j = 0; j < pattern.length; j++) if (b[i + j] !== pattern[j]) continue outer;
    return i;
  }
  return -1;
};

async function audioTracks(link) {
  let reads = 0;
  const read = async (from, n) => {
    if (++reads > 24) throw new Error('too many reads');
    const res = await fetch(link, { headers: { Range: `bytes=${from}-${from + n - 1}` }, ...within() });
    if (res.status !== 206) { // 200 would be the whole film
      await res.body?.cancel();
      throw new Error(`range: HTTP ${res.status}`);
    }
    return new Uint8Array(await res.arrayBuffer());
  };
  const head = await read(0, 256 * 1024);
  if (uint(head, 0, 4) === 0x1a45dfa3) return mkvAudio(head, read);
  if (ascii(head, 4, 8) === 'ftyp') return mp4Audio(head, read);
  return null;
}

// Matroska. An EBML number at p: {v, n}; an id keeps its length marker (0x1A45DFA3), a size doesn't, and a size of
// all ones (unknown: a live Segment or Cluster) is Infinity. null: not all of it in b.
function vint(b, p, id = false) {
  if (p >= b.length) return null;
  let n = 1;
  while (n <= 8 && !(b[p] & (0x100 >> n))) n++;
  if (n > 8 || p + n > b.length) return null;
  let v = id ? b[p] : b[p] & (0xff >> n);
  let ones = v === 0xff >> n;
  for (let i = 1; i < n; i++) {
    v = v * 256 + b[p + i];
    ones = ones && b[p + i] === 0xff;
  }
  return { v: !id && ones ? Infinity : v, n };
}

// An element at p: {id, at, data, end} (positions in b), null when its head isn't all in b.
function ebml(b, p) {
  const id = vint(b, p, true);
  const size = id && vint(b, p + id.n);
  if (!size) return null;
  const data = p + id.n + size.n;
  return { id: id.v, at: p, data, end: data + size.v };
}

// The elements in b[s, e) (e may lie past b: as far as b goes).
function* ebmlKids(b, s, e) {
  while (s < Math.min(e, b.length)) {
    const x = ebml(b, s);
    if (!x) return;
    yield x;
    if (x.end === Infinity) return;
    s = x.end;
  }
}

const SEGMENT = 0x18538067;
const TRACKS = 0x1654ae6b;
const CLUSTER = 0x1f43b675;
const MKV_CODECS = [[/^A_TRUEHD/, 'TrueHD'], [/^A_DTS\/(LOSSLESS|EXPRESS)/, 'DTS-HD'], [/^A_DTS/, 'DTS'],
  [/^A_EAC3/, 'DD+'], [/^A_AC3/, 'DD'], [/^A_AAC/, 'AAC'], [/^A_FLAC/, 'FLAC'], [/^A_OPUS/, 'Opus'], [/^A_PCM/, 'PCM'],
  [/^A_MPEG/, 'MP3'], [/^A_VORBIS/, 'Vorbis']];

async function mkvAudio(head, read) {
  const top = ebml(head, 0);
  const seg = top && ebml(head, top.end);
  if (!seg || seg.id !== SEGMENT) return [];
  // Tracks and the first Cluster: by the SeekHead, else as met; after: where the next element we didn't see starts
  let tracksAt = -1;
  let clusterAt = -1;
  let after = seg.data;
  for (const x of ebmlKids(head, seg.data, seg.end)) {
    if (x.id === 0x114d9b74) {
      for (const seek of ebmlKids(head, x.data, x.end)) {
        let id = 0;
        let pos = -1;
        for (const f of ebmlKids(head, seek.data, seek.end)) {
          if (f.id === 0x53ab) id = uint(head, f.data, f.end);
          if (f.id === 0x53ac) pos = seg.data + uint(head, f.data, f.end);
        }
        if (id === TRACKS) tracksAt = pos;
        if (id === CLUSTER && clusterAt < 0) clusterAt = pos;
      }
    }
    if (x.id === TRACKS) tracksAt = x.at;
    if (x.id === CLUSTER) {
      clusterAt = x.at;
      break;
    }
    after = x.end;
  }
  if (tracksAt < 0) return [];
  let b = head;
  let t = ebml(b, tracksAt);
  if (!t || t.end > b.length) {
    b = await read(tracksAt, 256 * 1024);
    t = ebml(b, 0);
    if (!t) return [];
  }
  const tracks = [];
  for (const e of ebmlKids(b, t.data, t.end)) {
    if (e.id !== 0xae) continue;
    const tr = { lang: 'eng', name: '', ch: 1, codec: '' }; // Matroska's defaults: English, one channel
    let bcp = '';
    for (const f of ebmlKids(b, e.data, e.end)) {
      if (f.id === 0xd7) tr.num = uint(b, f.data, f.end);
      else if (f.id === 0x83) tr.type = uint(b, f.data, f.end);
      else if (f.id === 0x86) tr.codec = utf8(b, f.data, f.end);
      else if (f.id === 0x22b59c) tr.lang = utf8(b, f.data, f.end);
      else if (f.id === 0x22b59d) bcp = utf8(b, f.data, f.end);
      else if (f.id === 0x536e) tr.name = utf8(b, f.data, f.end);
      else if (f.id === 0xe1) for (const a of ebmlKids(b, f.data, f.end)) if (a.id === 0x9f) tr.ch = uint(b, a.data, a.end);
    }
    if (bcp) tr.lang = bcp;
    if (tr.type === 2) tracks.push(tr);
  }
  const want = new Map(tracks.filter((x) => /^A_(TRUEHD|EAC3|DTS)/.test(x.codec)).map((x) => [x.num, x]));
  if (want.size) { // best effort: a failed range read here keeps the tracks, Atmos / DTS-HD then from the name
    try {
      if (clusterAt < 0) clusterAt = await hopTo(read, after, CLUSTER);
      if (clusterAt >= 0) await firstFrames(read, clusterAt, want);
    } catch { /* the frames' reads only */ }
  }
  return tracks.map((x) => {
    let codec = (MKV_CODECS.find(([re]) => re.test(x.codec)) || [, x.codec.replace(/^A_/, '')])[1];
    if (codec === 'DTS' && x.hd) codec = 'DTS-HD';
    return [langCode(x.lang), codec, x.ch, Number(x.atmos ?? /atmos/i.test(x.name)), x.name];
  });
}

// From a top-level element at `at`, element by element (16 bytes read each) to the first with this id: its place, -1.
async function hopTo(read, at, id) {
  for (let hops = 0; hops < 8 && Number.isFinite(at); hops++) {
    const x = ebml(await read(at, 16), 0);
    if (!x) return -1;
    if (x.id === id) return at;
    at += x.end;
  }
  return -1;
}

// The first block of each wanted track, from the first Cluster on: 256 KB at a time, stepping over the video's big
// blocks without reading them (a 4K key frame is a few MB). Sets atmos / hd on the tracks.
async function firstFrames(read, at, want) {
  const WINDOW = 256 * 1024;
  let b = await read(at, WINDOW);
  let base = at;
  const cluster = ebml(b, 0);
  if (!cluster || cluster.id !== CLUSTER) return;
  let p = base + cluster.data;
  const left = new Set(want.keys());
  for (let reads = 1; left.size && reads < 8;) {
    if (p - base + 64 > b.length && b.length === WINDOW) { // near its end, and not the file's (a short read)
      b = await read(p, WINDOW);
      base = p;
      reads++;
    }
    const x = ebml(b, p - base);
    if (!x) return;
    if (x.id === CLUSTER) { // the next one: into it
      p = base + x.data;
      continue;
    }
    // Timestamp, SimpleBlock, BlockGroup, PrevSize, Position, and CRC-32 / Void (ffmpeg writes a CRC-32 first in
    // every cluster): stepped over; anything else is past the clusters
    if (x.end === Infinity || ![0xe7, 0xa3, 0xa0, 0xab, 0xa7, 0xbf, 0xec].includes(x.id)) return;
    const block = x.id === 0xa0 ? [...ebmlKids(b, x.data, x.end)].find((k) => k.id === 0xa1) : x;
    const track = block && vint(b, block.data);
    if (track && left.has(track.v)) {
      left.delete(track.v);
      const s = block.data + track.n + 3; // the track, a 2-byte time, flags
      let frame = b.subarray(s, Math.min(block.end, b.length));
      if (block.end > b.length && frame.length < 4096) {
        frame = await read(base + s, Math.min(block.end - s, 16 * 1024));
        reads++;
      }
      firstFrame(want.get(track.v), frame);
    }
    p = base + x.end;
  }
}

function firstFrame(tr, b) {
  if (tr.codec.startsWith('A_TRUEHD')) { // major sync, its signature 0xB752, then substream_info: bit 7 = 16 channels
    const p = findBytes(b, [0xf8, 0x72, 0x6f, 0xba]);
    if (p >= 0 && p + 18 <= b.length && b[p + 8] === 0xb7 && b[p + 9] === 0x52) tr.atmos = (b[p + 17] & 0x80) !== 0;
  } else if (tr.codec.startsWith('A_EAC3')) {
    const p = findBytes(b, [0x0b, 0x77]);
    if (p >= 0) tr.atmos = eac3Joc(b.subarray(p));
  } else if (findBytes(b, [0x64, 0x58, 0x20, 0x25]) >= 0) { // DTS: an extension substream after the core
    tr.hd = true;
  }
}

// An E-AC-3 syncframe's addbsi says JOC (Atmos): Media3's Ac3Util.parseAc3SyncframeInfo, bit for bit, to that field.
// undefined: too short to tell.
function eac3Joc(b) {
  let pos = 0;
  const bits = (n) => {
    let v = 0;
    for (let i = 0; i < n; i++, pos++) {
      if (pos >> 3 >= b.length) throw new Error('short');
      v = v * 2 + ((b[pos >> 3] >> (7 - (pos & 7))) & 1);
    }
    return v;
  };
  const skip = (n) => { pos += n; };
  const bit = () => bits(1) === 1;
  try {
    pos = 40;
    if (bits(5) <= 10) return false; // bsid: AC-3
    pos = 16; // syncword
    const strmtyp = bits(2);
    skip(3 + 11); // substreamid, frmsiz
    const fscod = bits(2);
    let numblkscod = 3;
    let blocks = 6;
    if (fscod === 3) skip(2);
    else {
      numblkscod = bits(2);
      blocks = [1, 2, 3, 6][numblkscod];
    }
    const acmod = bits(3);
    const lfeon = bit();
    skip(5 + 5); // bsid, dialnorm
    if (bit()) skip(8); // compr
    if (acmod === 0) {
      skip(5);
      if (bit()) skip(8);
    }
    if (strmtyp === 1 && bit()) skip(16); // chanmap
    if (bit()) { // mixmdate
      if (acmod > 2) skip(2);
      if ((acmod & 1) && acmod > 2) skip(6);
      if (acmod & 4) skip(6);
      if (lfeon && bit()) skip(5);
      if (strmtyp === 0) {
        if (bit()) skip(6);
        if (acmod === 0 && bit()) skip(6);
        if (bit()) skip(6);
        const mixdef = bits(2);
        if (mixdef === 1) skip(5);
        else if (mixdef === 2) skip(12);
        else if (mixdef === 3) {
          const mixdeflen = bits(5);
          if (bit()) { // mixdata2e
            skip(5);
            for (let i = 0; i < 7; i++) if (bit()) skip(4);
            if (bit()) { // addche
              if (bit()) skip(4);
              if (bit()) skip(4);
            }
          }
          if (bit()) { // mixdata3e
            skip(5);
            if (bit()) {
              skip(7);
              if (bit()) skip(8);
            }
          }
          skip(8 * (mixdeflen + 2));
          pos = Math.ceil(pos / 8) * 8;
        }
        if (acmod < 2) {
          if (bit()) skip(14);
          if (acmod === 0 && bit()) skip(14);
        }
        if (bit()) { // frmmixcfginfoe
          if (numblkscod === 0) skip(5);
          else for (let i = 0; i < blocks; i++) if (bit()) skip(5);
        }
      }
    }
    if (bit()) { // infomdate
      skip(5);
      if (acmod === 2) skip(4);
      if (acmod >= 6) skip(2);
      if (bit()) skip(8);
      if (acmod === 0 && bit()) skip(8);
      if (fscod < 3) skip(1);
    }
    if (strmtyp === 0 && numblkscod !== 3) skip(1); // convsync
    if (strmtyp === 2 && (numblkscod === 3 || bit())) skip(6); // blkid
    return bit() && bits(6) === 1 && bits(8) === 1; // addbsie, addbsil, addbsi
  } catch {
    return undefined;
  }
}

// MP4: the top-level boxes to moov, then each trak's head where it starts (64 KB: its stsd comes before the sample
// tables, which are the MBs of a moov).
const MP4_CODECS = {
  'ac-3': 'DD', 'ec-3': 'DD+', mlpa: 'TrueHD', dtsc: 'DTS', dtsh: 'DTS-HD', dtsl: 'DTS-HD', dtse: 'DTS-HD',
  dtsx: 'DTS-HD', mp4a: 'AAC', Opus: 'Opus', fLaC: 'FLAC', 'ac-4': 'AC-4', ipcm: 'PCM', lpcm: 'PCM', '.mp3': 'MP3',
};
const ACMOD_CHANNELS = [2, 1, 2, 3, 3, 4, 4, 5];

function mp4Box(b, s) {
  if (s < 0 || s + 8 > b.length) return null;
  let size = uint(b, s, s + 4);
  let hl = 8;
  if (size === 1) {
    if (s + 16 > b.length) return null;
    size = uint(b, s + 8, s + 16);
    hl = 16;
  } else if (size === 0) size = Infinity; // to the end of the file
  return size < hl ? null : { type: ascii(b, s + 4, s + 8), at: s, data: s + hl, end: s + size };
}

function* mp4Kids(b, s, e) {
  while (s < Math.min(e, b.length)) {
    const x = mp4Box(b, s);
    if (!x) return;
    yield x;
    s = x.end;
  }
}

const mp4Find = (b, s, e, path) => {
  for (const x of mp4Kids(b, s, e)) {
    if (x.type === path[0]) return path.length === 1 ? x : mp4Find(b, x.data, Math.min(x.end, e), path.slice(1));
  }
  return null;
};

async function mp4Audio(head, read) {
  let b = head;
  let base = 0;
  const box = async (at) => { // the box at `at`, with the 64 KB from it in b when it isn't
    if (at + 16 > base + b.length || at < base) {
      b = await read(at, 64 * 1024);
      base = at;
    }
    return mp4Box(b, at - base);
  };
  let at = 0;
  let moov = null;
  for (let hops = 0; hops < 8 && Number.isFinite(at); hops++) {
    const x = await box(at);
    if (!x) return [];
    if (x.type === 'moov') {
      moov = { data: at + x.data - x.at, end: at + x.end - x.at };
      break;
    }
    at += x.end - x.at;
  }
  if (!moov) return [];
  const tracks = [];
  for (let p = moov.data; p + 8 <= moov.end;) {
    let x = await box(p);
    if (!x) break;
    if (x.type === 'trak' && p - base + 32 * 1024 > b.length && p + 32 * 1024 < moov.end) {
      b = await read(p, 64 * 1024);
      base = p;
      x = mp4Box(b, 0);
    }
    const track = x.type === 'trak' && mp4Track(b, x.data, Math.min(x.end, b.length));
    if (track) tracks.push(track);
    p += x.end - x.at;
  }
  return tracks;
}

// AAC's channels from esds: ES_Descriptor (3) → DecoderConfigDescriptor (4) → DecoderSpecificInfo (5), the
// AudioSpecificConfig's channelConfiguration (7 = 7.1). 0: not said (a program config element).
function aacChannels(d) {
  let p = 4; // version, flags
  const descriptor = () => { // its tag and where its body starts and ends
    const tag = d[p++];
    let size = 0;
    for (let i = 0; i < 4; i++) {
      const x = d[p++];
      size = size * 128 + (x & 0x7f);
      if (!(x & 0x80)) break;
    }
    return { tag, body: p, end: p + size };
  };
  let x = descriptor();
  if (x.tag !== 3) return 0;
  const flags = d[p + 2];
  p += 3 + (flags & 0x80 ? 2 : 0) + (flags & 0x40 ? 1 + d[p + 3] : 0) + (flags & 0x20 ? 2 : 0);
  x = descriptor();
  if (x.tag !== 4) return 0;
  p += 13;
  x = descriptor();
  if (x.tag !== 5 || x.end > d.length) return 0;
  let bit = p * 8;
  const bits = (n) => {
    let v = 0;
    for (let i = 0; i < n; i++, bit++) v = v * 2 + ((d[bit >> 3] >> (7 - (bit & 7))) & 1);
    return v;
  };
  if (bits(5) === 31) bits(6); // object type
  if (bits(4) === 15) bits(24); // sampling frequency
  const config = bits(4);
  return config === 7 ? 8 : config <= 6 ? config : 0;
}

function mp4Track(b, s, e) {
  const hdlr = mp4Find(b, s, e, ['mdia', 'hdlr']);
  if (!hdlr || ascii(b, hdlr.data + 8, hdlr.data + 12) !== 'soun') return null;
  const mdhd = mp4Find(b, s, e, ['mdia', 'mdhd']);
  let lang = '';
  if (mdhd) {
    const at = mdhd.data + (b[mdhd.data] === 1 ? 32 : 20);
    const l = uint(b, at, at + 2);
    lang = String.fromCharCode(((l >> 10) & 31) + 96, ((l >> 5) & 31) + 96, (l & 31) + 96);
  }
  const stsd = mp4Find(b, s, e, ['mdia', 'minf', 'stbl', 'stsd']);
  const entry = stsd && mp4Box(b, stsd.data + 8);
  if (!entry) return null;
  let ch = uint(b, entry.data + 16, entry.data + 18);
  let atmos = 0;
  for (const c of mp4Kids(b, entry.data + 28, Math.min(entry.end, e))) {
    const d = b.subarray(c.data, Math.min(c.end, b.length));
    if (c.type === 'dac3' && d.length >= 2) ch = ACMOD_CHANNELS[(d[1] >> 3) & 7] + ((d[1] >> 2) & 1);
    if (c.type === 'esds') ch = aacChannels(d) || ch; // the sample entry says 2 even for 5.1
    if (c.type === 'dec3' && d.length >= 5) { // the first independent substream, its dependent's channels, JOC
      const dependent = (d[4] >> 1) & 15;
      const chanLoc = dependent ? ((d[4] & 1) << 8) | d[5] : 0;
      let extra = 0;
      for (let bit = 0; bit < 9; bit++) if (chanLoc & (1 << bit)) extra += [0, 1, 4, 5, 6].includes(bit) ? 2 : 1;
      ch = ACMOD_CHANNELS[(d[3] >> 1) & 7] + (d[3] & 1) + extra;
      const after = 2 + (dependent ? 4 : 3);
      atmos = Number(d.length > after && (d[after] & 1) === 1);
    }
  }
  const name = utf8(b, hdlr.data + 24, Math.min(hdlr.end, e));
  return [langCode(lang), MP4_CODECS[entry.type] || entry.type, ch, atmos || Number(/atmos/i.test(name)), name];
}

function episodeOf(name) {
  const m = String(name).match(/S(\d{1,2})[ ._-]?E(\d{1,3})(?!\d)/i) || String(name).match(/(?:^|[^\d])(\d{1,2})x(\d{2,3})(?!\d)/i);
  return m ? [Number(m[1]), Number(m[2])] : null;
}

// Lampa's timeline key of an episode (as the app's Timeline.key): season, ':' after season 10, episode, original name.
const episodeKey = ([s, e], original) => lampaHash(`${s}${s > 10 ? ':' : ''}${e}${original}`);

// A series played from a torrent with more episodes (a season's, a series'): the episode asked for and the ones after
// it in the torrent, in order, for DDD's playlist: DDD goes on to the next by itself. Each as
// [season, episode, Lampa's key, file name, link]: the link is this worker's /stream address of that file, the same
// every time (DDD keeps its own resume point per address), which gets TorBox's link when DDD asks for it (TorBox's
// links don't live for a whole night of episodes). 30 at most; none when the file names say no episode.
// probe (intro-detect.py): all the torrent's episodes, with links that mark nothing watched (no Lampa key, no
// previous episode).
async function episodesFrom(request, env, ctx, torrent, files, file, original, link, probe = false) {
  if (!episodeOf(file.short_name || file.name)) return [];
  const all = files.filter((f) => VIDEO.test(f.name) && !/\bsample\b/i.test(f.name))
    .map((f) => ({ f, at: episodeOf(f.short_name || f.name) })).filter((x) => x.at)
    .sort((a, b) => a.at[0] - b.at[0] || a.at[1] - b.at[1] || b.f.size - a.f.size)
    .filter((x, i, a) => !i || x.at[0] !== a[i - 1].at[0] || x.at[1] !== a[i - 1].at[1]); // two of one: the bigger
  const from = all.findIndex((x) => x.f.id === file.id);
  if (from < 0) return [];
  const origin = new URL(request.url).origin;
  await keepLink(ctx, origin, torrent, file.id, link); // DDD's first ask for it costs no TorBox call
  const begin = probe ? 0 : from;
  return Promise.all(all.slice(begin, begin + 30).map(async (x, i) => {
    const key = probe ? '' : episodeKey(x.at, original);
    const prev = !probe && from + i > 0 ? episodeKey(all[from + i - 1].at, original) : '';
    const sig = await signed(env, `${torrent}/${x.f.id}/${key}/${prev}`);
    return [x.at[0], x.at[1], key, x.f.short_name || String(x.f.name).split('/').pop(),
      `${origin}/stream/${torrent}/${x.f.id}?tl=${key}&prev=${prev}&sig=${sig}`];
  }));
}

// HMAC-SHA-256 of a /stream address with PLAY_TOKEN, 32 hex: only addresses /play made are answered.
async function signed(env, text) {
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(env.PLAY_TOKEN), { name: 'HMAC', hash: 'SHA-256' },
    false, ['sign']);
  const mac = new Uint8Array(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(text)));
  return [...mac.slice(0, 16)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

// TorBox's link of a file kept at this Cloudflare location for half an hour (seeks ask /stream again), and a mark
// that the episode was streamed, for 3 hours (the next one's first ask then marks it watched).
const LINK_S = 1800;
const SEEN_S = 3 * 3600;
const cacheKey = (origin, what) => new Request(`${origin}/__cache/${what}`);
async function keepLink(ctx, origin, torrent, fileId, link, key) {
  if (typeof caches === 'undefined') return;
  const puts = [caches.default.put(cacheKey(origin, `link/${torrent}/${fileId}`),
    new Response(link, { headers: { 'Cache-Control': `max-age=${LINK_S}` } }))];
  if (key) puts.push(caches.default.put(cacheKey(origin, `seen/${key}`), new Response('1', { headers: { 'Cache-Control': `max-age=${SEEN_S}` } })));
  const all = Promise.all(puts);
  if (ctx) ctx.waitUntil(all);
  else await all;
}
const cached = async (origin, what) => (typeof caches === 'undefined' ? null
  : caches.default.match(cacheKey(origin, what)).then((r) => (r ? r.text() : null)));

// GET /stream/<TorBox torrent id>/<file id>?tl=<Lampa key>&prev=<the previous episode's key>&sig=… (from /play) ->
// 302 to TorBox's link. The episode's first ask (no Range, or from byte 0) while the previous one was streamed in
// the last 3 hours means DDD went on from it (it asks for the next file only once the current one is all loaded,
// its last minutes) or the user went on by hand: the previous one is marked watched, so Continue goes on even when
// DDD never answers the app (the TV turned off, the app killed).
// ponytail: stopping episode 5 half way and starting 6 by hand within 3 hours marks 5 watched too
async function stream(request, env, ctx, torrent, fileId) {
  const q = new URL(request.url).searchParams;
  const tl = q.get('tl') || '';
  const prev = q.get('prev') || '';
  if (!/^\d{0,10}$/.test(tl) || !/^\d{0,10}$/.test(prev) // no tl: intro-detect.py's probe
    || !sameSecret(q.get('sig') || '', await signed(env, `${torrent}/${fileId}/${tl}/${prev}`))) {
    return corsError(request, 'Forbidden.', 403);
  }
  const origin = new URL(request.url).origin;
  let link = await cached(origin, `link/${torrent}/${fileId}`);
  const first = !/^bytes=[1-9]/.test(request.headers.get('Range') || '');
  if (!link || first) {
    if (!link) {
      try {
        const dl = await torboxApi(env, `requestdl?token=${encodeURIComponent(env.TORBOX_API_KEY)}&torrent_id=${torrent}&file_id=${fileId}`);
        if (typeof dl?.data !== 'string') return corsError(request, dl?.detail || 'No link.', 502);
        link = dl.data;
      } catch {
        return corsError(request, 'TorBox unavailable.', 502);
      }
    }
    await keepLink(ctx, origin, torrent, fileId, link, tl);
  }
  if (first && prev && env.DB && await cached(origin, `seen/${prev}`)) {
    await env.DB.prepare(`INSERT INTO timeline (hash, time, duration, percent, updated) VALUES (?1, 0, 0, 100, ?2)
      ON CONFLICT (hash) DO UPDATE SET time = timeline.duration, percent = 100, updated = excluded.updated
      WHERE timeline.percent < 90`).bind(prev, Date.now()).run().catch(() => {}); // no mark beats no film
  }
  return new Response(null, { status: 302, headers: { Location: link, 'Cache-Control': 'no-store' } });
}

// GET /timeline[?hash=<hash>,…] -> {timelines: {hash: {time, duration, percent, updated[, card, season, episode]}}}
// POST /timeline {timelines: [{hash, time, duration, percent[, updated, card, season, episode]}]} -> 204
// Resume points shared by lampa-native and Lampa (the torbox-en plugin pulls them into Lampa's timeline and pushes
// its own), in D1 (timeline.sql), keyed by Lampa's hash of the title (+ season and episode); times in
// seconds. Per point the newer one wins (updated, ms; stamped here when missing, as from the stick). For the
// stick (X-Play-Token) and the CUB accounts in CUB_ACCOUNTS (checked with CUB, as /grades): one household's.
async function timelineAllowed(request, env) {
  return isStick(request, env) || Boolean(await cubUser(request, env));
}

async function timeline(request, env) {
  if (!(await timelineAllowed(request, env))) return corsError(request, 'Forbidden.', 403);
  const json = { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...corsHeaders(request) };
  if (request.method === 'GET') {
    const only = new URL(request.url).searchParams.get('hash'); // a series' episodes: lampa-native's marks
    const { results } = await (only
      ? env.DB.prepare('SELECT * FROM timeline WHERE hash IN (SELECT value FROM json_each(?))')
        .bind(JSON.stringify(only.split(',').slice(0, 2000)))
      : env.DB.prepare('SELECT * FROM timeline')).all();
    const timelines = {};
    for (const { hash, card, season, episode, ...t } of results) {
      timelines[hash] = card ? { ...t, card, ...(season ? { season, episode } : {}) } : t;
    }
    return new Response(JSON.stringify({ timelines }), { headers: json });
  }
  const list = (await request.json().catch(() => ({}))).timelines;
  const ok = (t) => /^\d{1,10}$/.test(String(t?.hash)) && ['time', 'duration', 'percent'].every((k) => Number.isFinite(t[k]))
    && (t.updated === undefined || Number.isFinite(t.updated));
  if (!Array.isArray(list) || list.length > 2000 || !list.every(ok)) return corsError(request, 'Bad timeline.', 400);
  const now = Date.now();
  const int = (v) => (Number.isInteger(v) && v > 0 ? v : null);
  const rows = list.map((t) => ({
    hash: String(t.hash), time: Math.round(t.time), duration: Math.round(t.duration), percent: Math.round(t.percent),
    updated: Math.round(t.updated ?? now), card: /^(movie|tv)_\d+$/.test(t.card) ? t.card : null,
    season: int(t.season), episode: int(t.episode),
  }));
  // One query for any number of points (D1's free plan allows 50 queries per request): the rows as one JSON.
  await env.DB.prepare(`INSERT INTO timeline (hash, time, duration, percent, updated, card, season, episode)
    SELECT value->>'hash', value->>'time', value->>'duration', value->>'percent', value->>'updated', value->>'card',
      value->>'season', value->>'episode' FROM json_each(?) WHERE true
    ON CONFLICT (hash) DO UPDATE SET time = excluded.time, duration = excluded.duration, percent = excluded.percent,
      updated = excluded.updated, card = COALESCE(excluded.card, card), season = COALESCE(excluded.season, season),
      episode = COALESCE(excluded.episode, episode)
    WHERE excluded.updated > timeline.updated`).bind(JSON.stringify(rows)).run();
  return new Response(null, { status: 204, headers: corsHeaders(request) });
}

// Lampa's Utils.hash: Java's String.hashCode as a positive decimal. Its timeline keys a film by its original title.
function lampaHash(s) {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (Math.imul(h, 31) + s.charCodeAt(i)) | 0;
  return String(Math.abs(h));
}

// Home cards in English for CUB's cards (CUB keeps Lampa's card as it was shown: Russian names and posters from CUB's
// own lists): the search index's (titles), else TMDB's en-US, which then go into the index. [type, id] pairs in;
// a card for each, in order (TMDB failed or more than `fetchMax` missing: the card CUB kept).
async function englishCards(env, wanted, fallback, fetchMax = 20) {
  const pairs = JSON.stringify(wanted.map(([type, id]) => ({ type, id })));
  const known = new Map(env.DB ? (await env.DB.prepare(`SELECT t.type, t.id, t.card FROM titles t
      JOIN json_each(?) j ON t.type = j.value->>'type' AND t.id = j.value->>'id'`).bind(pairs).all()
    .then((r) => r.results).catch(() => [])).map((r) => [`${r.type}:${r.id}`, JSON.parse(r.card)]) : []);
  const missing = wanted.filter(([type, id]) => !known.has(`${type}:${id}`)).slice(0, fetchMax);
  const fetched = (await Promise.all(missing.map(([type, id]) => tmdb(env, `${type}/${id}`)
    .then((res) => (res.ok ? res.json() : null)).then((m) => (m && m.poster_path ? { type, m } : null))
    .catch(() => null)))).filter(Boolean);
  for (const { type, m } of fetched) known.set(`${type}:${m.id}`, cardOf(m, type));
  if (fetched.length && env.DB) {
    await env.DB.prepare(`INSERT INTO titles (type, id, card, votes, seen, en)
        SELECT value->>'type', value->>'id', value->>'card', value->>'votes', ?2, value->>'en' FROM json_each(?1) WHERE true
        ON CONFLICT (type, id) DO NOTHING`)
      .bind(JSON.stringify(fetched.map(({ type, m }) => ({ type, id: m.id, card: JSON.stringify(cardOf(m, type)),
        votes: m.vote_count || 0, en: words(`${m.title || m.name} ${m.original_title || m.original_name || ''}`) }))),
      Date.now()).run().catch(() => {}); // next time from TMDB again
  }
  return wanted.map(([type, id], i) => known.get(`${type}:${id}`) || fallback[i]);
}

// Series' last aired episode ("season:episode"), original name (Lampa's keys) and English card, from TMDB, kept in
// D1 (shows.sql; showStale): a series leaves Continue browsing once that episode is watched
// and comes back when a new one airs; also its IMDb id, seasons and air dates, for new episodes. At most SHOW_FETCH
// asked of TMDB a request (the free plan's 50 subrequests); the others on the next asks. ids in, id → {original,
// last, card, imdb, seasons, aired} out (shows.sql).
const SHOW_FETCH = 12;
// Asked of TMDB again on the next episode's air date (hourly that day; a week at most), a month after it ended or
// was canceled, every 3 days while it waits for a date: a new season may come years later.
function showStale(r, now) {
  if (!r || r.seasons == null || r.status == null) return true; // or kept before these were
  const age = now - r.t;
  if (r.status === 'Ended' || r.status === 'Canceled') return age > 30 * 86400e3;
  if (r.next_air) return (now >= Date.parse(r.next_air) && age > 3600e3) || age > 7 * 86400e3;
  return age > 3 * 86400e3;
}
async function shows(env, ids) {
  const now = Date.now();
  const have = new Map(env.DB && ids.length ? (await env.DB.prepare(
    'SELECT * FROM shows WHERE id IN (SELECT value FROM json_each(?))')
    .bind(JSON.stringify(ids)).all().then((r) => r.results).catch(() => [])).map((r) => [r.id, r]) : []);
  const stale = ids.filter((id) => showStale(have.get(id), now)).slice(0, SHOW_FETCH);
  const got = (await Promise.all(stale.map((id) => tmdb(env, `tv/${id}`, '&append_to_response=external_ids')
    .then((res) => (res.ok ? res.json() : null))
    .catch(() => null)))).filter((m) => m && m.id && m.poster_path);
  const rows = got.map((m) => {
    const l = m.last_episode_to_air;
    return { id: m.id, original: m.original_name || m.name || '', last: l ? `${l.season_number}:${l.episode_number}` : '',
      card: JSON.stringify(cardOf(m, 'tv')), t: now, imdb: m.external_ids?.imdb_id || null, aired: l?.air_date || null,
      status: m.status || null, next_air: m.next_episode_to_air?.air_date || null,
      seasons: JSON.stringify((m.seasons || []).filter((x) => x.season_number > 0)
        .map((x) => [x.season_number, x.episode_count || 0, x.air_date || null])) };
  });
  for (const r of rows) have.set(r.id, r);
  if (rows.length && env.DB) {
    await env.DB.prepare(`INSERT INTO shows (id, original, last, card, t, imdb, seasons, aired, status, next_air)
        SELECT value->>'id', value->>'original', value->>'last', value->>'card', value->>'t', value->>'imdb',
          value->>'seasons', value->>'aired', value->>'status', value->>'next_air' FROM json_each(?) WHERE true
        ON CONFLICT (id) DO UPDATE SET original = excluded.original, last = excluded.last, card = excluded.card,
          t = excluded.t, imdb = excluded.imdb, seasons = excluded.seasons, aired = excluded.aired,
          status = excluded.status, next_air = excluded.next_air`)
      .bind(JSON.stringify(rows)).run().catch(() => {}); // asked again next time
  }
  return have;
}

// Your place in a series: the furthest episode watched to 90 % (w: [season, episode, when], null: none) and how many
// aired after it (left: its poster's "26 ep left"). s: shows(); done: the timeline's points past
// 70 % by hash.
function place(s, done) {
  const seasons = JSON.parse(s.seasons || '[]');
  const [ls, le] = s.last.split(':').map(Number);
  let w = null;
  let left = 0;
  for (const [n, count] of seasons) {
    for (let e = 1; e <= count && (n < ls || (n === ls && e <= le)); e++) {
      const p = done.get(episodeKey([n, e], s.original));
      if (p && p.percent >= 90) {
        w = [n, e, p.updated];
        left = 0;
      } else left++;
    }
  }
  return { seasons, ls, le, w, left };
}

// New episodes: the episode after your furthest watched one aired in the last NEW_DAYS, after you
// watched that one: [season, episode], else null. s: shows(); at: its place().
// ponytail: an air date is known for a season's first episode and the last aired one only (TMDB's series answer),
// so with two or more aired since (a week missed) there is no label until you catch up; upgrade: TMDB's
// tv/<id>/season/<n> for the episode's own date.
const NEW_DAYS = 30;
// The episode after [season, episode]: the next season's first after a season's last.
const after = (seasons, [n, e]) => (e < (seasons.find(([x]) => x === n)?.[1] || 0) ? [n, e + 1] : [n + 1, 1]);
function nextNew(s, at, now) {
  const { seasons, ls, le, w } = at;
  if (!w) return null;
  const next = after(seasons, w);
  if (next[0] > ls || (next[0] === ls && next[1] > le)) return null; // not aired yet
  const aired = Date.parse(next[1] === 1 ? seasons.find(([n]) => n === next[0])?.[2]
    : next[0] === ls && next[1] === le ? s.aired : null);
  return aired > w[2] && now - aired <= NEW_DAYS * 86400e3 ? next : null;
}

// The audio languages the stick asks Coming up about (its own setting: the app can be in English with
// Russian audio): /mine?lang=RU,UA, Torrentio's flag codes; "" any language; none given (an older app) RU,UA.
function askedLangs(request) {
  const q = new URL(request.url).searchParams.get('lang');
  return (q === null ? 'RU,UA' : q).split(',').map((c) => c.trim().toUpperCase()).filter((c) => /^[A-Z]{2}$/.test(c));
}

// "Russian or Ukrainian": a flag's likely language in English (UA → Ukrainian, as the app's Audio languages sheet)
function langNames(codes) {
  const names = new Intl.DisplayNames(['en'], { type: 'language' });
  const one = (c) => {
    if (c === 'EN') return 'English';
    try {
      return names.of(new Intl.Locale(`und-${c}`).maximize().language);
    } catch {
      return c;
    }
  };
  return [...new Set(codes.map(one))].join(' or ');
}

// Which of those new episodes are out in the asked audio languages (a dub comes days after the air date; none asked:
// any release): Torrentio's flags, asked at most every FRESH_EVERY per episode until it is (fresh.sql, keyed per
// language set; RU,UA's keys are the ones from before the setting), up to FRESH_MAX a request; its failures are
// asked again next time. [{key, imdb, next}] in, the keys out → when a release in those languages was first seen (ms).
const FRESH_EVERY = 3 * 3600e3;
const FRESH_MAX = 5;
async function freshOut(env, list, langs = ['RU', 'UA']) {
  if (!env.DB || !list.length) return new Map();
  const now = Date.now();
  const tag = langs.join(',') === 'RU,UA' ? '' : `|${langs.join(',')}`;
  const rows = new Map((await env.DB.prepare('SELECT key, out, t FROM fresh WHERE key IN (SELECT value FROM json_each(?))')
    .bind(JSON.stringify(list.map((x) => x.key + tag))).all().then((r) => r.results).catch(() => [])).map((r) => [r.key, r]));
  const row = (x) => rows.get(x.key + tag);
  const wanted = (st) => !langs.length || languagesOf(String(st.title)).some((c) => langs.includes(c));
  const due = env.STREAM_ADDON ? list.filter((x) => !row(x)?.out && now - (row(x)?.t || 0) >= FRESH_EVERY)
    .slice(0, FRESH_MAX) : [];
  const asked = (await Promise.all(due.map(({ key, imdb, next }) => fetch(
    addonStreams(env, 'series', `${imdb}:${next[0]}:${next[1]}`), { signal: AbortSignal.timeout(5000) })
    .then((res) => (res.ok ? res.json() : null))
    .then((j) => j && { key: key + tag, out: (j.streams || []).some(wanted) ? now : null, t: now })
    .catch(() => null)))).filter(Boolean);
  if (asked.length) {
    await env.DB.prepare(`INSERT INTO fresh (key, out, t) SELECT value->>'key', value->>'out', value->>'t'
      FROM json_each(?) WHERE true ON CONFLICT (key) DO UPDATE SET out = excluded.out, t = excluded.t`)
      .bind(JSON.stringify(asked)).run().catch(() => {}); // asked again next time
  }
  for (const a of asked) rows.set(a.key, a);
  return new Map(list.filter((x) => row(x)?.out).map((x) => [x.key, Number(row(x).out)]));
}

// New in 4K: CUB's own list of the latest 4K releases (Lampa's "In high quality" row). Its 4K
// means a 4K release with Russian audio: 13 of its newest 15 films had one on Torrentio, none of its WEB-DL ones
// (Fall 2: 14 English 4K releases). Read once a day; each title's first day in it is kept (uhd.sql), so the row is
// what turned 4K lately. Well-known ones only (100+ votes or IMDb 7+); English cards from the search index or TMDB.
const UHD_LISTS = [['movie', 3], ['tv', 2]]; // [CUB's category, pages of 20]
async function refreshUhd(env, now) {
  const got = [];
  for (const [cat, pages] of UHD_LISTS) {
    for (let p = 1; p <= pages; p++) {
      const json = await fetch(`https://tmdb.cub.best/?cat=${cat}&sort=latest&uhd=true&page=${p}`, within())
        .then((res) => (res.ok ? res.json() : null)).catch(() => null);
      (json?.results || []).forEach((c, i) => {
        if (c?.id && (Number(c.vote_count) >= 100 || Number(c.imdb_rating) >= 7)) got.push({ type: cat, id: Number(c.id), pos: (p - 1) * 20 + i });
      });
    }
  }
  const rows = (await env.DB.prepare('SELECT type, id, card IS NULL AS bare FROM uhd').all()).results;
  const have = new Set(rows.map((r) => `${r.type}:${r.id}`));
  const want = [...got.filter((x) => !have.has(`${x.type}:${x.id}`)), ...rows.filter((r) => r.bare)]; // no English card yet
  if (!want.length) return;
  const cards = await englishCards(env, want.map((x) => [x.type, Number(x.id)]), want.map(() => null), 30);
  await env.DB.prepare(`INSERT INTO uhd (type, id, t, pos, card) SELECT value->>'type', value->>'id', ?, value->>'pos',
      value->>'card' FROM json_each(?) WHERE true ON CONFLICT (type, id) DO UPDATE SET card = COALESCE(uhd.card, excluded.card)`)
    .bind(now, JSON.stringify(want.map((x, i) => ({ type: x.type, id: Number(x.id), pos: x.pos ?? 0,
      card: cards[i] ? JSON.stringify(cards[i]) : null })))).run();
}

// For you (grades-patch.js's, made here: TMDB's recommendations of 240+ graded films are more than the 50
// subrequests a request allows): TMDB recommendations of each Loved (×2), Good (×1) and Bad (×-1) film, kept a week
// (foryou.sql: recs), RECS_FETCH an hour; ranked per account into its top 60 cards (foryou), what /mine shows minus
// what was played since. Higher in a list counts more; 200+ votes and TMDB 6.5+ only; animation only from animated
// films (TMDB adds cartoon spin-offs). Lampa's "We recommend to see" (from the history) is left out: the cron can't
// read CUB.
const RECS_KEEP = 7 * 86400e3;
const RECS_FETCH = 25;
const WEIGHT = { 2: 2, 1: 1, '-1': -1 };
async function refreshForYou(env) {
  const now = Date.now();
  const whos = (await env.GRADES.list()).keys.map((k) => k.name);
  const graded = await Promise.all(whos.map((w) => env.GRADES.get(w, 'json')));
  const seedsOf = (g) => Object.keys(g || {}).filter((k) => /^(movie|tv)_\d+$/.test(k) && WEIGHT[g[k]?.g]);
  const seeds = [...new Set(graded.flatMap(seedsOf))];
  const have = new Map((await env.DB.prepare('SELECT key, list, t FROM recs WHERE key IN (SELECT value FROM json_each(?))')
    .bind(JSON.stringify(seeds)).all()).results.map((r) => [r.key, r]));
  const due = seeds.filter((k) => !have.has(k) || now - have.get(k).t > RECS_KEEP).slice(0, RECS_FETCH);
  const got = (await Promise.all(due.map((k) => {
    const [type, id] = k.split('_');
    return tmdb(env, `${type}/${id}/recommendations`).then((res) => (res.ok ? res.json() : null)).then((j) => j && {
      key: k, t: now, list: JSON.stringify((j.results || []).filter((m) => m.id).map((m) => [type, m.id, m.vote_count || 0,
        Math.round((m.vote_average || 0) * 10), (m.genre_ids || []).includes(16) ? 1 : 0])),
    }).catch(() => null);
  }))).filter(Boolean);
  if (got.length) {
    await env.DB.prepare(`INSERT INTO recs (key, list, t) SELECT value->>'key', value->>'list', value->>'t' FROM json_each(?)
      WHERE true ON CONFLICT (key) DO UPDATE SET list = excluded.list, t = excluded.t`).bind(JSON.stringify(got)).run();
  }
  for (const r of got) have.set(r.key, r);
  for (const [i, who] of whos.entries()) {
    const g = graded[i] || {};
    const score = new Map();
    const tie = new Map();
    for (const k of seedsOf(g)) {
      if (!have.has(k)) continue;
      const animated = (g[k].card?.genre_ids || []).includes(16);
      JSON.parse(have.get(k).list).forEach(([type, id, votes, rating, cartoon], at) => {
        const key = `${type}_${id}`;
        if ((cartoon && !animated) || g[key]?.g != null || votes < 200 || rating < 65) return;
        score.set(key, (score.get(key) || 0) + WEIGHT[g[k].g] * (1 - at / 40));
        tie.set(key, rating);
      });
    }
    const top = [...score].filter(([, x]) => x > 0).sort((a, b) => b[1] - a[1] || tie.get(b[0]) - tie.get(a[0]))
      .slice(0, 60).map(([k]) => k.split('_'));
    const cards = (await englishCards(env, top.map(([type, id]) => [type, Number(id)]), top.map(() => null), 15)).filter(Boolean);
    await env.DB.prepare(`INSERT INTO foryou (who, cards, t) VALUES (?, ?, ?)
      ON CONFLICT (who) DO UPDATE SET cards = excluded.cards, t = excluded.t`).bind(who, JSON.stringify(cards), now).run();
  }
}
// The hourly cron: New in 4K at 04 UTC (and while it has nothing), For you the other hours (each its own 50
// subrequests).
const HOURLY_CRON = '23 * * * *';

// Lampa's marks (favorite.js; one at a time): Looking, Viewed, Scheduled, To be continued, Thrown. The app's Finish
// later is Scheduled, Not interested is Thrown, Watched it is Viewed: all three leave Continue browsing. CUB keeps
// marks for Premium accounts only (error 555 "Нет премиум доступа"), so the app's go to D1 (marks.sql), per account
// and profile like the grades; a Premium account's own CUB marks count too.
const MARKS = ['look', 'viewed', 'scheduled', 'continued', 'thrown'];
const FILM_STARTED_S = 180; // a film in Continue browsing: watched 3 min (5 until 2026-11-01: sooner, user)
const AWAY = new Set(['viewed', 'scheduled', 'thrown']);

// A CUB bookmark's card: {type, card} (Lampa's card as JSON text in it), null for one without a TMDB id.
function bookmarkCard(b) {
  try {
    const card = typeof b.data === 'string' ? JSON.parse(b.data) : b.data;
    return card && card.id && (!card.source || /^(tmdb|cub)$/i.test(card.source)) ? { type: b.card_type, card } : null; // others: no TMDB id
  } catch {
    return null;
  }
}

// Extensions: a private worker (or any service) bound in wrangler.toml as EXT_<NAME> (a [[services]] binding) is
// reached at /ext/<name>/<path> as /<name>/<path>, for the stick only (X-Play-Token). The worker adds the secret
// EXT_<NAME>_TOKEN as X-Ext-Token when set; nothing else of the request goes along but the method, the path and a body
// of at most 16 KB. The answers: the extension's status (a 401 or 403 of its own becomes 502, so the stick keeps its
// token) and JSON, never cached. GET /mine also asks EXT_LEARN's /learn/progress for "learned" (a film's percent;
// present, even {}, only while the extension answers: the stick shows Learn only then).
const EXT_BODY_MAX = 16384;
const EXT_MS = 15000; // a pass-through call waits this long at most
const EXT_MINE_MS = 1500; // /mine waits this long for an extension at most

async function extFetch(env, name, path, init = {}, ms = EXT_MS) {
  const binding = env[`EXT_${name.toUpperCase()}`];
  if (!binding || typeof binding.fetch !== 'function') return null;
  const headers = { 'Content-Type': 'application/json' };
  const token = env[`EXT_${name.toUpperCase()}_TOKEN`];
  if (token) headers['X-Ext-Token'] = token;
  return binding.fetch(`https://ext/${name}${path}`, { ...init, headers, signal: AbortSignal.timeout(ms) });
}

async function extension(request, env, name, rest) {
  if (!isStick(request, env)) return corsError(request, 'Forbidden.', 403);
  if (!env[`EXT_${name.toUpperCase()}`]) return corsError(request, 'Not found.', 404);
  if (Number(request.headers.get('Content-Length')) > EXT_BODY_MAX) return corsError(request, 'Too large.', 413);
  const body = request.method === 'POST' ? await request.text() : undefined;
  if (body !== undefined && new TextEncoder().encode(body).length > EXT_BODY_MAX) return corsError(request, 'Too large.', 413);
  try {
    const res = await extFetch(env, name, rest, { method: request.method, body });
    const out = await res.text();
    const status = res.status === 401 || res.status === 403 ? 502 : res.status;
    return new Response([204, 205, 304].includes(status) ? null : out, { status, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store',
      ...corsHeaders(request) } });
  } catch (e) {
    return corsError(request, `${name}: ${e.message}.`, 502);
  }
}

// null: no extension, or it didn't answer well (the stick then shows no Learn button); else {"movie_603": 93, …}
async function extLearned(env) {
  try {
    const res = await extFetch(env, 'learn', '/progress', {}, EXT_MINE_MS);
    const got = res?.ok ? await res.json() : null;
    return got && typeof got === 'object' && !Array.isArray(got) ? got : null;
  } catch {
    return null;
  }
}

// GET /mine with the app's CUB sign-in (X-Cub-Token, X-Cub-Profile; accounts in CUB_ACCOUNTS only, as /grades) ->
// {"rows": [...] as /home, "marks": {"tv_93740": "scheduled", ...}, "grades": {"movie_603": 2, ...}, "new":
// {"tv_93740": "S3 E5", ...}}: the rows of Lampa's CUB home that are yours, for the top of lampa-native's Home,
// every film's mark and grade (the film page's ⋯ and ★ show them; grades: KV GRADES as /grades, "skip" = Didn't
// watch / not mine), the series with a new episode out (nextNew, freshOut): first in Continue browsing, and "mylist":
// ["movie_603", ...], what My list holds (the film page's ⋯ adds or removes), "left": {"movie_603": 4000, ...}, the
// seconds left of each film started (3 min in, under 90 %, not graded), for its poster, and "tv_1399": "26 ep", a
// series' aired episodes after your furthest watched one (none when caught up), "percent": {"movie_603": 43, ...},
// how far in each of those films is (a Continue tile's bar), and "seen": ["movie_603", ...],
// the films in the history played to 90 % (graded or not): a genre page hides them with the graded and marked ones,
// "next": {"tv_1399": "S3 E5 · up next", ...}, a series' episode to go on with (Continue browsing's tiles), and
// "coming": [[date, card, what, state], ...], the app's Coming up.
// A graded film is watched: out of My list, Finish later and Continue browsing; a graded series may be
// watched on, so only "not mine" (skip) takes it out.
// Rows: Continue browsing, For you, New in 4K (both made by the hourly cron), My list, Finish later, Rate what you watched.
// Continue browsing: Lampa's (favorite.js continues): the series in the history, newest first, 19 at most; here
// also the films being watched (3 min in, under 90 %: the timeline; shorter is a try of a release, ~2 min of its
// sound and picture; their film page still resumes from 10 s), which Lampa's main screen leaves out. Out of it:
// Viewed, Thrown and Scheduled, and series whose last aired episode is watched. Finish later: the Scheduled ones.
// Rate what you watched (grades-patch.js's row): the history played past 70 % (a series: any episode), no grade yet.
// Never cached: it changes with every film played.
// CUB's dump (2026-10-31): {bookmarks: [{id, type, card_id, card_type: movie|tv, time (ms), data: Lampa's card as
// JSON text, …}]}, oldest first: ~320 KB for 365 history entries. Only the newest 100 history cards are parsed (the
// free plan's 10 ms of CPU a request; Lampa keeps 100 in its own history).
async function mine(request, env) {
  const who = await cubUser(request, env);
  if (!who) return corsError(request, 'CUB sign-in required.', 401);
  const db = (sql, ...args) => (env.DB ? env.DB.prepare(sql).bind(...args).all().then((r) => r.results).catch(() => []) : []);
  const [dump, ours, graded, picks, uhd, follows, told, learned] = await Promise.all([cubApi(request, 'bookmarks/dump'),
    db('SELECT card, mark FROM marks WHERE who = ?', who), env.GRADES ? env.GRADES.get(who, 'json').catch(() => null) : null,
    db('SELECT cards FROM foryou WHERE who = ?', who), db('SELECT type, id, card FROM uhd WHERE card IS NOT NULL ORDER BY t DESC, pos LIMIT 100'),
    db('SELECT person, name, coming FROM follows WHERE who = ?', who),
    db('SELECT t, card, head, line FROM news WHERE who = ? AND t > ? ORDER BY t DESC LIMIT ?', who, Date.now() - NEWS_DAYS * 86400e3,
      NEWS_MAX), extLearned(env)]);
  if (!dump || !Array.isArray(dump.bookmarks)) return corsError(request, 'CUB unavailable.', 502);
  const marks = {};
  for (const b of dump.bookmarks) if (MARKS.includes(b.type) && /^(movie|tv)$/.test(b.card_type)) marks[`${b.card_type}_${b.card_id}`] = b.type;
  for (const m of ours) marks[m.card] = m.mark;
  const grades = {};
  for (const [k, v] of Object.entries(graded || {})) if (v && v.g != null) grades[k] = v.g; // null: removed
  const byTime = (a, b) => (Number(b.time) || 0) - (Number(a.time) || 0);
  const newest = (...types) => dump.bookmarks.filter((b) => types.includes(b.type) && /^(movie|tv)$/.test(b.card_type)).sort(byTime);
  const keyOf = (b) => `${b.card_type}_${b.card_id}`;
  const once = (list) => { // each film once, where it first comes
    const seen = new Set();
    return list.filter((b) => !seen.has(keyOf(b)) && seen.add(keyOf(b)));
  };
  const markOf = (b) => marks[keyOf(b)];
  const all = newest('history');
  const recent = all.slice(0, 100).map((b) => (AWAY.has(markOf(b)) ? { ...bookmarkCard(b), away: true } : bookmarkCard(b)))
    .filter((h) => h?.card);
  const history = recent.filter((h) => !h.away);
  const later = once([...all.filter((b) => markOf(b) === 'scheduled'), ...newest('scheduled')]).slice(0, 40).map(bookmarkCard)
    .filter(Boolean);
  // My list (one list, not three): Lampa's Bookmarks and Later together, newest first
  const listed = once(newest('book', 'wath'));
  const mylist = listed.slice(0, 40).map(bookmarkCard).filter(Boolean);
  const series = history.filter((h) => h.type === 'tv').slice(0, 30);
  // every series in the history but Not interested and Finish later ones: a new season may come years later
  const followed = once(all.filter((b) => b.card_type === 'tv' && !['thrown', 'scheduled'].includes(markOf(b))))
    .map(bookmarkCard).filter(Boolean);
  const info = await shows(env, [...new Set([...series, ...later, ...followed, ...mylist].filter((h) => h.type === 'tv')
    .map((h) => Number(h.card.id)))]);
  const hash = (c) => lampaHash(c.original_title || c.title || '');
  const lastKey = (h) => {
    const s = info.get(Number(h.card.id));
    return s && s.last ? episodeKey(s.last.split(':').map(Number), s.original) : null;
  };
  const keys = [...recent.filter((h) => h.type === 'movie').map((h) => hash(h.card)), ...series.map(lastKey).filter(Boolean)];
  const points = new Map(keys.length && env.DB ? (await env.DB.prepare(
    'SELECT hash, time, duration, percent FROM timeline WHERE hash IN (SELECT value FROM json_each(?))')
    .bind(JSON.stringify(keys)).all().then((r) => r.results).catch(() => [])).map((p) => [p.hash, p]) : []);
  const gradedOut = (h) => {
    const g = grades[`${h.type}_${h.card.id}`];
    return g !== undefined && (h.type === 'movie' || g === 'skip');
  };
  const started = (h) => {
    const p = points.get(hash(h.card));
    return p && p.time >= FILM_STARTED_S && p.percent < 90 && !gradedOut(h);
  };
  const going = (h) => (h.type === 'tv' ? series.includes(h) && !gradedOut(h) && !(points.get(lastKey(h))?.percent >= 90)
    : started(h));
  // ponytail: every point past 70 % read (~300 in 2026-10, one small query)
  const done = new Map((await db('SELECT hash, percent, updated FROM timeline WHERE percent >= 70')).map((p) => [p.hash, p]));
  // a series once you're caught up: its last aired episode past 70 % (not one episode of it)
  const watched = (h) => (h.type === 'movie' ? done.has(hash(h.card)) : done.has(lastKey(h)));
  const rate = recent.filter((h) => !(`${h.type}_${h.card.id}` in grades) && watched(h)).slice(0, 20);
  const places = new Map([...info].filter(([, s]) => s.last).map(([id, s]) => [id, place(s, done)]));
  // Coming up and new episodes: not the series graded Not for me or marked Not interested (a My list one too)
  const mine = (h) => !gradedOut(h) && marks[`${h.type}_${h.card.id}`] !== 'thrown';
  const newOnes = followed.filter(mine).map((h) => {
    const s = info.get(Number(h.card.id));
    const next = s && s.imdb && places.has(s.id) ? nextNew(s, places.get(s.id), Date.now()) : null;
    return next && { h, next, imdb: s.imdb, key: `${h.card.id}/${next[0]}/${next[1]}` };
  }).filter(Boolean);
  const langs = askedLangs(request);
  const out = await freshOut(env, newOnes, langs);
  const fresh = {};
  for (const x of newOnes) if (out.has(x.key)) fresh[`tv_${x.h.card.id}`] = `S${x.next[0]} E${x.next[1]}`;
  const isNew = (h) => h.type === 'tv' && `tv_${h.card.id}` in fresh && !gradedOut(h);
  const continuing = [...followed.filter(isNew), ...history.filter((h) => going(h) && !isNew(h))].slice(0, 19);
  // English cards: a series' from shows, the others from the search index or TMDB (15 a request at most)
  // My list and Finish later without what you watched, here or in Lampa: a film 90 % or marked
  // Watched it, a series that ended, to its last episode; one running on stays (a new season may come). Hidden, not
  // removed from CUB (Lampa's Bookmarks keep them; ⋯ removes).
  const finished = (h) => gradedOut(h) || marks[`${h.type}_${h.card.id}`] === 'viewed' || (h.type === 'movie'
    ? done.get(hash(h.card))?.percent >= 90
    : ['Ended', 'Canceled'].includes(info.get(Number(h.card.id))?.status) && done.get(lastKey(h))?.percent >= 90);
  const lists = [['Continue browsing', continuing], ['My list', mylist.filter((h) => !finished(h))],
    ['Finish later', later.filter((h) => !finished(h))], ['Rate what you watched', rate]];
  const rest = [...new Map(lists.flatMap(([, l]) => l).filter((h) => !(h.type === 'tv' && info.has(Number(h.card.id))))
    .map((h) => [`${h.type}:${h.card.id}`, h])).values()];
  const english = await englishCards(env, rest.map((h) => [h.type, Number(h.card.id)]), rest.map((h) => cardOf(h.card, h.type)), 15);
  const cardsOf = new Map(rest.map((h, i) => [`${h.type}:${h.card.id}`, english[i]]));
  const cardFor = (h) => (h.type === 'tv' && info.has(Number(h.card.id)) ? JSON.parse(info.get(Number(h.card.id)).card)
    : cardsOf.get(`${h.type}:${h.card.id}`));
  // For you and New in 4K (the hourly cron's): without what you played, graded or marked
  const played = new Set(all.map(keyOf));
  const unseen = (type, id) => !played.has(`${type}_${id}`) && !(`${type}_${id}` in grades) && !marks[`${type}_${id}`];
  const forYou = JSON.parse(picks[0]?.cards || '[]').filter((c) => unseen(c[1], c[0])).slice(0, 20);
  const in4k = uhd.filter((r) => unseen(r.type, r.id)).slice(0, 20).map((r) => JSON.parse(r.card));
  // A series tile's line in Continue browsing: its episode to go on with, the one after
  // the furthest watched, aired: "S3 E5 · up next", or "S1 E7 · 12m left" while it is being watched.
  const nextOf = new Map();
  for (const h of continuing.filter((x) => x.type === 'tv')) {
    const s = info.get(Number(h.card.id));
    const at = places.get(Number(h.card.id));
    if (!at) continue;
    // nothing watched to 90 % yet: the first episode, only while it is being watched ("S1 E1 · 30m left"; the
    // seasons before may have been watched elsewhere)
    const n = at.w ? after(at.seasons, at.w) : [at.seasons.find(([x]) => x > 0)?.[0] || 1, 1];
    if (n[0] < at.ls || (n[0] === at.ls && n[1] <= at.le)) nextOf.set(s.id, [n, episodeKey(n, s.original), !at.w]);
  }
  const partly = new Map(nextOf.size ? (await db('SELECT hash, time, duration FROM timeline WHERE percent < 90 AND hash '
    + 'IN (SELECT value FROM json_each(?))', JSON.stringify([...nextOf.values()].map(([, k]) => k)))).map((p) => [p.hash, p]) : []);
  const leftText = (sec) => {
    const m = Math.max(1, Math.round(sec / 60));
    return m >= 60 ? `${Math.floor(m / 60)}h ${m % 60}m left` : `${m}m left`;
  };
  const next = {};
  for (const [id, [n, k, first]] of nextOf) {
    const p = partly.get(k);
    const going = p && p.time >= 60 && p.duration > p.time;
    if (first && !going) continue;
    next[`tv_${id}`] = `S${n[0]} E${n[1]} · ${going ? leftText(p.duration - p.time) : 'up next'}`;
  }
  // Coming up, [date, card, what, state, [season, episode] (a series')] by date, ""
  // (later, or no date) last: the next episode of each series you follow for four weeks: a new one aired (out in your
  // audio languages, state "out", or only in English so far, "waiting": dated today), or the next to air once you're caught
  // up ("airs"); a new season's start wherever you are in it ("airs"); and My list's films not out
  // yet ("film": in four weeks, else "").
  const today = new Date().toISOString().slice(0, 10);
  const until = new Date(Date.now() + 28 * 86400e3).toISOString().slice(0, 10);
  // "S6 E3 · next after your E2", "Season 4 starts · you finished season 3"
  const what = ([n, e], w, how) => (e === 1 ? `Season ${n} starts${how} · you finished season ${w[0]}`
    : `S${n} E${e}${how} · next after your E${w[1]}`);
  // "out in Russian or Ukrainian", "no Russian or Ukrainian yet" (English asked too, or any language: "no release
  // yet"): not out as far as known (asked and none, or not asked yet), not a claim that an English one is
  const waiting = !langs.length || langs.includes('EN') ? ' · no release yet' : ` · no ${langNames(langs)} yet`;
  const outIn = langs.length ? ` · out in ${langNames(langs)}` : ' · out';
  const coming = newOnes.map((x) => [today, JSON.parse(info.get(Number(x.h.card.id)).card), what(x.next,
    places.get(Number(x.h.card.id)).w, out.has(x.key) ? outIn : waiting),
  out.has(x.key) ? 'out' : 'waiting', x.next]);
  for (const h of followed.filter(mine)) {
    const s = info.get(Number(h.card.id));
    const at = s && places.get(s.id);
    if (!at?.w || at.w[0] !== at.ls || at.w[1] !== at.le || !s.next_air || s.next_air < today || s.next_air > until) continue;
    const n = after(at.seasons, at.w);
    coming.push([s.next_air, JSON.parse(s.card), what(n, at.w, ''), 'airs', n]); // caught up
  }
  // a season starting in four weeks, of a series in your history or My list, wherever you are in it
  const shown = new Set(coming.map(([, c]) => c[0]));
  for (const h of [...followed, ...mylist.filter((x) => x.type === 'tv')].filter(mine)) {
    const s = info.get(Number(h.card.id));
    const start = s && !shown.has(s.id) && JSON.parse(s.seasons || '[]').find(([, , d]) => d >= today && d <= until);
    if (!start) continue;
    shown.add(s.id);
    const at = places.get(s.id);
    const on = at?.w && after(at.seasons, at.w);
    const how = !on ? '' : on[0] === start[0] ? ` · you finished season ${at.w[0]}` : ` · you're on S${on[0]} E${on[1]}`;
    coming.push([start[2], JSON.parse(s.card), `Season ${start[0]} starts${how}`, 'airs', [start[0], 1]]);
  }
  for (const h of mylist) {
    const date = h.type === 'movie' ? h.card.release_date || '' : today;
    const card = date > today && !finished(h) && mine(h) && cardFor(h);
    if (card) coming.push([date <= until ? date : '', card, "From My list · in cinemas first, here once it's out digitally", 'film']);
  }
  // the films of the people you follow, as My list's: each once, not one you graded or marked
  const films = new Set(coming.filter(([, c]) => c[1] === 'movie').map(([, c]) => c[0]));
  for (const f of follows) {
    for (const [date, card, how] of JSON.parse(f.coming || '[]')) {
      if (date <= today || films.has(card[0]) || !unseen('movie', card[0])) continue;
      films.add(card[0]);
      coming.push([date <= until ? date : '', card, `${how === 'directs' ? `${f.name}'s new film` : `With ${f.name}`} · in `
        + "cinemas first, here once it's out digitally", 'film']);
    }
  }
  // What's new, newest first, [t, card, head, line]: the new episodes out
  // in your audio languages (when a release was first seen) and the news kept in D1 (refreshFollows: films the people
  // you follow announced), NEWS_DAYS back; the app keeps when you last looked
  const outLine = langs.length ? `Out in ${langNames(langs)}` : 'Out';
  const since = Date.now() - NEWS_DAYS * 86400e3;
  const news = [...newOnes.filter((x) => out.get(x.key) > since).map((x) => {
    const c = JSON.parse(info.get(Number(x.h.card.id)).card);
    return [out.get(x.key), c, `${c[2]} · S${x.next[0]} E${x.next[1]}`, outLine];
  }),
  ...told.map((r) => [r.t, JSON.parse(r.card), r.head, r.line])].sort((a, b) => b[0] - a[0]).slice(0, NEWS_MAX);
  const rank = ['out', 'waiting', 'airs', 'film']; // a day's: out first
  coming.sort((a, b) => (a[0] || '9').localeCompare(b[0] || '9') || rank.indexOf(a[3]) - rank.indexOf(b[3]));
  const rows = [];
  for (const [title, cards] of [['Continue browsing', continuing.map(cardFor)], ['For you', forYou], ['New in 4K', in4k],
    ...lists.slice(1).map(([title, l]) => [title, l.map(cardFor)])]) {
    if (cards.length) rows.push([title, cards]);
  }
  const left = {}, percent = {}; // percent: how far in, for the app's Continue tile bar
  for (const h of recent) {
    if (h.type === 'movie' && !['viewed', 'thrown'].includes(marks[`movie_${h.card.id}`]) && started(h)) {
      const p = points.get(hash(h.card));
      left[`movie_${h.card.id}`] = p.duration - p.time;
      percent[`movie_${h.card.id}`] = p.percent;
    }
  }
  for (const [id, at] of places) {
    if (at.w && at.left && grades[`tv_${id}`] !== 'skip' && !['viewed', 'thrown'].includes(marks[`tv_${id}`])) left[`tv_${id}`] = `${at.left} ep`;
  }
  const seen = all.filter((b) => b.card_type === 'movie').map((b) => [keyOf(b), bookmarkCard(b)])
    .filter(([, h]) => h && done.get(hash(h.card))?.percent >= 90).map(([k]) => k);
  return new Response(JSON.stringify({ rows, marks, grades, new: fresh, mylist: listed.map(keyOf), left, percent,
    seen: [...new Set(seen)], next, coming, follows: follows.map((f) => f.person), news,
    ...(learned ? { learned } : {}) }), {
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...corsHeaders(request) },
  });
}

// GET /mine/history?tz=<the stick's minutes east of UTC>: what was
// played here and in Lampa, newest first: {items: [[t (ms), card, what, left, lampa, [hash, ...]], ...]}, 100 at most.
// A title of CUB's history once for each day it was played: the timeline's dated points (lampa-native's carry the card,
// Lampa's only its hash: matched by the history's original titles and, for episodes, shows' seasons: 66 of 67 on
// 2026-11-02), a series' episodes of that day on one row ("S1 E6 and E7"). A title with no dated point (Lampa's
// points before 2026-08 carry none): one row at CUB's own time (when it last went to the history's front), with its
// furthest point (and only it to Mark unwatched). CUB's time on a later day than any point (a play that saved none:
// under a minute, or DDD's answer lost): a row of its own that day with nothing known. what: the episodes, "" a film;
// left: the newest point's seconds left, 0 watched (90 %), -1 no point; lampa: 1 when Lampa played all of it (points without
// a card), 0 here or not known; the hashes: the row's points (Mark unwatched). Two films of one original title share
// Lampa's key (Lampa's own timeline does too).
// Points under a minute (a release tried) are left out.
// ponytail: a film played here and later in Lampa keeps the card, so it says "here"; a column per point if it matters
const NEWS_DAYS = 30;
const NEWS_MAX = 20;
const HISTORY_MAX = 100;
const HISTORY_TITLES = 150; // the newest: their JSON and episodes' keys are the CPU (~5 ms locally, as /mine)
const TRIED_S = 60;
const DATED = Date.UTC(2020, 0, 1); // Lampa's undated points say 1970
// [[season, episode], ...] in order → "S1 E6", "S1 E6 and E7", "S1 E3 to E7" (one run), "S1 E1, E5 and S2 E1"
function episodesText(eps) {
  if (!eps.length) return '';
  const name = ([s, e], i) => (i > 0 && s === eps[i - 1][0] ? `E${e}` : `S${s} E${e}`);
  const run = eps.every(([s, e], i) => i === 0 || (s === eps[i - 1][0] && e === eps[i - 1][1] + 1));
  if (eps.length > 2 && run) return `${name(eps[0], 0)} to E${eps.at(-1)[1]}`;
  const names = eps.map(name);
  return names.length === 1 ? names[0] : `${names.slice(0, -1).join(', ')} and ${names.at(-1)}`;
}
async function historyPage(request, env) {
  const who = await cubUser(request, env);
  if (!who) return corsError(request, 'CUB sign-in required.', 401);
  const dump = await cubApi(request, 'bookmarks/dump');
  if (!dump || !Array.isArray(dump.bookmarks)) return corsError(request, 'CUB unavailable.', 502);
  const tz = Math.max(-840, Math.min(840, Number(new URL(request.url).searchParams.get('tz')) || 0)) * 60e3;
  const titles = new Map(); // "movie_603" → {type, card, t}: each title once, its newest history bookmark
  for (const b of dump.bookmarks.filter((x) => x.type === 'history').sort((x, y) => (Number(y.time) || 0) - (Number(x.time) || 0))) {
    if (titles.size >= HISTORY_TITLES) break;
    const h = /^(movie|tv)$/.test(b.card_type) && !titles.has(`${b.card_type}_${b.card_id}`) && bookmarkCard(b);
    if (h) titles.set(`${h.type}_${h.card.id}`, { ...h, t: Number(b.time) || 0 });
  }
  const info = await shows(env, [...titles.values()].filter((h) => h.type === 'tv').map((h) => Number(h.card.id)));
  const of = new Map(); // Lampa's key → [title, season, episode]
  for (const [key, h] of titles) {
    if (h.type === 'movie') of.set(lampaHash(h.card.original_title || h.card.title || ''), [key, 0, 0]);
    const s = h.type === 'tv' && info.get(Number(h.card.id));
    for (const [n, count] of s ? JSON.parse(s.seasons || '[]') : []) {
      for (let e = 1; e <= count; e++) of.set(episodeKey([n, e], s.original), [key, n, e]);
    }
  }
  // ponytail: every point read (433 on 2026-11-02, one small query), as GET /timeline does
  const points = env.DB ? await env.DB.prepare('SELECT * FROM timeline').all().then((r) => r.results).catch(() => []) : [];
  const played = new Map();
  for (const p of points) {
    const at = p.card ? [p.card, p.season || 0, p.episode || 0] : of.get(p.hash);
    if (!at || !titles.has(at[0]) || (p.time < TRIED_S && p.percent < 90)) continue;
    if (!played.has(at[0])) played.set(at[0], []);
    played.get(at[0]).push({ ...p, s: at[1], e: at[2] });
  }
  const items = [];
  const byEpisode = (a, b) => a.s - b.s || a.e - b.e;
  for (const [key, h] of titles) {
    const all = played.get(key) || [];
    const days = new Map();
    for (const p of all.filter((x) => x.updated > DATED)) {
      const d = Math.floor((p.updated + tz) / 86400e3);
      days.set(d, [...(days.get(d) || []), p]);
    }
    const cubDay = Math.floor((h.t + tz) / 86400e3);
    if (!days.size) days.set(null, all);
    else if (!days.has(cubDay) && h.t > Math.max(...[...days.values()].flat().map((p) => p.updated))) days.set(cubDay, []);
    for (const [d, ps] of days) {
      const newest = d === null ? [...ps].sort(byEpisode).at(-1) : ps.reduce((a, b) => (!a || b.updated > a.updated ? b : a), null);
      const eps = h.type !== 'tv' ? [] : d === null ? (newest?.s > 0 ? [newest] : [])
        : [...new Map(ps.sort(byEpisode).filter((p) => p.s > 0).map((p) => [`${p.s}:${p.e}`, p])).values()];
      items.push([d === null || !newest ? h.t : newest.updated, h, episodesText(eps.map((p) => [p.s, p.e])),
        !newest ? -1 : newest.percent >= 90 ? 0 : Math.max(0, newest.duration - newest.time), ps.length && ps.every((p) => !p.card) ? 1 : 0,
        d === null ? (newest ? [newest.hash] : []) : ps.map((p) => p.hash)]);
    }
  }
  items.sort((a, b) => b[0] - a[0]);
  const shown = items.slice(0, HISTORY_MAX);
  // English cards: a series' from shows, the others from the search index or TMDB (15 a request at most)
  const rest = [...new Map(shown.map(([, h]) => h).filter((h) => !(h.type === 'tv' && info.has(Number(h.card.id))))
    .map((h) => [`${h.type}:${h.card.id}`, h])).values()];
  const english = await englishCards(env, rest.map((h) => [h.type, Number(h.card.id)]), rest.map((h) => cardOf(h.card, h.type)), 15);
  const cards = new Map(rest.map((h, i) => [`${h.type}:${h.card.id}`, english[i]]));
  const cardFor = (h) => (h.type === 'tv' && info.has(Number(h.card.id)) ? JSON.parse(info.get(Number(h.card.id)).card)
    : cards.get(`${h.type}:${h.card.id}`));
  return new Response(JSON.stringify({ items: shown.map(([t, h, ...more]) => [t, cardFor(h), ...more]) }), {
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...corsHeaders(request) },
  });
}

// POST /mine/unwatch {hashes: [Lampa's key, ...]} (the same sign-in) -> 204: History's Mark unwatched. The points go
// back to the start, stamped now, so Lampa takes them too (the newer wins).
async function unwatch(request, env) {
  if (!(await cubUser(request, env))) return corsError(request, 'CUB sign-in required.', 401);
  const hashes = (await request.json().catch(() => ({}))).hashes;
  if (!Array.isArray(hashes) || !hashes.length || hashes.length > 2000 || !hashes.every((h) => /^\d{1,10}$/.test(String(h)))) {
    return corsError(request, 'Bad hashes.', 400);
  }
  if (!env.DB) return corsError(request, 'No database.', 502);
  await env.DB.prepare('UPDATE timeline SET time = 0, percent = 0, updated = ?2 WHERE hash IN (SELECT value FROM json_each(?1))')
    .bind(JSON.stringify(hashes.map(String)), Date.now()).run();
  return new Response(null, { status: 204, headers: corsHeaders(request) });
}

// POST /mine/<list>/movie/<tmdb id> or /mine/<list>/tv/<tmdb id> (the same sign-in) -> 204. history: a film played
// here goes to the front of Lampa's history, as Lampa puts one there when its torrents open (torrent.js): Lampa's
// recommendations and Continue browsing come from it; one already there is moved (its bookmark id, as Lampa sends
// it). The card as Lampa keeps one (Utils.clearCard's fields, TMDB en-US). scheduled, thrown, viewed: the film's
// mark (one at a time, as Lampa's), none: no mark; in D1 (CUB's marks need Premium). unhistory: out of the history
// (History's Remove), as Lampa removes one.
// lampa-native's voice search: the remote's voice button records the request on the stick (16 kHz WAV).
// POST /voice/hear (the stick's X-Play-Token, the WAV) -> {text}: Deepgram Nova-3 on Workers AI (~0.4 s; English,
// Russian). The stick then asks Claude Code on a Mac at home what was meant (voice-claude.py, voice-prompt.json's
// answer) and POSTs /voice/find {text, understood} -> {intent, by, open, results: [Home card + confidence 0-100, -1
// = found by the filters]}: each guess looked up on TMDB (a year off by more than one halves it), a recommendation's
// filters searched too (TMDB discover). Without "understood" (the MacBook off, the plan's limit reached) a free
// Workers AI model reads the text here: titles as well as Claude in the test of 2026-10-31, recommendations worse.
// open: one title that sure (70+) and 50 ahead of the next opens at once (an exact title Claude rates 70-80, a same-name
// one 10: Foundation, Темная материя, 2026-10-31).
const HEARS = '@cf/deepgram/nova-3';
const READS = '@cf/meta/llama-4-scout-17b-16e-instruct';
const VOICE_WAV_MAX = 1e6; // 30 s
const GENRES = {
  movie: { action: 28, adventure: 12, animation: 16, comedy: 35, crime: 80, documentary: 99, drama: 18, family: 10751,
    fantasy: 14, history: 36, horror: 27, music: 10402, mystery: 9648, romance: 10749, 'science fiction': 878,
    'sci-fi': 878, thriller: 53, war: 10752, western: 37 },
  tv: { 'action & adventure': 10759, action: 10759, adventure: 10759, animation: 16, comedy: 35, crime: 80,
    documentary: 99, drama: 18, family: 10751, kids: 10762, mystery: 9648, 'sci-fi & fantasy': 10765,
    'science fiction': 10765, 'sci-fi': 10765, fantasy: 10765, 'war & politics': 10768, war: 10768, western: 37 },
};

const voicePrompt = () => VOICE.system.join('\n').replace('{today}', new Date().toISOString().slice(0, 10));
const jsonIn = (s) => { try { return JSON.parse(s.slice(s.indexOf('{'), s.lastIndexOf('}') + 1)); } catch { return null; } };
const voiceAnswer = (request, o) => new Response(JSON.stringify(o), {
  headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...corsHeaders(request) },
});

async function voiceHear(request, env) {
  if (!isStick(request, env)) return corsError(request, 'Forbidden.', 403);
  if (Number(request.headers.get('Content-Length')) > VOICE_WAV_MAX) return corsError(request, 'A WAV of 30 s at most.', 413);
  const wav = await request.arrayBuffer();
  if (wav.byteLength <= 44 || wav.byteLength > VOICE_WAV_MAX) return corsError(request, 'A WAV of 30 s at most.', 413);
  try {
    const o = await env.AI.run(HEARS, { audio: { body: new Blob([wav]).stream(), contentType: 'audio/wav' },
      detect_language: true, smart_format: true });
    return voiceAnswer(request, { text: (o?.results?.channels?.[0]?.alternatives?.[0]?.transcript || '').trim() });
  } catch {
    return corsError(request, 'Speech to text failed.', 502);
  }
}

// A guess on TMDB: its year first, any year when that finds nothing.
async function voiceLookUp(env, guess) {
  const type = guess.type === 'tv' ? 'tv' : 'movie';
  const year = Math.trunc(Number(guess.year)) || 0;
  const look = (extra) => tmdb(env, `search/${type}`,
    `&include_adult=false&query=${encodeURIComponent(guess.title.slice(0, 100))}${extra}`)
    .then((res) => (res.ok ? res.json() : {})).then((o) => (o.results || []).filter((m) => m.poster_path))
    .catch(() => []);
  const m = (year && (await look(`&${type === 'tv' ? 'first_air_date_year' : 'year'}=${year}`))[0]) || (await look(''))[0];
  if (!m) return null;
  const off = year && Math.abs(Number((m.release_date || m.first_air_date || '').slice(0, 4)) - year) > 1;
  return [...cardOf(m, type), Math.round(Math.min(1, Math.max(0, Number(guess.confidence) || 0)) * (off ? 50 : 100))];
}

// A recommendation's filters: keywords (any of 3), genres (any), years (up to today), family = rated PG at most.
async function voiceDiscover(env, d) {
  const type = d.type === 'tv' ? 'tv' : 'movie';
  const date = type === 'tv' ? 'first_air_date' : 'primary_release_date';
  const keywords = (await Promise.all((Array.isArray(d.keywords) ? d.keywords : []).slice(0, 3)
    .map((k) => tmdb(env, 'search/keyword', `&query=${encodeURIComponent(String(k).slice(0, 50))}`)
      .then((res) => (res.ok ? res.json() : {})).then((o) => o.results?.[0]?.id).catch(() => null)))).filter(Boolean);
  const genres = new Set((Array.isArray(d.genres) ? d.genres : []).map((g) => GENRES[type][String(g).toLowerCase()]));
  if (d.family && type === 'tv') genres.add(10751).add(10762);
  genres.delete(undefined);
  const today = new Date().toISOString().slice(0, 10);
  const from = Math.trunc(Number(d.from)) || 0, to = Math.trunc(Number(d.to)) || 0;
  const query = `&include_adult=false&sort_by=popularity.desc&vote_count.gte=${from >= Number(today.slice(0, 4)) - 2 ? 20 : 100}`
    + (from ? `&${date}.gte=${from}-01-01` : '') + `&${date}.lte=${to && `${to}-12-31` < today ? `${to}-12-31` : today}`
    + (keywords.length ? `&with_keywords=${keywords.join('|')}` : '') + (genres.size ? `&with_genres=${[...genres].join('|')}` : '')
    + (d.family && type === 'movie' ? '&certification_country=US&certification.lte=PG' : '');
  const o = await tmdb(env, `discover/${type}`, query).then((res) => (res.ok ? res.json() : {})).catch(() => ({}));
  return (o.results || []).filter((m) => m.poster_path).map((m) => [...cardOf(m, type), -1]);
}

async function voiceFind(request, env) {
  if (!isStick(request, env)) return corsError(request, 'Forbidden.', 403);
  const ask = await request.json().catch(() => null);
  const text = String(ask?.text || '').trim().slice(0, 300);
  let understood = ask?.understood && typeof ask.understood === 'object' ? ask.understood : null;
  const by = understood ? 'claude' : 'workers-ai';
  if (!understood && text && env.AI) {
    const o = await env.AI.run(READS, { messages: [{ role: 'system', content: voicePrompt() }, { role: 'user', content: text }],
      max_tokens: 1500 }).catch(() => null);
    understood = typeof o?.response === 'object' ? o.response : jsonIn(String(o?.response || ''));
  }
  if (!understood) return corsError(request, 'Not understood.', 502);
  const intent = ['title', 'describe', 'recommend'].includes(understood.intent) ? understood.intent : 'title';
  const guesses = (Array.isArray(understood.candidates) ? understood.candidates : [])
    .filter((c) => c && typeof c.title === 'string' && c.title.trim()).slice(0, 8);
  const [found, more] = await Promise.all([Promise.all(guesses.map((g) => voiceLookUp(env, g))),
    intent === 'recommend' && understood.discover ? voiceDiscover(env, understood.discover) : []]);
  const seen = new Set();
  const results = [...found.filter(Boolean), ...more]
    .filter((c) => !seen.has(`${c[1]}:${c[0]}`) && seen.add(`${c[1]}:${c[0]}`)).slice(0, 20);
  const [a, b] = results;
  const open = intent !== 'recommend' && Boolean(a) && a[7] >= 70 && (!b || b[7] <= a[7] - 50);
  return voiceAnswer(request, { intent, by, open, results });
}

const LAMPA_FIELDS = ['poster_path', 'overview', 'release_date', 'id', 'original_title', 'original_language', 'title',
  'backdrop_path', 'popularity', 'vote_count', 'vote_average', 'imdb_id', 'original_name', 'name', 'first_air_date',
  'origin_country', 'status', 'number_of_seasons', 'number_of_episodes'];
async function bookmark(request, env, list, type, id) {
  const who = await cubUser(request, env);
  if (!who) return corsError(request, 'CUB sign-in required.', 401);
  if (!['history', 'book', 'unbook', 'unhistory'].includes(list)) {
    if (!env.DB) return corsError(request, 'No database.', 502);
    await (list === 'none'
      ? env.DB.prepare('DELETE FROM marks WHERE who = ? AND card = ?').bind(who, `${type}_${id}`)
      : env.DB.prepare(`INSERT INTO marks (who, card, mark, t) VALUES (?1, ?2, ?3, ?4)
          ON CONFLICT (who, card) DO UPDATE SET mark = excluded.mark, t = excluded.t`).bind(who, `${type}_${id}`, list, Date.now())
    ).run();
    return new Response(null, { status: 204, headers: corsHeaders(request) });
  }
  // removing: each bookmark with the card CUB keeps in it (as Lampa sends one), no TMDB; unhistory is History's Remove
  const removes = { unbook: ['book', 'wath'], unhistory: ['history'] }[list];
  const [res, dump] = await Promise.all([removes ? null : tmdb(env, `${type}/${id}`), cubApi(request, 'bookmarks/dump')]);
  if ((!removes && !res.ok) || !dump || !Array.isArray(dump.bookmarks)) return corsError(request, 'TMDB or CUB unavailable.', 502);
  const mine = dump.bookmarks.filter((b) => String(b.card_id) === String(id) && b.card_type === type);
  const send = async (method, kind, bookmarkId, data) => {
    const json = await cubApi(request, `bookmarks/${method}`, { type: kind, data, card_id: String(id), id: String(bookmarkId) });
    if (!json || json.error) throw new Error(`CUB refused ${method} ${kind}${json ? ` (${json.code})` : ''}`);
  };
  try {
    if (removes) {
      for (const b of mine.filter((x) => removes.includes(x.type))) {
        await send('remove', b.type, b.id, typeof b.data === 'string' ? b.data : JSON.stringify(b.data));
      }
    } else {
      const m = await res.json();
      const card = { source: 'tmdb', genre_ids: (m.genres || []).map((g) => g.id) };
      for (const k of LAMPA_FIELDS) if (m[k] != null) card[k] = m[k];
      if (card.poster_path) card.img = `https://image.tmdb.org/t/p/w300${card.poster_path}`;
      await send('add', list, mine.find((b) => b.type === list)?.id ?? 0, JSON.stringify(card));
    }
  } catch (e) {
    return corsError(request, `${e.message}.`, 502);
  }
  return new Response(null, { status: 204, headers: corsHeaders(request) });
}

export default {
  async scheduled(event, env) {
    if (!env.DB) return undefined;
    if (event.cron === HOURLY_CRON) {
      const empty = !(await env.DB.prepare('SELECT 1 FROM uhd LIMIT 1').first()); // the first run fills it at once
      if (new Date(event.scheduledTime).getUTCHours() === 4 || empty) {
        return refreshUhd(env, event.scheduledTime).catch((e) => console.log('uhd', String(e)));
      }
      // follows beside For you (25 of the 50 subrequests), not beside New in 4K (up to 35); a failure stays theirs
      const night = [1, 2, 3].includes(new Date(event.scheduledTime).getUTCHours());
      return Promise.all([env.GRADES && refreshForYou(env).catch((e) => console.log('for you', String(e))),
        refreshFollows(env, event.scheduledTime).catch((e) => console.log('follows', e)),
        night && dropProbeAdds(env, event.scheduledTime).catch((e) => console.log('adds', e))]);
    }
    return TITLE_CRONS[event.cron] ? refreshTitles(env, TITLE_CRONS[event.cron]) : undefined;
  },

  // Whatever a route throws (a DB or network error nobody caught) is a 500 with CORS and a log line, not Cloudflare's
  // error page: the Lampa plugin can read the first, and `wrangler tail` shows which route failed.
  async fetch(request, env, ctx) {
    try {
      return await route(request, env, ctx);
    } catch (e) {
      console.log('failed', request.method, new URL(request.url).pathname, String(e));
      return corsError(request, 'The worker failed.', 500);
    }
  },
};

async function route(request, env, ctx) {
  if (request.method === 'OPTIONS') {
    return new Response(null, { status: 204, headers: corsHeaders(request) });
  }

  if (!METHODS.includes(request.method)) {
    return corsError(request, `${request.method}: this worker answers ${METHODS.join(', ')} and OPTIONS.`, 405);
  }

  // The app's screens spend the TMDB key (and the Workers quota): the stick's token first, as /card. No other client
  // asks them: the Lampa plugin uses /grades, /timeline, /mdblist and ?url= only; imdb-import.py sends the token.
  if (/^\/(home|search|season\/|person\/|find\/|discover\/)/.test(new URL(request.url).pathname) && !isStick(request, env)) {
    return corsError(request, 'Forbidden.', 403);
  }
  if (new URL(request.url).pathname === '/grades' && request.method === 'POST') return grades(request, env);
  if (new URL(request.url).pathname === '/home' && request.method === 'GET') return home(request, env);
  if (new URL(request.url).pathname === '/search' && request.method === 'GET') return search(request, env);
  const film = new URL(request.url).pathname.match(/^\/card\/(movie|tv)\/(\d+)$/);
  if (film && request.method === 'GET') { // the token first: each new address spends MDBList's daily 1,000
    if (!isStick(request, env)) return corsError(request, 'Forbidden.', 403);
    return edgeCached(request, ctx, () => filmCard(request, env, film[1], film[2]));
  }
  const season = new URL(request.url).pathname.match(/^\/season\/(\d+)\/(\d+)$/);
  if (season && request.method === 'GET') {
    return edgeCached(request, ctx, () => seasonPage(request, env, season[1], season[2]));
  }
  const actor = new URL(request.url).pathname.match(/^\/person\/(\d+)$/);
  if (actor && request.method === 'GET') return edgeCached(request, ctx, () => personPage(request, env, actor[1]));
  const imdb = new URL(request.url).pathname.match(/^\/find\/(tt\d{1,10})$/);
  if (imdb && request.method === 'GET') return edgeCached(request, ctx, () => findImdb(request, env, imdb[1]));
  const picked = new URL(request.url).pathname.match(/^\/discover\/([a-z-]+)$/);
  if (picked && request.method === 'GET') return edgeCached(request, ctx, () => discover(request, env, picked[1]));
  const ext = new URL(request.url).pathname.match(/^\/ext\/([a-z][a-z0-9]{0,15})(\/[\w/-]*)$/);
  if (ext && /^(GET|POST)$/.test(request.method)) {
    return extension(request, env, ext[1], ext[2] + new URL(request.url).search);
  }
  if (new URL(request.url).pathname === '/torbox' && request.method === 'GET') return torboxAccount(request, env);
  const drop = new URL(request.url).pathname.match(/^\/torbox\/drop\/([0-9a-f]{40})$/);
  if (drop && request.method === 'POST') return torboxDrop(request, env, drop[1]);
  const mv = new URL(request.url).pathname.match(/^\/maxvol\/(tt\d+)$/);
  if (mv && request.method === 'GET') {
    if (!isStick(request, env)) return corsError(request, 'Forbidden.', 403);
    return env.DB ? new Response(JSON.stringify(await maxvolAudio(env, mv[1])), {
      headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
    }) : corsError(request, 'No database.', 503);
  }
  // The streams addon's address, for the app to ask it itself when it refuses the worker (Cloudflare's shared IPs)
  if (new URL(request.url).pathname === '/addon' && request.method === 'GET') {
    if (!isStick(request, env)) return corsError(request, 'Forbidden.', 403);
    return env.STREAM_ADDON ? new Response(env.STREAM_ADDON) : corsError(request, 'No streams addon set.', 404);
  }
  const src = new URL(request.url).pathname.match(/^\/streams\/(movie|series)\/(tt\d+)(?:\/(\d+)\/(\d+))?$/);
  if (src && /^(GET|POST)$/.test(request.method) && (src[1] === 'series') === Boolean(src[3])) {
    return sources(request, env, ctx, src[1], src[2], src[3], src[4]);
  }

  if (/^\/segments\/(jobs|own)$/.test(new URL(request.url).pathname) && env.DB) return introJobs(request, env);
  const skip = new URL(request.url).pathname.match(/^\/segments\/(movie|tv)\/(\d+)\/(tt\d+)$/);
  if (skip && request.method === 'GET') return segments(request, env, skip[1], skip[2], skip[3]);
  const tracks = new URL(request.url).pathname.match(/^\/audio\/([0-9a-f]{40})$/);
  if (tracks && request.method === 'GET') return audio(request, env, tracks[1]);
  const link = new URL(request.url).pathname.match(/^\/play\/([0-9a-f]{40})$/);
  if (link && request.method === 'GET') return play(request, env, ctx, link[1]);
  const file = new URL(request.url).pathname.match(/^\/stream\/(\d+)\/(\d+)$/);
  if (file && /^(GET|HEAD)$/.test(request.method)) return stream(request, env, ctx, file[1], file[2]);
  if (new URL(request.url).pathname === '/timeline') return timeline(request, env);
  if (new URL(request.url).pathname === '/mine' && request.method === 'GET') return mine(request, env);
  if (new URL(request.url).pathname === '/mine/history' && request.method === 'GET') return historyPage(request, env);
  if (new URL(request.url).pathname === '/mine/unwatch' && request.method === 'POST') return unwatch(request, env);
  const followed = new URL(request.url).pathname.match(/^\/mine\/(follow|unfollow)\/(\d{1,10})$/);
  if (followed && request.method === 'POST') return follow(request, env, followed[1] === 'follow', followed[2]);
  const listed = new URL(request.url).pathname
    .match(/^\/mine\/(history|unhistory|book|unbook|viewed|scheduled|thrown|none)\/(movie|tv)\/(\d+)$/);
  if (listed && request.method === 'POST') return bookmark(request, env, listed[1], listed[2], listed[3]);
  if (new URL(request.url).pathname === '/voice/hear' && request.method === 'POST') return voiceHear(request, env);
  if (new URL(request.url).pathname === '/voice/find' && request.method === 'POST') return voiceFind(request, env);

  // The Lampa plugin's routes, only when wanted (PASS_THROUGH=1 in wrangler.toml's [vars]): open to anyone, they
  // spend the MDBList key and pass requests through
  if (env.PASS_THROUGH !== '1') return corsError(request, 'Not found.', 404);
  const scores = new URL(request.url).pathname.match(/^\/mdblist\/(movie|show)(?:\/(\d+))?$/);
  if (scores && (scores[2] || request.method === 'POST')) return mdblist(request, env, scores[1], scores[2], ctx);
  // the Lampa plugin playing a torrent a probe added (its requestdl, answered by TorBox with its key): the user's
  // now, the night leaves it alone
  const out = await passThrough(request);
  const played = out.status === 200 ? pluginPlay(request) : null;
  if (played !== null && env.DB) {
    ctx.waitUntil(env.DB.prepare('DELETE FROM added WHERE id = ?').bind(played).run().catch(() => {}));
  }
  return out;
}
