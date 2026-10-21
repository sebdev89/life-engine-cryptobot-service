"""Offline tests of scripts/pov/pov-collector.py (KAN-1222): release-truth, git and gh come from tests/fixtures,
the cryptobot API is a local fake. Run: python3 -m unittest discover -s scripts/pov/tests"""
import http.server
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(os.path.dirname(HERE), "pov-collector.py")
FIXTURES = os.path.join(HERE, "fixtures")
SHA = "a330d4b7fa534f4ade5e988b0acce2139ae87661"


def run(*args, env=None):
    e = dict(os.environ, POV_FIXTURES=FIXTURES, **(env or {}))
    if "POV_TOKEN_FILE" not in (env or {}):
        e.pop("POV_TOKEN_FILE", None)
    return subprocess.run([sys.executable, SCRIPT, *args], capture_output=True, text=True, env=e, timeout=60)


def dry(*workloads):
    out = run("--dry-run", "--workloads", ",".join(workloads))
    assert out.returncode == 0, out.stderr
    return json.loads(out.stdout)


class DryRun(unittest.TestCase):

    def test_an_accepted_change_becomes_one_value_event_with_measured_evidence(self):
        d = dry("cryptobot")
        self.assertEqual(len(d["events"]), 1)
        ev = d["events"][0]
        self.assertEqual(ev["taskId"], "cryptobot@a330d4b")
        self.assertEqual(ev["artifact"]["commitSha"], SHA)
        self.assertEqual(ev["artifact"]["prUrl"], "https://github.com/sebdev89/life-engine-cryptobot-service/pull/72")
        self.assertEqual(ev["artifact"]["imageDigest"], "sha256:147cdef6d561874dad5072ae61a5e91899ef926fea1ca9cfb9708ec3a84ac665")
        self.assertEqual(set(ev["acceptance"]["stages"].values()), {True})
        self.assertEqual(ev["acceptance"]["environment"], "uat-k8s")
        self.assertEqual(ev["acceptance"]["acceptedAt"], "2026-10-21T12:33:14Z")
        # reproducible: the deploy/deploy commit release-truth ran from + the hash of its report
        self.assertRegex(ev["acceptance"]["evidenceRef"], r"^release-truth@e25842d uat cryptobot sha256:[0-9a-f]{64}$")

    def test_contributors_come_from_trailers_never_from_invented_shares(self):
        ev = dry("cryptobot")["events"][0]
        # an `Agent: cryptobot` trailer names the agent; the owner specifies and reviews; no units or shares here
        self.assertEqual([(c["identityId"], c["role"]) for c in ev["contributions"]],
                         [("sebas", "SPECIFIER"), ("agent-cryptobot", "IMPLEMENTER"), ("sebas", "REVIEWER")])
        self.assertTrue(all(set(c) == {"identityId", "role"} for c in ev["contributions"]))

    def test_a_change_without_agent_trailers_is_implemented_by_the_owner(self):
        ev = dry("personal-ops")["events"][0]
        self.assertIn(("sebas", "IMPLEMENTER"), [(c["identityId"], c["role"]) for c in ev["contributions"]])

    def test_a_workload_that_is_not_measured_accepted_is_skipped_with_the_reason(self):
        d = dry("command")
        self.assertEqual(d["events"], [])
        self.assertIn("ACCEPTED(functional_acceptance=UNKNOWN)", d["skipped"][0]["reason"])

    def test_one_event_per_change_even_when_two_workloads_run_the_same_commit(self):
        d = dry("cryptobot", "cryptobot-signer")
        self.assertEqual([e["taskId"] for e in d["events"]], ["cryptobot@a330d4b"])
        self.assertIn("same change as an earlier workload", d["skipped"][0]["reason"])

    def test_dry_run_says_how_many_devnet_transactions_it_would_send(self):
        d = dry("cryptobot", "personal-ops")
        self.assertEqual(d["anchors"]["wouldSend"], 2)
        out = run("--dry-run", "--workloads", "cryptobot,personal-ops", "--max-anchors", "1")
        self.assertEqual(json.loads(out.stdout)["anchors"]["wouldSend"], 1)
        self.assertIn("pov/equal-split/v1", d["units"])

    def test_the_default_is_the_allowlist_and_unknown_workloads_are_refused(self):
        out = run("--dry-run")
        self.assertEqual(out.returncode, 0, out.stderr)
        d = json.loads(out.stdout)
        seen = {e["taskId"].split("@")[0] for e in d["events"]} | {s["workload"] for s in d["skipped"]}
        # cryptobot-signer is in the registry and PASS, but not on the allowlist: never looked at
        self.assertEqual(seen, {"cryptobot", "command", "personal-ops"})
        bad = run("--dry-run", "--workloads", "nope")
        self.assertEqual(bad.returncode, 2)
        self.assertIn("not in release-truth's uat registry", bad.stderr)


