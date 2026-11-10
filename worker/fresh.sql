-- lampa-native's /mine: new episodes of the series you follow, out with Russian or Ukrainian audio (Torrentio asked
-- at most every 3 hours per episode until it is). Applied with:
--   npx wrangler@4 d1 execute <database> --remote --file fresh.sql
CREATE TABLE IF NOT EXISTS fresh (
  key TEXT PRIMARY KEY, -- "<TMDB id>/<season>/<episode>", then "|<languages>" unless they are RU,UA (the setting)
  out INTEGER,          -- when a release with that audio was first seen (ms); null: not yet
  t INTEGER NOT NULL    -- last asked (ms)
);
