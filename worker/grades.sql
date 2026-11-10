-- The Lampa plugin's film grades and lampa-native's, per CUB account and profile ("<user id>:<profile>"), one row a film
-- (they were one KV value an account: two devices posting together overwrote each other). Applied with:
--   npx wrangler@4 d1 execute <database> --remote --file grades.sql
-- The grades of an install that had them in KV (binding GRADES) move here by themselves: the first /grades or /mine of
-- an account with no rows here copies its KV value in (gradesOf), and the hourly run does it for the rest.
CREATE TABLE IF NOT EXISTS grades (
  who TEXT NOT NULL,
  card TEXT NOT NULL,  -- "movie_603" or "tv_1399"
  t INTEGER NOT NULL,  -- when it was given (ms): per film the newer one wins
  v TEXT NOT NULL,     -- the plugin's value as it sent it, JSON: {g: 2|1|0|-1|"skip"|null (removed, kept so the removal
                       -- reaches the other devices), t, card}
  PRIMARY KEY (who, card)
);
