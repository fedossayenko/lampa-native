-- lampa-native's audio chips on sources ("RU DD+ 7.1", "EN TrueHD Atmos"): each release's audio tracks, read once
-- from its video file's header (audioTracks), and the TorBox adds spent reading releases nobody played (a budget:
-- TorBox allows ~50 adds an hour, and Play needs them). Applied with:
--   npx wrangler@4 d1 execute <database> --remote --file audio.sql
CREATE TABLE IF NOT EXISTS audio (
  hash TEXT PRIMARY KEY, -- the torrent's info hash (a season's torrent: its first file read, episodes share tracks)
  tracks TEXT NOT NULL,  -- JSON [[lang, codec, channels, atmos 0/1, name], ...]; [] = not MKV/MP4 or no audio
  t INTEGER NOT NULL     -- read at (ms)
);
CREATE TABLE IF NOT EXISTS probes (
  t INTEGER NOT NULL     -- a TorBox add for reading one (ms); rows older than an hour go
);
