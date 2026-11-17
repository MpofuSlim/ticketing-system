#!/usr/bin/env python3
"""Alert notifier: Alertmanager webhooks in, SMS and email out.

Alertmanager can only call webhooks, and the cell's receivers pointed at a
placeholder (``alert-sink``) that never existed, so no alert ever reached a
person. This relay is that missing receiver. It sends through the InnBucks
notification API, the same gateway and ``BANK_API_*`` credentials the fleet's
own SMS and email use (``POST /auth/third-party`` for a bearer, then
``POST /api/notification/sms`` and ``/api/notification/email``).

Why its own pod and not an endpoint in a service: a service that has crashed
or run out of memory cannot tell anyone, and the alert would be about exactly
that. This pod depends on nothing in the cell but Alertmanager, and on nothing
outside it but the notification API. Standard library only, so the image is
the official python one with nothing installed.

Routes (receivers in prometheus/alertmanager.yml):

* ``POST /page``     severity=page: SMS to every ``ALERT_SMS_TO`` number,
                     and email to every ``ALERT_EMAIL_TO`` address.
* ``POST /ticket``   severity=ticket: email only.
* ``POST /default``  anything else: email only.
* ``GET  /healthz``  readiness: 200 when it can deliver, else 503 naming what
                     is missing (never a value).
* ``GET  /livez``    liveness: 200 while the process serves.

Answers to Alertmanager: 200 when at least one message was accepted by the
gateway (or there is nobody configured for that route), 401 without the right
bearer, 400/413 for a body Alertmanager would never send, 502 when nothing
could be delivered, so Alertmanager retries with backoff.

Never logged: message text, phone numbers (masked), email addresses (masked),
credentials, tokens.
"""

import base64
import hmac
import json
import logging
import os
import re
import sys
import threading
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = logging.getLogger("alert-notifier")

LOGIN_PATH = "/auth/third-party"
SMS_PATH = "/api/notification/sms"
EMAIL_PATH = "/api/notification/email"
USER_AGENT = "Ticketize-AlertNotifier/1.0"

# One GSM-7 segment. A longer message is truncated, never split: two
# segments double the cost and can arrive out of order.
SMS_MAX = 160
# The email endpoint refuses a reference longer than ~46 characters.
MAX_REFERENCE = 46
EMAIL_SUBJECT_MAX = 120
MAX_BODY_BYTES = 1024 * 1024

ROUTES = ("page", "ticket", "default")


# --------------------------------------------------------------------------
# Text: the notification API's SMS character rules
# --------------------------------------------------------------------------

_TRANSLITERATIONS = (
    ("–", "-"), ("—", "-"), ("‒", "-"), ("―", "-"),
    ("‘", "'"), ("’", "'"), ("‚", "'"), ("‛", "'"),
    # Curly double quotes become an apostrophe: the API refuses '"'.
    ("“", "'"), ("”", "'"), ("„", "'"),
    ("…", "..."), (" ", " "),
    # A bullet becomes '-', not '*': '*' is refused too.
    ("•", "-"), ("·", "."),
)
_ACCEPTED_PUNCTUATION = " .,()-%@&#'+"


def gsm_safe(text):
    """Python twin of the services' SmsTextSanitizer.toGsmSafe.

    The notification API refuses ``! : / ? " * ;`` and anything outside
    printable ASCII with 400 "Invalid message" (probed one character at a time
    on 2026-07-29). Same rules, same whitelist: what is not proven accepted
    becomes a space. Used for SMS text and email SUBJECTS (the email endpoint
    validates the subject the same way; the body accepts anything).
    """
    if not text:
        return text
    for old, new in _TRANSLITERATIONS:
        text = text.replace(old, new)
    text = "".join(ch for ch in unicodedata.normalize("NFKD", text)
                   if not unicodedata.combining(ch))
    text = (text.replace("!", ".").replace("?", ".").replace(";", ".")
            .replace(":", " ").replace("/", " ").replace("*", " ")
            .replace('"', "'"))
    out = []
    for ch in text:
        if ch in "\n\r\t" or ("A" <= ch <= "Z") or ("a" <= ch <= "z") \
                or ("0" <= ch <= "9") or ch in _ACCEPTED_PUNCTUATION:
            out.append(ch)
        else:
            out.append(" ")
    return re.sub(" {2,}", " ", "".join(out))


