-- lampa-native's audio chips on sources ("RU DD+ 7.1", "EN TrueHD Atmos"): each release's audio tracks, read once
-- from its video file's header (audioTracks), and the TorBox adds spent reading releases nobody played (a budget:
-- TorBox allows ~50 adds an hour, and Play needs them). Applied with:
--   npx wrangler@4 d1 execute <database> --remote --file audio.sql
CREATE TABLE IF NOT EXISTS audio (
  hash TEXT PRIMARY KEY, -- the torrent's info hash (a season's torrent: its first file read, episodes share tracks)
  tracks TEXT NOT NULL,  -- JSON [[lang, codec, channels, atmos 0/1, name], ...]; [] = not MKV/MP4 or no audio;
                         -- null = couldn't be read (asked again a day later)
  t INTEGER NOT NULL     -- read at (ms)
);
CREATE TABLE IF NOT EXISTS probes (
  t INTEGER NOT NULL     -- a TorBox add for reading one (ms); rows older than an hour go
);
CREATE TABLE IF NOT EXISTS plays (
  hash TEXT PRIMARY KEY, -- a release Play asked for: a torrent a probe added stays in the account; a week kept
  t INTEGER NOT NULL     -- (ms)
);
CREATE TABLE IF NOT EXISTS added (
  hash TEXT PRIMARY KEY, -- a torrent a probe added to the TorBox account: taken out at night unless played since
  id INTEGER NOT NULL,   -- TorBox's torrent id
  t INTEGER NOT NULL     -- added at (ms)
);
CREATE TABLE IF NOT EXISTS maxvol (
  imdb TEXT PRIMARY KEY, -- a film's (a series': all its seasons) releases on MaxVol
  tracks TEXT NOT NULL,  -- JSON {hash: [[lang, codec, channels, atmos 0/1, name], ...]} from its ffprobe; {} = none
  t INTEGER NOT NULL     -- asked at (ms): a day kept, an hour when {}
);
