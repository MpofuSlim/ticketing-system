#!/usr/bin/env bash
# Stand up (or update) the monitoring stack in the ticketing k3s cell.
#
# Generates the ConfigMaps + scrape-token Secret that
# deploy/k8s/monitoring/monitoring.yaml mounts — from the single source of
# truth under prometheus/ — then applies the manifest. Re-run after ANY edit
# to prometheus/*.yml|yaml; it is idempotent (create --dry-run | apply).
#
# Usage (on the EC2 box, repo checked out):
#   METRICS_SCRAPE_TOKEN=<same value as in cell-zw-secrets> \
#     ./scripts/apply-monitoring.sh
#
# METRICS_SCRAPE_TOKEN must be the SAME value the app services receive via
# the cell-zw-secrets Secret (cell.<iso>.local.env) — the services compare it
# constant-time against the X-Metrics-Token header Prometheus sends. If it's
# already provisioned in cell-zw-secrets you can omit the env var and the
# script reads it from there.
#
# Who the alert-notifier tells (stored in the alert-notifier Secret, never in
# the repo). Each is optional on a re-run: UNSET keeps what the Secret already
# holds, set to '' clears it.
#   ALERT_SMS_TO     numbers in E.164, comma-separated (+2637...): texted for
#                    page alerts. Set it on production only.
#   ALERT_EMAIL_TO   addresses, comma-separated: emailed for every alert.
#   ALERT_ENV_LABEL  the first word of every message: PROD or STAGING.
# First run on a host, e.g.:
#   ALERT_SMS_TO='+2637XXXXXXXX' ALERT_EMAIL_TO='you@example.com' \
#     ALERT_ENV_LABEL=PROD ./scripts/apply-monitoring.sh

set -euo pipefail

NS="${MONITORING_NAMESPACE:-ticketing}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

TOKEN="${METRICS_SCRAPE_TOKEN:-}"
if [[ -z "$TOKEN" ]]; then
    TOKEN=$(kubectl -n "$NS" get secret cell-zw-secrets \
        -o jsonpath='{.data.METRICS_SCRAPE_TOKEN}' 2>/dev/null | base64 -d || true)
fi
if [[ -z "$TOKEN" ]]; then
    echo "FAILED: METRICS_SCRAPE_TOKEN is not set and not present in cell-zw-secrets." >&2
    echo "Generate one (openssl rand -base64 48), add it to cell.<iso>.local.env /" >&2
    echo "cell-zw-secrets so the app services can verify it, then re-run." >&2
    exit 1
fi

# Config from the in-repo source of truth. kubectl create --dry-run | apply
# makes each idempotent AND updates in place on re-run.
kubectl -n "$NS" create configmap prometheus-config \
    --from-file=prometheus.yml="$REPO_ROOT/prometheus/prometheus.yml" \
    --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NS" create configmap prometheus-rules \
    --from-file=alerts.yaml="$REPO_ROOT/prometheus/alerts.yaml" \
    --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NS" create configmap alertmanager-config \
    --from-file=alertmanager.yml="$REPO_ROOT/prometheus/alertmanager.yml" \
    --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NS" create secret generic metrics-scrape-token \
    --from-literal=token="$TOKEN" \
    --dry-run=client -o yaml | kubectl apply -f -

# The alert-notifier: its script, and its Secret (the bearer Alertmanager
# presents, generated once and kept, plus who is told). Created BEFORE the
# manifest is applied: Alertmanager mounts the token, and a pod whose Secret
# is missing never starts.
kubectl -n "$NS" create configmap alert-notifier-script \
    --from-file=alert_notifier.py="$REPO_ROOT/prometheus/alert-notifier/alert_notifier.py" \
    --dry-run=client -o yaml | kubectl apply -f -

current() {
    kubectl -n "$NS" get secret alert-notifier -o "jsonpath={.data.$1}" 2>/dev/null \
        | base64 -d 2>/dev/null || true
}
WEBHOOK_TOKEN="$(current webhook_token)"
if [[ -z "$WEBHOOK_TOKEN" ]]; then
    WEBHOOK_TOKEN="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
