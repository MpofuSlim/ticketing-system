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

kubectl apply -f "$REPO_ROOT/deploy/k8s/monitoring/"

# Config changes need a restart to load (no config-watch sidecar — one more
# moving part than a single-node cell wants).
kubectl -n "$NS" rollout restart deployment/prometheus deployment/alertmanager
kubectl -n "$NS" rollout status  deployment/prometheus
kubectl -n "$NS" rollout status  deployment/alertmanager

echo
echo "Done. Verify targets are UP (expect every job green, incl. the 5"
echo "token-authed services — a 401 here means METRICS_SCRAPE_TOKEN differs"
echo "between cell-zw-secrets and the metrics-scrape-token Secret):"
echo "  kubectl -n $NS port-forward svc/prometheus 9091:9090 &"
echo "  open http://localhost:9091/targets"
