#!/usr/bin/env python3
import argparse
from collections import Counter
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime
from email.utils import parsedate_to_datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import random
import signal
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from zoneinfo import ZoneInfo


PHONES = [f"07000020{index:02}" for index in range(40)]
EAT = ZoneInfo("Africa/Nairobi")


def today():
    return datetime.now(EAT).date().isoformat()


def retry_after(value, now):
    try:
        return max(1.0, float(value))
    except (TypeError, ValueError):
        try:
            return max(1.0, parsedate_to_datetime(value).timestamp() - now)
        except (TypeError, ValueError, OverflowError):
            return 60.0


@dataclass
class Reply:
    status: int
    headers: dict
    body: dict


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError(f"Refusing HTTP redirect ({code}) from the configured demo API")


class Api:
    def __init__(self, base):
        parsed = urllib.parse.urlsplit(base)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("BASE_URL must be the existing public HTTPS tunnel URL")
        self.base = base.rstrip("/")
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, method, path, body=None, token=None):
        headers = {"Accept": "application/json"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(self.base + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
        try:
            response = self.opener.open(req, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            try:
                data = json.loads(raw) if raw else {}
            except (ValueError, UnicodeDecodeError):
                data = {}
            return Reply(response.code, {key.lower(): value for key, value in response.headers.items()}, data)

    def require_dev(self):
        reply = self.request("GET", "/health")
        if reply.status != 200 or reply.body.get("dev_mode") is not True or reply.body.get("status") != "ok":
            raise RuntimeError("Refusing demo feed: public /health must report healthy DEV_MODE=true")


class RateBudget:
    def __init__(self, path, limit=30, clock=time.time):
        self.path = Path(path)
        self.limit = limit
        self.clock = clock
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)

    @contextmanager
    def state(self):
        with self.path.with_suffix(".lock").open("a+") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            data = json.loads(self.path.read_text()) if self.path.exists() else {}
            data.setdefault("attempts", [[] for _ in PHONES])
            data.setdefault("blocked_until", [0.0 for _ in PHONES])
            data.setdefault("completed", {})
            data.setdefault("next_user", 0)
            yield data
            temporary = self.path.with_suffix(".new")
            temporary.write_text(json.dumps(data, separators=(",", ":")))
            temporary.replace(self.path)

    def _wait(self, data, index):
        now = self.clock()
        attempts = [stamp for stamp in data["attempts"][index] if stamp > now - 3601]
        data["attempts"][index] = attempts
        quota = attempts[0] + 3601 - now if len(attempts) >= self.limit else 0
        return max(0.0, quota, data["blocked_until"][index] - now)

    def reserve(self, index):
        with self.state() as data:
            wait = self._wait(data, index)
            if wait == 0:
                data["attempts"][index].append(self.clock())
                data["next_user"] = (index + 1) % len(PHONES)
            return wait

    def choose(self):
        with self.state() as data:
            ordered = [(data["next_user"] + offset) % len(PHONES) for offset in range(len(PHONES))]
            return min(ordered, key=lambda index: self._wait(data, index))

    def block(self, index, seconds):
        with self.state() as data:
            data["blocked_until"][index] = max(data["blocked_until"][index], self.clock() + seconds)

    def reserve_auth(self):
        with self.state() as data:
            wait = max(0.0, data.get("next_auth", 0) - self.clock())
            if wait == 0:
                data["next_auth"] = self.clock() + 1.1
            return wait

    def completed(self, day):
        with self.state() as data:
            return set(data["completed"].get(day, []))

    def mark(self, day, report_id):
        with self.state() as data:
            done = set(data["completed"].get(day, []))
            done.add(report_id)
            data["completed"][day] = sorted(done)


class DurationFinished(Exception):
    pass


def stop(*_):
    raise KeyboardInterrupt()


class Feed:
    def __init__(self, api, budget, duration=None):
        self.api = api
        self.budget = budget
        self.deadline = None if duration is None else time.monotonic() + duration
        self.tokens = {}
        self.counts = Counter()

    def wait(self, seconds=0):
        remaining = float("inf") if self.deadline is None else self.deadline - time.monotonic()
        if remaining <= 0:
            raise DurationFinished()
        if seconds >= remaining:
            time.sleep(remaining)
            raise DurationFinished()
        if seconds:
            time.sleep(seconds)

    def login(self, index):
        for attempt in range(5):
            while delay := self.budget.reserve_auth():
                self.wait(delay)
            self.wait()
            reply = self.api.request("POST", "/v1/auth/otp/verify", {"phone": PHONES[index], "code": "000000"})
            if reply.status == 429:
                self.wait(retry_after(reply.headers.get("retry-after"), time.time()))
                continue
            if reply.status != 200:
                raise RuntimeError(f"Demo login failed: HTTP {reply.status}")
            token = reply.body["access_token"]
            me = self.api.request("GET", "/v1/me", token=token)
            if me.status != 200:
                raise RuntimeError(f"Demo account check failed: HTTP {me.status}")
            version = me.body["current_consent_version"]
            if me.body.get("consent_version") != version:
                accepted = self.api.request("POST", "/v1/me/consent", {"consent_version": version}, token)
                if accepted.status != 204:
                    raise RuntimeError(f"Demo consent failed: HTTP {accepted.status}")
            self.tokens[index] = token
            return token
        raise RuntimeError("Demo login remained rate-limited after five attempts")

    def send(self, index, report):
        for attempt in range(6):
            self.wait()
            token = self.tokens.get(index) or self.login(index)
            while delay := self.budget.reserve(index):
                print(f"Demo user {index + 1:02}: respecting report quota; waiting {delay:.1f}s", flush=True)
                self.wait(delay)
            try:
                reply = self.api.request("POST", "/v1/reports", report, token)
            except (urllib.error.URLError, TimeoutError, OSError):
                self.wait(min(30, 2 ** (attempt + 1)))
                continue
            self.counts[str(reply.status)] += 1
            if reply.status in (200, 201):
                if reply.body.get("report_id") != report["report_id"]:
                    raise RuntimeError("Report response did not preserve its deterministic ID")
                return reply.status
            if reply.status == 401:
                self.tokens.pop(index, None)
                continue
            if reply.status == 429:
                self.budget.block(index, retry_after(reply.headers.get("retry-after"), time.time()))
                continue
            if reply.status >= 500:
                self.wait(min(30, 2 ** (attempt + 1)))
                continue
            raise RuntimeError(f"Demo report rejected: HTTP {reply.status}; report_id={report['report_id']}")
        raise RuntimeError("Demo report did not succeed after six bounded attempts")


def load_plan(args):
    if args.plan:
        path = Path(args.plan)
    else:
        root = "/tmp/opencode" if Path("/tmp/opencode").is_dir() else None
        directory = Path(tempfile.mkdtemp(prefix="soko-bei-demo-plan-", dir=root))
        container_path = "/tmp/" + directory.name + ".json"
        with (directory / "generation.log").open("w") as log:
            subprocess.run(["docker", "exec", args.container, "/app/bin/backend", "demo-plan", "--seed", str(args.seed), "--output", container_path], check=True, timeout=180, stdout=log, stderr=subprocess.STDOUT)
        path = directory / "plan.json"
        subprocess.run(["docker", "cp", f"{args.container}:{container_path}", str(path)], check=True, timeout=30, stdout=subprocess.DEVNULL)
    plan = json.loads(path.read_text())
    if plan.get("demo") is not True or plan.get("users") != PHONES or plan.get("day") != today() or plan.get("seed") != args.seed:
        raise RuntimeError("A current-day, matching-seed dev-only demo-plan with the 40 synthetic accounts is required")
    if not plan.get("templates") or len(plan.get("reports", [])) > 1000:
        raise RuntimeError("Invalid or oversized demo API plan")
    for event in plan["reports"] + plan["templates"]:
        if not 0 <= event["user_index"] < len(PHONES):
            raise RuntimeError("Demo plan contains an invalid user index")
    print(f"Generated demo prices only: plan={path}; day={plan['day']}; today_reports={len(plan['reports'])}; users=40", flush=True)
    return plan


def main(argv=None):
    parser = argparse.ArgumentParser(description="Feed generated demo prices through the unchanged public HTTPS API")
    parser.add_argument("mode", choices=("today", "trickle"))
    parser.add_argument("--duration", type=float, help="Stop after this many seconds, including quota waits")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--plan", help="Use today's demo-plan JSON instead of generating it in the running backend")
    parser.add_argument("--container", default=os.environ.get("BACKEND_CONTAINER", "soko-backend"))
    parser.add_argument("--reports-per-hour", type=int, default=30)
    parser.add_argument("--state-dir", default=str(Path(os.environ.get("XDG_STATE_HOME", str(Path.home() / ".local/state"))) / "soko-bei/demo-feed"))
    args = parser.parse_args(argv)
    if args.duration is not None and args.duration <= 0:
        parser.error("--duration must be positive")
    if not 1 <= args.reports_per_hour <= 30:
        parser.error("--reports-per-hour must be 1..30; this tool never raises server quotas")
    os.umask(0o077)
    api = Api(os.environ.get("BASE_URL", ""))
    api.require_dev()
    key = hashlib.sha256(api.base.encode()).hexdigest()[:20]
    budget = RateBudget(Path(args.state_dir) / (key + ".json"), args.reports_per_hour)
    feed = Feed(api, budget, args.duration)
    rng = random.SystemRandom()
    signal.signal(signal.SIGTERM, stop)
    started = time.monotonic()
    skipped = 0
    try:
        plan = load_plan(args)
        if args.mode == "today":
            done = budget.completed(plan["day"])
            for position, event in enumerate(plan["reports"]):
                if today() != plan["day"]:
                    raise RuntimeError("The EAT date changed; rerun demo-today for the new day")
                report = event["report"]
                if report["report_id"] in done:
                    skipped += 1
                    continue
                feed.send(event["user_index"], report)
                budget.mark(plan["day"], report["report_id"])
                if (position + 1) % 40 == 0 or position + 1 == len(plan["reports"]):
                    print(f"Today API progress: {position + 1}/{len(plan['reports'])}; HTTP={dict(feed.counts)}; already_done={skipped}", flush=True)
                feed.wait(.2)
        else:
            count = 0
            while True:
                feed.wait()
                if today() != plan["day"]:
                    api.require_dev()
                    plan = load_plan(args)
                if count % 20 == 0:
                    api.require_dev()
                report = dict(rng.choice(plan["templates"])["report"])
                report["report_id"] = str(uuid.uuid4())
                report["reported_at_ms"] = int(time.time() * 1000)
                index = budget.choose()
                status = feed.send(index, report)
                count += 1
                print(f"Demo trickle {count}: HTTP {status}; user={index + 1:02}; {report['market_id']}/{report['commodity_id']}/{report['grade']}; report_id={report['report_id']}", flush=True)
                feed.wait(rng.uniform(20, 40))
    except (KeyboardInterrupt, DurationFinished):
        print("Demo feed stopped.", flush=True)
    finally:
        print(json.dumps({"mode": args.mode, "http": dict(feed.counts), "already_done": skipped, "elapsed_seconds": round(time.monotonic() - started, 2)}), flush=True)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, KeyError, OSError, subprocess.SubprocessError) as error:
        print(f"Demo feed failed: {error}", file=sys.stderr)
        sys.exit(1)
