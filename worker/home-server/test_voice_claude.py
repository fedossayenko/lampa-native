"""Tests for voice-claude.py's request checks (python3 -I worker/home-server/test_voice_claude.py; tools/check.sh
runs it): a signature, its time window, a replay, the body's size. Claude itself is replaced, nothing is started."""
import importlib.util
import json
import os
import socket
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('voice_claude', os.path.join(HERE, 'voice-claude.py'))
vc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(vc)
real_understand = vc.understand  # setUp replaces it; one test runs the real one


class Checks(unittest.TestCase):
    def setUp(self):
        self.gate = threading.Event()
        token = tempfile.NamedTemporaryFile('w', delete=False)
        token.write('abc\n')
        token.close()
        self.addCleanup(os.unlink, token.name)
        vc.TOKEN = token.name
        vc.seen.clear()
        self.asked = []
        vc.understand = lambda text, taste='': (self.asked.append((text, taste)), {'intent': 'title'})[1]
        vc.recap = lambda o: (self.gate.wait(3), 'x' * 400)[1]
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), vc.Handler)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.addCleanup(self.gate.set)
        self.last = 0

    def stamp(self):
        self.last = max(self.last + 1, int(time.time() * 1000))  # two requests never share a millisecond
        return self.last

    def post(self, path, body, when=None, sig=None, raw=None):
        data = json.dumps(body).encode() if raw is None else raw
        when = str(self.stamp()) if when is None else when
        sig = vc.signed('abc', when, data) if sig is None else sig
        req = urllib.request.Request(f'http://127.0.0.1:{self.port}{path}', data=data,
                                     headers={'X-Home-Time': when, 'X-Home-Sig': sig})
        try:
            return urllib.request.urlopen(req, timeout=10).status
        except urllib.error.HTTPError as e:
            return e.code

    def test_signed_request_is_answered_and_not_twice(self):
        when = str(self.stamp())
        body = json.dumps({'text': 'dune'}).encode()
        sig = vc.signed('abc', when, body)
        self.assertEqual(self.post('/understand', {'text': 'dune'}, when, sig), 200)
        self.assertEqual(self.post('/understand', {'text': 'dune'}, when, sig), 403, 'a replay')

    def test_taste_goes_to_claude(self):
        self.assertEqual(self.post('/understand', {'text': 'scare me', 'taste': 'Loved: Alien'}), 200)
        self.assertEqual(self.post('/understand', {'text': 'dune'}), 200)
        self.assertEqual(self.asked, [('scare me', 'Loved: Alien'), ('dune', '')])

    def test_wrong_signature_stale_time_and_changed_body(self):
        self.assertEqual(self.post('/understand', {'text': 'x'}, sig='0' * 64), 403)
        self.assertEqual(self.post('/understand', {'text': 'x'}, when=str(self.stamp() - 120000)), 403, 'stale')
        self.assertEqual(self.post('/understand', {'text': 'x'}, when=str(self.stamp() + 120000)), 403, 'from the future')
        when = str(self.stamp())
        sig = vc.signed('abc', when, b'{"text":"x"}')
        self.assertEqual(self.post('/understand', None, when, sig, raw=b'{"text":"y"}'), 403, 'another body')
        self.assertEqual(self.post('/nope', {}), 403)

    def test_body_size(self):
        self.assertEqual(self.post('/understand', {'text': 'x' * 33000}), 413)  # text and taste: 32 KB
        # a negative length would read to the end of the connection before any check
        with socket.create_connection(('127.0.0.1', self.port), timeout=5) as s:
            s.sendall(b'POST /understand HTTP/1.0\r\nContent-Length: -1\r\n\r\n')
            self.assertIn(b' 413 ', s.recv(200))

    def test_one_recap_per_key_at_a_time(self):
        first = []
        t = threading.Thread(target=lambda: first.append(self.post('/recap', {'key': 'tv_1 S1E1'})))
        t.start()
        time.sleep(0.5)
        self.assertEqual(self.post('/recap', {'key': 'tv_1 S1E1'}), 429)
        self.gate.set()
        t.join()
        self.assertEqual(first, [200])
        self.assertEqual(self.post('/recap', {'key': 'tv_1 S1E1'}), 200, 'free again')

    def test_understanding_cap(self):
        for _ in range(vc.UNDERSTANDING):
            self.assertTrue(vc.understanding.acquire(blocking=False))
        self.addCleanup(lambda: [vc.understanding.release() for _ in range(vc.UNDERSTANDING)])
        self.assertEqual(self.post('/understand', {'text': 'dune'}), 429)



class Taste(unittest.TestCase):
    def test_taste_reaches_claude_after_the_request_capped(self):
        sent = []

        class Fake:  # claude -p: what it was given, a fixed answer
            def __init__(self):
                self.stdin = self
                self.stdout = iter(['{"type": "result", "result": "{\\"intent\\": \\"recommend\\"}"}\n'])

            def write(self, s):
                sent.append(json.loads(s)['message']['content'])

            def flush(self):
                pass

            def kill(self):
                pass

            def wait(self):
                pass
        was = vc.take, vc.understand
        self.addCleanup(lambda: setattr(vc, 'take', was[0]) or setattr(vc, 'understand', was[1]))
        vc.take, vc.understand = Fake, real_understand
        self.assertEqual(vc.understand('Scare me', 'Loved: Alien (1979)'), {'intent': 'recommend'})
        self.assertEqual(sent[-1], 'Scare me\n\nAbout the viewer:\nLoved: Alien (1979)')
        vc.understand('dune')
        self.assertEqual(sent[-1], 'dune', 'no taste: the request alone')
        vc.understand('x', 'y' * 9000)
        self.assertEqual(len(sent[-1]), len('x\n\nAbout the viewer:\n') + 6000)


if __name__ == '__main__':
    unittest.main()
