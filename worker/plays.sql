-- lampa-native's plays, one row each: what the stick played (the title, the release, its file) and how far it got:
-- Play's ask (GET /play) makes the row, DDD's reports after it (POST /timeline every 30 s, on pause, and its answer at
-- the end) move it on. A playlist's next episode gets a row of its own, the release of the one before it. For "why
-- does a play stop early" (GET /plays) and Your <year> (GET /mine/year). Kept for good: ~250 B a play. Applied with:
--   npx wrangler@4 d1 execute <database> --remote --file plays.sql
CREATE TABLE IF NOT EXISTS play_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  t INTEGER NOT NULL,      -- Play asked for it (ms); a playlist's next episode: its first report
  tl TEXT NOT NULL,        -- Lampa's timeline key (the film; an episode: season, episode and the series)
  card TEXT,               -- "movie_603", "tv_1399": the app's, else from the first report
  title TEXT,              -- its name as the app showed it (English)
  season INTEGER,          -- an episode's (null: a film)
  episode INTEGER,
  hash TEXT NOT NULL,      -- the torrent's info hash
  release TEXT,            -- the torrent's name on TorBox
  file TEXT,               -- the file Play picked (a playlist's next episode: null)
  start INTEGER NOT NULL DEFAULT 0, -- where it started (s): the resume point
  time INTEGER,            -- the newest report: position, duration (s), percent
  duration INTEGER,
  percent INTEGER,
  updated INTEGER,         -- that report's time (ms)
  end_by TEXT              -- DDD's answer when it closed: "completion" or "user" (null: none came: a report was last)
);
CREATE INDEX IF NOT EXISTS play_log_tl ON play_log (tl, t);
CREATE INDEX IF NOT EXISTS play_log_t ON play_log (t);
