-- Your <year> (GET /mine/year): each title's genres and its directors (a series: its creators) from TMDB, asked once
-- when a year first counts it (a call of the worker's own a few titles at a time: a film's credits are ~50 KB of JSON
-- to parse). Applied with:
--   npx wrangler@4 d1 execute <database> --remote --file year.sql
CREATE TABLE IF NOT EXISTS title_info (
  type TEXT NOT NULL,      -- "movie" or "tv"
  id INTEGER NOT NULL,     -- TMDB's
  genres TEXT NOT NULL,    -- JSON ["Animation", "Family"]
  people TEXT NOT NULL,    -- JSON ["Denis Villeneuve"]: a film's directors, a series' creators
  t INTEGER NOT NULL,      -- asked at (ms)
  PRIMARY KEY (type, id)
);
