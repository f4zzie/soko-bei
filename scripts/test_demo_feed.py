import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import demo_feed


class DemoFeedTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.now = 10000.0
        self.budget = demo_feed.RateBudget(Path(self.directory.name) / "budget.json", limit=2, clock=lambda: self.now)

    def test_quota_is_shared_across_process_instances_rotates_and_honors_retry_after(self):
        self.assertEqual(0, self.budget.reserve(0))
        self.assertEqual(0, self.budget.reserve(0))
        other = demo_feed.RateBudget(self.budget.path, limit=2, clock=lambda: self.now)
        self.assertEqual(3601, other.reserve(0))
        self.assertEqual(1, other.choose())
        other.block(1, 5000)
        self.assertEqual(2, self.budget.choose())
        self.assertEqual(5000, self.budget.reserve(1))
        self.now += 3602
        self.assertEqual(0, other.reserve(0))
        self.assertEqual(1398, self.budget.reserve(1))

    def test_production_refusal_precedes_plan_generation_or_login(self):
        with patch.dict("os.environ", {"BASE_URL": "https://demo.invalid"}), patch.object(demo_feed.Api, "request", return_value=demo_feed.Reply(200, {}, {"status": "ok", "dev_mode": False})) as request, patch.object(demo_feed, "load_plan") as plan:
            with self.assertRaisesRegex(RuntimeError, "DEV_MODE=true"):
                demo_feed.main(["trickle", "--state-dir", self.directory.name])
            plan.assert_not_called()
            request.assert_called_once_with("GET", "/health")

    def test_429_and_expired_login_retry_preserve_the_same_report(self):
        api = Mock()
        report = {"report_id": "3bdcb9a9-3dff-48cf-afb4-168b05a0654e", "price_kes": 4500}
        replies = iter([
            demo_feed.Reply(429, {"retry-after": "5"}, {}),
            demo_feed.Reply(401, {}, {}),
            demo_feed.Reply(201, {}, report),
        ])
        sent = []

        def request(method, path, body=None, token=None):
            if path == "/v1/reports":
                sent.append(dict(body))
                return next(replies)
            if path == "/v1/auth/otp/verify":
                return demo_feed.Reply(200, {}, {"access_token": "test-token"})
            if path == "/v1/me":
                return demo_feed.Reply(200, {}, {"current_consent_version": 2, "consent_version": 1})
            self.assertEqual("/v1/me/consent", path)
            self.assertEqual({"consent_version": 2}, body)
            return demo_feed.Reply(204, {}, {})

        api.request.side_effect = request
        feed = demo_feed.Feed(api, self.budget)
        waits = []

        def wait(seconds=0):
            waits.append(seconds)
            self.now += seconds

        feed.wait = wait
        with patch.object(demo_feed.time, "time", side_effect=lambda: self.now):
            self.assertEqual(201, feed.send(0, report))
        self.assertEqual([report, report, report], sent)
        self.assertIn(5, waits)
        self.assertEqual({"429": 1, "401": 1, "201": 1}, dict(feed.counts))
        self.assertNotIn("test-token", self.budget.path.read_text())

    def test_today_checkpoints_are_idempotent_and_contain_no_credentials(self):
        self.budget.mark("2026-11-04", "report-one")
        self.budget.mark("2026-11-04", "report-one")
        self.assertEqual({"report-one"}, self.budget.completed("2026-11-04"))
        self.assertEqual(set(), self.budget.completed("2026-11-05"))
        self.assertNotIn("tokens", json.loads(self.budget.path.read_text()))

    def test_non_https_and_embedded_credentials_are_refused(self):
        for url in ("", "http://localhost:8080", "https://user:password@demo.invalid"):
            with self.assertRaises(ValueError):
                demo_feed.Api(url)
        self.assertEqual(7, demo_feed.retry_after("Thu, 01 Jan 1970 00:00:10 GMT", 3))


if __name__ == "__main__":
    unittest.main()
