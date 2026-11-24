-- lampa-native's Netflix rows on Home (Settings → Country): each asked country's newest Top 10 week as Home rows,
-- read by the worker's own call (refreshTop10: at the first ask, then the hourly cron every 6 hours), and each title's
-- TMDB card, found once by its name. Applied with:
--   npx wrangler@4 d1 execute <database> --remote --file top10.sql
CREATE TABLE IF NOT EXISTS top10 (
  country TEXT PRIMARY KEY, -- ISO code, as Netflix's file: BG
  week TEXT NOT NULL,       -- the week's Sunday, 2026-11-15; '': not read yet
  rows TEXT NOT NULL,       -- [[title, [Home card, ...]], ...]: series, then films
  asked INTEGER NOT NULL,   -- the last Home that wanted it (ms, once a day): read while asked within 30 days
  t INTEGER NOT NULL        -- last read (ms)
);
CREATE TABLE IF NOT EXISTS top10_titles (
  key TEXT PRIMARY KEY,     -- "tv:Black Doves", "movie:Rush Hour"
  card TEXT NOT NULL,       -- its Home card, 'null': TMDB has none
  t INTEGER NOT NULL
);
