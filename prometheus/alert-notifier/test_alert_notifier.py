"""Tests for alert_notifier: the text rules, the routing, and the wire contract
with the InnBucks notification API, pinned against a stand-in gateway (the
WireMock contract-test rule from CLAUDE.md, in the standard library).

Run: python3 -m unittest discover -s prometheus/alert-notifier -v
"""

import base64
import json
import logging
import socket
import threading
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import alert_notifier as an

TOKEN = "hook-token-123"

# The relay's own log lines are asserted nowhere; keep the test output readable.
logging.disable(logging.CRITICAL)


def _jwt(exp):
    def part(obj):
        return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()
    return "%s.%s.sig" % (part({"alg": "none"}), part({"exp": exp}))


def webhook(status="firing", alerts=None, alertname="ServiceDown"):
    alerts = alerts if alerts is not None else [{
        "status": status,
        "labels": {"alertname": alertname, "job": "payment-service",
                   "instance": "payment-service:8085", "severity": "page"},
        "annotations": {"summary": "payment-service is down",
                        "description": "Prometheus has been unable to scrape payment-service for 2 minutes.",
                        "runbook": "prometheus/slo.md#servicedown"},
        "startsAt": "2026-11-17T12:00:00Z",
        "endsAt": "2026-11-17T12:30:00Z" if status == "resolved" else "0001-01-01T00:00:00Z",
    }]
    return {"version": "4", "status": status, "receiver": "pager",
            "groupLabels": {"alertname": alertname, "job": "payment-service"},
            "commonLabels": {"alertname": alertname}, "alerts": alerts}


# ---------------------------------------------------------------------------
# A stand-in notification API
# ---------------------------------------------------------------------------

class Gateway:
    """Records every request; answers per path from a queue or a default."""

    def __init__(self):
        self.requests = []
        self.responses = {}  # path -> list of (status, body, headers)
        self.login_token = _jwt(4102444800)  # year 2100
        gateway = self

        class H(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(length) or b"{}")
                gateway.requests.append({"path": self.path, "headers": dict(self.headers),
                                         "body": body})
                queue = gateway.responses.get(self.path)
                if queue:
                    status, payload, headers = queue.pop(0)
                elif self.path == an.LOGIN_PATH:
                    status, payload, headers = 200, {"accessToken": gateway.login_token}, {}
                else:
                    status, payload, headers = 200, {"status": "ok"}, {}
                data = json.dumps(payload).encode()
                self.send_response(status)
                for k, v in headers.items():
                    self.send_header(k, v)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.url = "http://127.0.0.1:%d" % self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def on(self, path, status, payload=None, headers=None):
        self.responses.setdefault(path, []).append((status, payload or {}, headers or {}))

    def paths(self):
        return [r["path"] for r in self.requests]

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def closed_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


# ---------------------------------------------------------------------------
# Text rules
# ---------------------------------------------------------------------------

class GsmSafeTest(unittest.TestCase):

    def test_refused_characters_never_reach_the_gateway(self):
        self.assertEqual(an.gsm_safe('Down! Why? a;b c:d e/f *g "h"'),
                         "Down. Why. a.b c d e f g 'h'")

    def test_typography_and_accents_become_ascii(self):
        self.assertEqual(an.gsm_safe("café — “ok”…"), "cafe - 'ok'...")

    def test_anything_unproven_becomes_a_space(self):
        self.assertEqual(an.gsm_safe("up \U0001F525 down [x] {y} $5 ~z"), "up down x y 5 z")

    def test_percent_and_brackets_that_are_accepted_survive(self):
        self.assertEqual(an.gsm_safe("12% free (node-1) @ & # ' +"), "12% free (node-1) @ & # ' +")