fi
SMS_TO="${ALERT_SMS_TO-$(current sms_to)}"
EMAIL_TO="${ALERT_EMAIL_TO-$(current email_to)}"
ENV_LABEL="${ALERT_ENV_LABEL-$(current env_label)}"

sms_count=0
IFS=',' read -ra numbers <<< "$SMS_TO"
# ${a[@]+...}: an empty array is "unbound" to bash 4.2's set -u.
for n in ${numbers[@]+"${numbers[@]}"}; do
    n="${n// /}"
    [[ -z "$n" ]] && continue
    if [[ ! "$n" =~ ^\+[0-9]{9,15}$ ]]; then
        echo "FAILED: an ALERT_SMS_TO entry starting '${n:0:4}' is not E.164 (+ then 9 to 15 digits)." >&2
        exit 1
    fi
    sms_count=$((sms_count + 1))
done

# Files, not --from-literal, so the numbers and the token never sit in this
# host's process list.
notifier_dir="$(mktemp -d)"
trap 'rm -rf "$notifier_dir"' EXIT
chmod 700 "$notifier_dir"
printf '%s' "$WEBHOOK_TOKEN" > "$notifier_dir/webhook_token"
printf '%s' "$SMS_TO"        > "$notifier_dir/sms_to"
printf '%s' "$EMAIL_TO"      > "$notifier_dir/email_to"
printf '%s' "$ENV_LABEL"     > "$notifier_dir/env_label"
kubectl -n "$NS" create secret generic alert-notifier \
    --from-file="$notifier_dir/webhook_token" --from-file="$notifier_dir/sms_to" \
    --from-file="$notifier_dir/email_to" --from-file="$notifier_dir/env_label" \
    --dry-run=client -o yaml | kubectl apply -f -

kubectl apply -f "$REPO_ROOT/deploy/k8s/monitoring/"

# Config changes need a restart to load (no config-watch sidecar — one more
# moving part than a single-node cell wants).
kubectl -n "$NS" rollout restart deployment/prometheus deployment/alertmanager deployment/alert-notifier
kubectl -n "$NS" rollout status  deployment/prometheus
kubectl -n "$NS" rollout status  deployment/alertmanager
# Not ready = it cannot deliver (no notification API credentials, or nobody
# to tell). Say so instead of waiting for ever.
if ! kubectl -n "$NS" rollout status deployment/alert-notifier --timeout=120s; then
    echo "WARNING: alert-notifier is not ready, so alerts reach nobody. Why:" >&2
    kubectl -n "$NS" logs deployment/alert-notifier --tail=20 >&2 || true
fi
# redis-exporter (job "redis") reads REDIS_PASSWORD from cell-zw-secrets; it
# needs no restart for a Prometheus config change, only to exist.
kubectl -n "$NS" rollout status  deployment/redis-exporter
# node-clock (job "node-clock") reads the node's clock state; same: it only
# has to exist.
kubectl -n "$NS" rollout status  daemonset/node-clock

echo
echo "Done. Verify targets are UP (expect every job green, incl. the 5"
echo "token-authed services — a 401 here means METRICS_SCRAPE_TOKEN differs"
echo "between cell-zw-secrets and the metrics-scrape-token Secret):"
echo "(Exception: loans-service is DOWN on any host without the loans"
echo "Deployment, i.e. production, by design — nothing alerts on it.)"
echo "  kubectl -n $NS port-forward svc/prometheus 9091:9090 &"
echo "  open http://localhost:9091/targets"
echo
echo "Alerts go to: ${sms_count} number(s) by SMS (page alerts),"
echo "  email $([[ -n "$EMAIL_TO" ]] && echo set || echo 'not set'), label '${ENV_LABEL:-CELL}'."
echo "Send a test page (a text now, and a RESOLVED text about 5 minutes later):"
echo "  kubectl -n $NS exec deploy/alertmanager -- amtool alert add AlertPathTest \\"
echo "    severity=page job=test --annotation=summary='Test alert, please ignore' \\"
echo "    --alertmanager.url=http://localhost:9093"