class FakeApi(http.server.BaseHTTPRequestHandler):
    events, identities, posts = [], set(), []

    def log_message(self, *a):
        pass

    def _send(self, code, body=None):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        if body is not None:
            self.wfile.write(json.dumps(body).encode())

    def do_GET(self):
        if self.headers.get("Authorization") != "Bearer t0k":
            return self._send(401)
        if self.path.startswith("/api/cryptobot/value-events"):
            return self._send(200, FakeApi.events)
        if self.path.startswith("/api/cryptobot/identities/"):
            return self._send(200 if self.path.rsplit("/", 1)[1] in FakeApi.identities else 404, {})
        self._send(404)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        FakeApi.posts.append((self.path, body))
        if self.path == "/api/cryptobot/identities":
            FakeApi.identities.add(body["id"])
            return self._send(201, body)
        if self.path.startswith("/api/cryptobot/value-events"):
            ev = dict(body, id=f"ev-{len(FakeApi.events)}", createdAt="2026-10-21T13:00:00Z")
            FakeApi.events.append(ev)
            return self._send(201, ev)
        self._send(404)


class Record(unittest.TestCase):

    def setUp(self):
        FakeApi.events, FakeApi.identities, FakeApi.posts = [], {"sebas"}, []
        self.server = http.server.HTTPServer(("127.0.0.1", 0), FakeApi)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.tmp = tempfile.TemporaryDirectory()
        self.token = os.path.join(self.tmp.name, "token")
        with open(self.token, "w") as f:
            f.write("t0k\n")
        self.env = {"POV_TOKEN_FILE": self.token, "POV_API_BASE": f"http://127.0.0.1:{self.server.server_port}"}

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()

    def test_without_a_credential_it_refuses_to_record(self):
        out = run("--workloads", "cryptobot")
        self.assertEqual(out.returncode, 2)
        self.assertIn("credentials gate", out.stderr)

    def test_records_once_anchored_creating_missing_agent_identities(self):
        out = run("--workloads", "cryptobot", env=self.env)
        self.assertEqual(out.returncode, 0, out.stderr)
        paths = [p for p, _ in FakeApi.posts]
        self.assertEqual(paths, ["/api/cryptobot/identities", "/api/cryptobot/value-events?anchor=true"])
        self.assertEqual(FakeApi.posts[0][1], {"id": "agent-cryptobot", "kind": "AGENT", "displayName": "agent-cryptobot",
                                               "ownerId": "sebas"})
        # idempotent: the same commit is not recorded again
        again = run("--workloads", "cryptobot", env=self.env)
        self.assertEqual(again.returncode, 0, again.stderr)
        self.assertEqual(len([p for p, _ in FakeApi.posts if "value-events" in p]), 1)

    def test_the_daily_anchor_cap_counts_what_was_recorded_today(self):
        out = run("--workloads", "cryptobot,personal-ops", "--max-anchors", "1", env=dict(self.env))
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(len([p for p, _ in FakeApi.posts if "value-events" in p]), 1)
        self.assertIn("daily anchor cap reached", out.stderr)


if __name__ == "__main__":
    unittest.main()