class ComposeSmsTest(unittest.TestCase):

    def test_one_firing_alert(self):
        self.assertEqual(an.compose_sms(webhook(), "PROD"),
                         "PROD ALERT ServiceDown - payment-service is down")

    def test_resolved(self):
        self.assertEqual(an.compose_sms(webhook("resolved"), "PROD"),
                         "PROD RESOLVED ServiceDown - payment-service is down")

    def test_several_alerts_are_counted_and_listed_once(self):
        alerts = [{"status": "firing", "labels": {"alertname": "ServiceDown"},
                   "annotations": {"summary": s}}
                  for s in ("seat-service is down", "user-service is down", "user-service is down")]
        self.assertEqual(an.compose_sms(webhook(alerts=alerts), "PROD"),
                         "PROD 3 ALERTS ServiceDown - seat-service is down, user-service is down")

    def test_cleared_ones_are_counted_beside_the_firing_ones(self):
        alerts = [{"status": "firing", "annotations": {"summary": "a is down"}},
                  {"status": "resolved", "annotations": {"summary": "b is down"}}]
        self.assertEqual(an.compose_sms(webhook(alerts=alerts), "PROD"),
                         "PROD ALERT ServiceDown - a is down, 1 cleared")

    def test_one_segment_at_most_and_safe_to_send(self):
        alerts = [{"status": "firing", "annotations": {"summary": "service number %d: is down!" % i}}
                  for i in range(20)]
        text = an.compose_sms(webhook(alerts=alerts), "PROD")
        self.assertLessEqual(len(text), an.SMS_MAX)
        self.assertTrue(text.endswith("..."))
        self.assertEqual(an.gsm_safe(text), text)

    def test_line_breaks_in_a_summary_become_spaces(self):
        alerts = [{"status": "firing", "annotations": {"summary": "line one\nline two"}}]
        self.assertEqual(an.compose_sms(webhook(alerts=alerts), "PROD"),
                         "PROD ALERT ServiceDown - line one line two")


class ComposeEmailTest(unittest.TestCase):

    def test_subject_is_gateway_safe_and_body_has_the_detail(self):
        subject, body = an.compose_email(webhook(), "PROD", 120)
        self.assertEqual(subject, "PROD FIRING ServiceDown")
        self.assertIn("[FIRING] payment-service is down", body)
        self.assertIn("unable to scrape payment-service", body)
        self.assertIn("job=payment-service, instance=payment-service:8085, severity=page", body)
        self.assertIn("Since: 2026-11-17 14:00 +0200", body)
        self.assertIn("Runbook: prometheus/slo.md#servicedown", body)
        self.assertIn("amtool silence add alertname=ServiceDown", body)

    def test_alertmanagers_nanosecond_times_are_read(self):
        payload = webhook()
        payload["alerts"][0]["startsAt"] = "2026-11-17T12:00:00.123456789Z"
        _, body = an.compose_email(payload, "PROD", 120)
        self.assertIn("Since: 2026-11-17 14:00 +0200", body)

    def test_resolved_shows_when_it_cleared_and_no_mute_hint(self):
        subject, body = an.compose_email(webhook("resolved"), "STAGING", 120)
        self.assertEqual(subject, "STAGING RESOLVED ServiceDown")
        self.assertIn("Cleared: 2026-11-17 14:30 +0200", body)
        self.assertNotIn("amtool", body)


class TokenCacheTest(unittest.TestCase):

    def test_a_valid_token_is_reused(self):
        logins = []
        cache = an.TokenCache(lambda: (logins.append(1) or "t1", 1000.0), clock=lambda: 0.0)
        self.assertEqual(cache.get(), "t1")
        self.assertEqual(cache.get(), "t1")
        self.assertEqual(len(logins), 1)

    def test_n_rejections_of_one_token_cost_one_login(self):
        tokens = iter(["t1", "t2", "t3"])
        cache = an.TokenCache(lambda: (next(tokens), 1000.0), clock=lambda: 0.0)
        first = cache.get()
        self.assertEqual(cache.refresh_after_rejection(first), "t2")
        self.assertEqual(cache.refresh_after_rejection(first), "t2")

    def test_inside_the_refresh_window_a_caller_keeps_the_current_token_while_another_refreshes(self):
        now = [0.0]
        cache = an.TokenCache(lambda: ("t1", 100.0), clock=lambda: now[0])
        cache.get()
        now[0] = 80.0  # past refresh_at (70), before expires_at (100)
        cache._lock.acquire()  # someone else is logging in
        try:
            self.assertEqual(cache.get(), "t1")
        finally:
            cache._lock.release()


# ---------------------------------------------------------------------------
# End to end: Alertmanager -> notifier -> gateway
# ---------------------------------------------------------------------------