def one_line(text):
    """Collapse every run of whitespace (line breaks included) to one space."""
    return re.sub(r"\s+", " ", text or "").strip()


def truncate(text, limit):
    if len(text) <= limit:
        return text
    return text[: limit - 3].rstrip(" ,.-") + "..."


def mask_phone(number):
    digits = number.strip()
    return digits[:4] + "****" + digits[-3:] if len(digits) > 7 else "****"


def mask_email(address):
    local, _, domain = address.strip().partition("@")
    return (local[:1] + "****@" + domain) if domain else "****"


# --------------------------------------------------------------------------
# Composing messages from an Alertmanager webhook
# --------------------------------------------------------------------------

def _alerts(payload, status):
    return [a for a in payload.get("alerts") or []
            if isinstance(a, dict) and a.get("status") == status]


def _alert_name(payload):
    for source in (payload.get("groupLabels"), payload.get("commonLabels")):
        if isinstance(source, dict) and source.get("alertname"):
            return str(source["alertname"])
    for alert in payload.get("alerts") or []:
        name = (alert.get("labels") or {}).get("alertname")
        if name:
            return str(name)
    return "Alert"


def _summary(alert):
    annotations = alert.get("annotations") or {}
    labels = alert.get("labels") or {}
    return one_line(annotations.get("summary") or labels.get("alertname") or "alert")


def _unique(items):
    seen, out = set(), []
    for item in items:
        if item not in seen:
            seen.add(item)
            out.append(item)
    return out


def compose_sms(payload, env_label):
    """One SMS segment saying what is wrong, or that it has cleared.

    ``PROD ALERT ServiceDown - payment-service is down``
    ``PROD 2 ALERTS NodeMemoryLow - Server memory low, 12% free, ...``
    ``PROD RESOLVED ServiceDown - payment-service is down``
    """
    name = _alert_name(payload)
    firing = _alerts(payload, "firing")
    resolved = _alerts(payload, "resolved")
    if firing:
        head = "ALERT" if len(firing) == 1 else "%d ALERTS" % len(firing)
        text = "%s %s %s - %s" % (env_label, head, name,
                                  ", ".join(_unique(_summary(a) for a in firing)))
        if resolved:
            text += ", %d cleared" % len(resolved)
    else:
        text = "%s RESOLVED %s - %s" % (env_label, name,
                                        ", ".join(_unique(_summary(a) for a in resolved)) or name)
    return truncate(one_line(gsm_safe(text)), SMS_MAX)


def compose_email(payload, env_label, utc_offset_minutes):
    """Subject (gateway-safe, one line) and a plain-text body with every alert."""
    name = _alert_name(payload)
    firing = _alerts(payload, "firing")
    resolved = _alerts(payload, "resolved")
    state = "FIRING" if firing else "RESOLVED"
    count = len(firing) if firing else len(resolved)
    subject = "%s %s %s" % (env_label, state, name)
    if count > 1:
        subject += " (%d alerts)" % count
    subject = truncate(one_line(gsm_safe(subject)), EMAIL_SUBJECT_MAX)

    offset = timezone(timedelta(minutes=utc_offset_minutes))
    lines = ["%s: %s %s, %d alert%s." % (env_label, name, state.lower(), count,
                                       "" if count == 1 else "s"), ""]
    for alert in firing + resolved:
        labels = alert.get("labels") or {}
        annotations = alert.get("annotations") or {}
        lines.append("[%s] %s" % (str(alert.get("status", "")).upper(), _summary(alert)))
        if annotations.get("description"):
            lines.append("  " + one_line(annotations["description"]))
        shown = ", ".join("%s=%s" % (k, labels[k]) for k in ("job", "instance", "severity")
                          if labels.get(k))
        if shown:
            lines.append("  " + shown)
        lines.append("  Since: " + _local_time(alert.get("startsAt"), offset))
        if alert.get("status") == "resolved":
            lines.append("  Cleared: " + _local_time(alert.get("endsAt"), offset))
        if annotations.get("runbook"):
            lines.append("  Runbook: " + one_line(annotations["runbook"]))
        lines.append("")
    if firing:
        lines.append("To mute it for 2 hours, on the server:")
        lines.append("  kubectl -n ticketing exec deploy/alertmanager -- amtool silence add "
                     "alertname=%s --duration=2h --comment='looking into it' "
                     "--alertmanager.url=http://localhost:9093" % name)
    return subject, "\n".join(lines).rstrip() + "\n"