class RelayTest(unittest.TestCase):

    def setUp(self):
        self.gateway = Gateway()
        self.notifier = self.make_notifier(self.gateway.url)
        self.server = an.make_server(self.notifier, host="127.0.0.1", port=0)
        self.url = "http://127.0.0.1:%d" % self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.gateway.close()

    def make_notifier(self, base_url, sms="+263771000001,+263771000002",
                      email="ops@example.com", api_key="key-1"):
        api = an.NotificationApi(base_url, api_key, "notify-user", "notify-pass",
                                 login_timeout=3, send_timeout=3)
        return an.Notifier(api, TOKEN, sms, email, env_label="PROD")

    def post(self, route, payload, token=TOKEN, raw=None):
        data = raw if raw is not None else json.dumps(payload).encode()
        req = urllib.request.Request(self.url + "/" + route, data=data, method="POST")
        req.add_header("Content-Type", "application/json")
        if token is not None:
            req.add_header("Authorization", "Bearer " + token)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        try:
            with opener.open(req, timeout=10) as r:
                return r.status
        except urllib.error.HTTPError as e:
            return e.code

    def get(self, path):
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        try:
            with opener.open(self.url + path, timeout=10) as r:
                return r.status, r.read().decode()
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode()

    # -- the wire contract --------------------------------------------------

    def test_page_logs_in_then_texts_every_number_and_emails_every_address(self):
        self.assertEqual(self.post("page", webhook()), 200)
        self.assertEqual(self.gateway.paths(), [an.LOGIN_PATH, an.SMS_PATH, an.SMS_PATH, an.EMAIL_PATH])

        login = self.gateway.requests[0]
        self.assertEqual(login["body"], {"username": "notify-user", "password": "notify-pass"})
        self.assertEqual(login["headers"]["X-Api-Key"], "key-1")
        self.assertNotIn("Authorization", login["headers"])

        sms = self.gateway.requests[1]
        self.assertEqual(sms["headers"]["X-Api-Key"], "key-1")
        self.assertEqual(sms["headers"]["Authorization"], "Bearer " + self.gateway.login_token)
        self.assertEqual(sms["headers"]["User-Agent"], an.USER_AGENT)
        self.assertEqual(set(sms["body"]), {"message", "reference", "destinationMsisdn"})
        self.assertEqual(sms["body"]["message"], "PROD ALERT ServiceDown - payment-service is down")
        self.assertEqual(sms["body"]["destinationMsisdn"], "+263771000001")
        self.assertEqual(self.gateway.requests[2]["body"]["destinationMsisdn"], "+263771000002")
        self.assertTrue(sms["body"]["reference"].startswith("TKT-ALERT-"))
        self.assertLessEqual(len(sms["body"]["reference"]), an.MAX_REFERENCE)

        email = self.gateway.requests[3]
        self.assertEqual(set(email["body"]), {"subject", "message", "reference", "destinationEmail"})
        self.assertEqual(email["body"]["destinationEmail"], "ops@example.com")
        self.assertEqual(email["body"]["subject"], "PROD FIRING ServiceDown")
        self.assertLessEqual(len(email["body"]["reference"]), an.MAX_REFERENCE)

    def test_ticket_and_default_alerts_are_emailed_never_texted(self):
        self.assertEqual(self.post("ticket", webhook()), 200)
        self.assertEqual(self.post("default", webhook()), 200)
        self.assertNotIn(an.SMS_PATH, self.gateway.paths())
        self.assertEqual(self.gateway.paths().count(an.EMAIL_PATH), 2)

    def test_the_token_is_reused_across_webhooks(self):
        self.post("page", webhook())
        self.post("page", webhook("resolved"))
        self.assertEqual(self.gateway.paths().count(an.LOGIN_PATH), 1)

    def test_a_401_logs_in_again_and_replays_once(self):
        self.gateway.on(an.SMS_PATH, 401)
        self.assertEqual(self.post("page", webhook()), 200)
        self.assertEqual(self.gateway.paths(),
                         [an.LOGIN_PATH, an.SMS_PATH, an.LOGIN_PATH, an.SMS_PATH, an.SMS_PATH,
                          an.EMAIL_PATH])

    # -- what Alertmanager is told ------------------------------------------

    def test_nothing_delivered_is_a_502_so_alertmanager_retries(self):
        for _ in range(2):
            self.gateway.on(an.SMS_PATH, 400, {"errors": ["Invalid message"]})
        self.gateway.on(an.EMAIL_PATH, 500)
        self.assertEqual(self.post("page", webhook()), 502)

    def test_one_message_through_is_a_200(self):
        self.gateway.on(an.SMS_PATH, 400)
        self.gateway.on(an.EMAIL_PATH, 500)
        self.assertEqual(self.post("page", webhook()), 200)  # the second SMS went through

    def test_an_unreachable_gateway_is_a_502(self):
        self.notifier.api = self.make_notifier("http://127.0.0.1:%d" % closed_port()).api
        self.assertEqual(self.post("page", webhook()), 502)

    def test_a_redirect_is_never_followed(self):
        self.gateway.on(an.SMS_PATH, 302, headers={"Location": self.gateway.url + "/elsewhere"})
        self.gateway.on(an.SMS_PATH, 302, headers={"Location": self.gateway.url + "/elsewhere"})
        self.gateway.on(an.EMAIL_PATH, 302, headers={"Location": self.gateway.url + "/elsewhere"})
        self.assertEqual(self.post("page", webhook()), 502)
        self.assertNotIn("/elsewhere", self.gateway.paths())

    # -- guard rails: nothing reaches the gateway ----------------------------

    def test_a_missing_or_wrong_bearer_is_refused_and_nothing_is_sent(self):
        self.assertEqual(self.post("page", webhook(), token=None), 401)
        self.assertEqual(self.post("page", webhook(), token="wrong"), 401)
        self.assertEqual(self.gateway.requests, [])

    def test_bodies_alertmanager_never_sends_are_refused(self):
        self.assertEqual(self.post("page", None, raw=b"not json"), 400)
        self.assertEqual(self.post("page", {"status": "firing"}), 400)
        self.assertEqual(self.post("page", None, raw=b"x" * (an.MAX_BODY_BYTES + 1)), 413)
        self.assertEqual(self.post("elsewhere", webhook()), 404)
        self.assertEqual(self.gateway.requests, [])

    def test_a_route_with_nobody_configured_drops_without_calling_the_gateway(self):
        self.notifier.email_to = []
        self.assertEqual(self.post("ticket", webhook()), 200)
        self.assertEqual(self.gateway.requests, [])

    def test_without_gateway_credentials_it_answers_503_and_is_not_ready(self):
        self.notifier.api = self.make_notifier(self.gateway.url, api_key="").api
        self.assertEqual(self.post("page", webhook()), 503)
        self.assertEqual(self.gateway.requests, [])
        status, text = self.get("/healthz")
        self.assertEqual(status, 503)
        self.assertIn("notification API credentials", text)
        self.assertNotIn("notify-pass", text)
        self.assertEqual(self.get("/livez")[0], 200)

    def test_ready_when_configured(self):
        self.assertEqual(self.get("/healthz"), (200, "ok\n"))

    def test_not_ready_without_any_recipient(self):
        self.notifier.sms_to, self.notifier.email_to = [], []
        status, text = self.get("/healthz")
        self.assertEqual(status, 503)
        self.assertIn("recipients", text)


class FromEnvTest(unittest.TestCase):

    def test_the_secret_url_wins_over_the_configmap_one(self):
        n = an.Notifier.from_env({"NOTIFY_BASE_URL": "https://a.example", "NOTIFY_BASE_URL_FALLBACK":
                                  "https://b.example", "ALERT_SMS_TO": " +263771000001 , ",
                                  "ALERT_ENV_LABEL": "PROD: main"})
        self.assertEqual(n.api.base_url, "https://a.example")
        self.assertEqual(n.sms_to, ["+263771000001"])
        self.assertEqual(n.env_label, "PROD main")
        n = an.Notifier.from_env({"NOTIFY_BASE_URL_FALLBACK": "https://b.example/"})
        self.assertEqual(n.api.base_url, "https://b.example")
        self.assertEqual(n.env_label, "CELL")


if __name__ == "__main__":
    unittest.main()