def _local_time(value, offset):
    if not value:
        return "unknown"
    try:
        stamp = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return str(value)
    if stamp.year < 2000:  # Alertmanager's zero time on a firing alert's endsAt
        return "unknown"
    return stamp.astimezone(offset).strftime("%Y-%m-%d %H:%M %z")


# --------------------------------------------------------------------------
# The notification API client
# --------------------------------------------------------------------------

class DeliveryError(Exception):
    """A send the gateway refused or could not be reached for."""


class _Unauthorized(Exception):
    pass


class _NoRedirects(urllib.request.HTTPRedirectHandler):
    """A redirect would re-send X-Api-Key and the bearer to whatever host
    Location names (the fleet's outbound rule), so a 3xx is an error."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class TokenCache:
    """The bearer from /auth/third-party, refreshed 30s before it expires.

    A caller holding a token that is still valid never waits for a login. Only
    a caller with no usable token waits, and at most one login runs at a time
    (the login was measured at ~19s for the services)."""

    def __init__(self, login, clock=time.time):
        self._login = login
        self._clock = clock
        self._lock = threading.Lock()
        self._cached = None  # (token, refresh_at, expires_at)

    def get(self):
        cached, now = self._cached, self._clock()
        if cached and now < cached[1]:
            return cached[0]
        if cached and now < cached[2]:
            if not self._lock.acquire(blocking=False):
                return cached[0]  # someone else is refreshing; this one still works
        else:
            self._lock.acquire()
        try:
            cached, now = self._cached, self._clock()
            if cached and now < cached[1]:
                return cached[0]
            token, expires_at = self._login()
            self._cached = (token, expires_at - 30, expires_at)
            return token
        finally:
            self._lock.release()

    def refresh_after_rejection(self, refused):
        """After a 401: log in again only if the refused token is still the
        cached one, so N concurrent 401s cost one login."""
        with self._lock:
            if self._cached and self._cached[0] == refused:
                self._cached = None
        return self.get()


class NotificationApi:
    def __init__(self, base_url, api_key, username, password,
                 login_timeout=35.0, send_timeout=20.0, fallback_ttl=480, clock=time.time):
        self.base_url = (base_url or "").rstrip("/")
        self.api_key = api_key or ""
        self.username = username or ""
        self.password = password or ""
        self.login_timeout = login_timeout
        self.send_timeout = send_timeout
        self.fallback_ttl = fallback_ttl
        self._clock = clock
        self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirects())
        self.tokens = TokenCache(self._login, clock=clock)

    def configured(self):
        return all((self.base_url, self.api_key, self.username, self.password))

    def send_sms(self, to, message, reference):
        self._authed(SMS_PATH, {"message": message, "reference": reference,
                                "destinationMsisdn": to})

    def send_email(self, to, subject, message, reference):
        self._authed(EMAIL_PATH, {"subject": subject, "message": message,
                                  "reference": reference, "destinationEmail": to})

    def _authed(self, path, body):
        token = self.tokens.get()
        try:
            self._post(path, body, token)
        except _Unauthorized:
            LOG.info("Notification API answered 401; logging in again and replaying once")
            try:
                self._post(path, body, self.tokens.refresh_after_rejection(token))
            except _Unauthorized:
                raise DeliveryError("HTTP 401 after a fresh login")

    def _login(self):
        raw = self._request(LOGIN_PATH, {"username": self.username, "password": self.password},
                            None, self.login_timeout)
        try:
            token = json.loads(raw or b"{}").get("accessToken")
        except (ValueError, AttributeError):
            raise DeliveryError("login answered something that is not JSON")
        if not token:
            raise DeliveryError("login returned no accessToken")
        LOG.info("Notification API login succeeded")
        return token, self._expiry(token)

    def _expiry(self, token):
        try:
            part = token.split(".")[1]
            claims = json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))
            if isinstance(claims.get("exp"), (int, float)):
                return float(claims["exp"])
        except (IndexError, ValueError, TypeError, AttributeError):
            pass
        return self._clock() + self.fallback_ttl

    def _post(self, path, body, token):
        self._request(path, body, token, self.send_timeout)

    def _request(self, path, body, token, timeout):
        request = urllib.request.Request(
            self.base_url + path, data=json.dumps(body).encode("utf-8"), method="POST")
        request.add_header("Content-Type", "application/json")
        request.add_header("Accept", "application/json")
        request.add_header("User-Agent", USER_AGENT)
        request.add_header("X-Api-Key", self.api_key)
        if token is not None:
            request.add_header("Authorization", "Bearer " + token)
        try:
            with self._opener.open(request, timeout=timeout) as response:
                return response.read()
        except urllib.error.HTTPError as e:
            if e.code == 401 and token is not None:
                raise _Unauthorized()
            raise DeliveryError("HTTP %d" % e.code)
        except (urllib.error.URLError, OSError) as e:
            reason = getattr(e, "reason", e)
            raise DeliveryError("unreachable (%s)" % type(reason).__name__)


# --------------------------------------------------------------------------
# The notifier: who gets what
# --------------------------------------------------------------------------

def _split(value):
    return [item.strip() for item in (value or "").split(",") if item.strip()]


class Notifier:
    def __init__(self, api, webhook_token, sms_to, email_to, env_label="CELL",
                 utc_offset_minutes=120):
        self.api = api
        self.webhook_token = webhook_token or ""
        self.sms_to = _split(sms_to)
        self.email_to = _split(email_to)
        self.env_label = one_line(gsm_safe(env_label or "CELL")) or "CELL"
        self.utc_offset_minutes = utc_offset_minutes

    @classmethod
    def from_env(cls, env=os.environ):
        api = NotificationApi(
            env.get("NOTIFY_BASE_URL") or env.get("NOTIFY_BASE_URL_FALLBACK"),
            env.get("NOTIFY_API_KEY"), env.get("NOTIFY_USERNAME"), env.get("NOTIFY_PASSWORD"))
        return cls(api, env.get("WEBHOOK_TOKEN"), env.get("ALERT_SMS_TO"),
                   env.get("ALERT_EMAIL_TO"), env.get("ALERT_ENV_LABEL") or "CELL",
                   int(env.get("ALERT_UTC_OFFSET_MINUTES") or "120"))

    def problems(self):
        """What stops delivery, by name only (served on /healthz)."""
        missing = []
        if not self.api.configured():
            missing.append("notification API credentials (BANK_API_URL/KEY/USERNAME/PASSWORD)")
        if not self.webhook_token:
            missing.append("webhook token")
        if not self.sms_to and not self.email_to:
            missing.append("recipients (ALERT_SMS_TO or ALERT_EMAIL_TO)")
        return missing

    def authorized(self, header):
        if not self.webhook_token or not header or not header.startswith("Bearer "):
            return False
        return hmac.compare_digest(header[len("Bearer "):].encode("utf-8"),
                                   self.webhook_token.encode("utf-8"))

    def deliver(self, route, payload):
        """Send everything this route owes; returns (http status, reason)."""
        if not self.api.configured():
            LOG.error("Cannot deliver %s: notification API credentials are missing", route)
            return 503, "notification API not configured"
        sms_to = self.sms_to if route == "page" else []
        if not sms_to and not self.email_to:
            LOG.warning("Nobody is configured for %s alerts (%s); dropped",
                        route, _alert_name(payload))
            return 200, "no recipients for this route"

        name = _alert_name(payload)
        sent, failed = 0, 0
        if sms_to:
            text = compose_sms(payload, self.env_label)
            for number in sms_to:
                reference = "TKT-ALERT-" + uuid.uuid4().hex[:20]
                try:
                    self.api.send_sms(number, text, reference)
                    sent += 1
                    LOG.info("Alert SMS accepted alert=%s to=%s ref=%s",
                             name, mask_phone(number), reference)
                except DeliveryError as e:
                    failed += 1
                    LOG.warning("Alert SMS failed alert=%s to=%s ref=%s error=%s",
                                name, mask_phone(number), reference, e)
        if self.email_to:
            subject, body = compose_email(payload, self.env_label, self.utc_offset_minutes)
            for address in self.email_to:
                reference = "TKT-ALERT-" + uuid.uuid4().hex[:20]
                try:
                    self.api.send_email(address, subject, body, reference)
                    sent += 1
                    LOG.info("Alert email accepted alert=%s to=%s ref=%s",
                             name, mask_email(address), reference)
                except DeliveryError as e:
                    failed += 1
                    LOG.warning("Alert email failed alert=%s to=%s ref=%s error=%s",
                                name, mask_email(address), reference, e)
        if sent:
            return 200, "sent %d, failed %d" % (sent, failed)
        LOG.error("Alert %s reached nobody (%d attempts failed); Alertmanager will retry",
                  name, failed)
        return 502, "nothing delivered"


# --------------------------------------------------------------------------
# HTTP
# --------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    server_version = "alert-notifier"
    sys_version = ""

    def do_GET(self):
        notifier = self.server.notifier
        if self.path == "/livez":
            return self._answer(200, "ok")
        if self.path == "/healthz":
            problems = notifier.problems()
            return self._answer(503, "missing: " + "; ".join(problems)) if problems \
                else self._answer(200, "ok")
        return self._answer(404, "not found")

    def do_POST(self):
        notifier = self.server.notifier
        route = urllib.parse.urlsplit(self.path).path.strip("/")
        if route not in ROUTES:
            return self._answer(404, "not found")
        if not notifier.authorized(self.headers.get("Authorization")):
            LOG.warning("Refused a webhook without the right bearer from %s", self.client_address[0])
            return self._answer(401, "unauthorized")
        try:
            length = int(self.headers.get("Content-Length") or "0")
        except ValueError:
            return self._answer(400, "bad length")
        if length <= 0:
            return self._answer(400, "empty body")
        if length > MAX_BODY_BYTES:
            return self._answer(413, "too large")
        try:
            payload = json.loads(self.rfile.read(length))
        except ValueError:
            return self._answer(400, "not JSON")
        if not isinstance(payload, dict) or not isinstance(payload.get("alerts"), list):
            return self._answer(400, "not an Alertmanager webhook")
        status, reason = notifier.deliver(route, payload)
        return self._answer(status, reason)

    def _answer(self, status, text):
        body = (text + "\n").encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        pass  # one line per delivery is logged by the notifier; probes stay quiet


def make_server(notifier, host="0.0.0.0", port=9099):
    server = ThreadingHTTPServer((host, port), Handler)
    server.daemon_threads = True
    server.notifier = notifier
    return server


def main():
    logging.Formatter.converter = time.gmtime
    logging.basicConfig(stream=sys.stdout, level=logging.INFO,
                        format="%(asctime)sZ %(levelname)s %(name)s %(message)s")
    notifier = Notifier.from_env()
    host = urllib.parse.urlsplit(notifier.api.base_url).hostname or "(none)"
    LOG.info("Starting: label=%s sms=%s email=%s gateway=%s",
             notifier.env_label, [mask_phone(n) for n in notifier.sms_to] or "none",
             [mask_email(a) for a in notifier.email_to] or "none", host)
    for problem in notifier.problems():
        LOG.error("NOT READY, nobody will be told about alerts: missing %s", problem)
    make_server(notifier, port=int(os.environ.get("PORT") or "9099")).serve_forever()


if __name__ == "__main__":
    main()
