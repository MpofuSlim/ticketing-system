# Ticketing cell on single-node Kubernetes (k3s)

Runs the ticketing cell on **single-node k3s**, so the box can also host other
systems (each in its own namespace). This is the k8s equivalent of the Docker
Compose stack in [`../../docker-compose.yml`](../../docker-compose.yml) — same
images, same env contract (it reuses the cell env files in `../cells/`), Eureka
service discovery, fronted by the host's nginx.

> Examples use the `zw` cell / `ticketing` namespace. The pattern generalises:
> a second cell or a different system gets its own namespace + its own
> `cell-<iso>` / `cell-<iso>-secrets`.

## Prerequisites

Single-node k3s with the built-in Traefik **and** servicelb disabled, so it
never competes with the host nginx for `80/443`:

```sh
curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="--disable traefik --disable servicelb --write-kubeconfig-mode 644" sh -
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
```

## 1. Namespace, config, secrets, image-pull

Config + secrets come straight from the cell env files (the same source of
truth as Compose). Every workload sets `envFrom` ordered **configmap → secret**,
so the secret (`cell.<iso>.local.env`) wins on any shared key — exactly like
Compose's layered `--env-file`s (e.g. the real `EUREKA_PASSWORD` overrides the
`REPLACE_ME` placeholder, and the CORS override wins over the committed default).

```sh
kubectl apply -f 00-namespace.yaml

# GHCR pull credential (a read:packages PAT)
kubectl -n ticketing create secret docker-registry ghcr \
  --docker-server=ghcr.io --docker-username=<ghcr-owner> --docker-password=<PAT>

# non-secret defaults + real secrets, from the cell env files
kubectl -n ticketing create configmap cell-zw            --from-env-file=../cells/cell.zw.env
kubectl -n ticketing create secret generic cell-zw-secrets --from-env-file=../cells/cell.zw.local.env

# Postgres init script -> creates the 6 per-service databases on first boot
kubectl -n ticketing create configmap pg-init \
  --from-file=init-databases.sql=../../docker/postgres/init-databases.sql
```

### Changing a value later

Every service reads its whole environment through
`envFrom: [configMapRef: cell-zw, secretRef: cell-zw-secrets]`, which has two
consequences worth knowing before you chase a "config didn't take" ghost:

- **A key absent from BOTH sources never reaches the pod.** There is no
  per-service default to fall back on, so a variable that only exists in
  `docker-compose.yml` or `.env.example` is simply missing on k3s. Add it to
  `deploy/cells/cell.<iso>.env` (non-secret) or `cell.<iso>.local.env` (secret).
- **`envFrom` is not live-reloaded.** Re-creating the ConfigMap does nothing to
  a running pod until it restarts.

So the round trip for a non-secret change is: edit `cell.zw.env`, then

```sh
kubectl -n ticketing create configmap cell-zw --from-env-file=../cells/cell.zw.env \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n ticketing rollout restart deployment/<service>
kubectl -n ticketing rollout status  deployment/<service>
```

> [!WARNING]
> If a value was ever set with `kubectl set env deployment/<service> KEY=…`, that
> writes an explicit `env:` entry which **wins over `envFrom`** — and a later
> `kubectl apply -f 04-services.yaml` silently deletes it, because the manifest
> has no such entry. Prefer the ConfigMap/Secret round trip above; if you must
> use `set env` for a hotfix, fold the value back into the cell env file before
> the next apply. `kubectl -n ticketing set env deployment/<service> --list` shows
> what has drifted.

## 2. Apply the workloads (bottom-up)

```sh
kubectl apply -f 01-infra.yaml        # postgres, redis (local-path PVCs)
kubectl apply -f 02-discovery.yaml    # Eureka HA pair
kubectl apply -f 03-user-service.yaml
kubectl apply -f 04-services.yaml     # event, seat, booking, payment, loyalty
kubectl apply -f 05-gateway.yaml      # api-gateway (NodePort 30080)
kubectl -n ticketing get pods
```

## 3. Edge

The gateway is a NodePort on `30080`. Point the host nginx vhost's upstream at
it and reload — this is the only cutover line (Compose used `18080`):

```
proxy_pass http://127.0.0.1:30080;
```
```sh
sudo nginx -t && sudo nginx -s reload
```

## 4. Network segmentation (optional, OWASP A05)

`deploy/k8s/optional/06-networkpolicy.yaml` locks the namespace down to
same-namespace ingress only (the api-gateway stays publicly reachable). It is
**not** applied by `kubectl apply -f .` (that's non-recursive) — apply it
deliberately and watch readiness, because health probes under a default-deny
depend on the k3s (kube-router) NetworkPolicy controller allowing node→pod
traffic:

```sh
kubectl apply -f optional/06-networkpolicy.yaml
kubectl -n ticketing get pods -w          # confirm all stay Ready
# revert instantly if any pod goes unready:
# kubectl delete -f optional/06-networkpolicy.yaml
```

## 5. Monitoring stack (Prometheus + Alertmanager)

The alert rules in `prometheus/alerts.yaml` — including the payment-integrity
and audit-tamper pages — only fire once this is running; **a cell without it
pages nobody**. `deploy/k8s/monitoring/` is a subdirectory on purpose (the
non-recursive fleet apply skips it) because its ConfigMaps/Secret must be
generated first from the `prometheus/` source of truth:

```sh
# METRICS_SCRAPE_TOKEN must match the value in cell-zw-secrets (the services
# verify it constant-time on X-Metrics-Token; see MetricsScrapeAuthFilter).
./scripts/apply-monitoring.sh
# then confirm every scrape target is UP:
kubectl -n ticketing port-forward svc/prometheus 9091:9090 &
# open http://localhost:9091/targets
```

Re-run the script after any edit to `prometheus/*.yml|yaml` — it re-renders
the ConfigMaps and restarts the stack. Alert receivers are still webhook
placeholders in `alertmanager.yml`; point them at your real Slack/PagerDuty/
email integrations per cell (gitignored override), or the routed alerts
terminate at a nonexistent `alert-sink`.

## Workload hardening (OWASP A05)

Every **application** Deployment (`02`–`05`) runs with a locked-down
`securityContext`: non-root `runAsUser: 10001`, `seccompProfile: RuntimeDefault`,
`allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true` (with a `/tmp`
`emptyDir` for the JVM's temp/hsperfdata), all Linux capabilities dropped, and
`automountServiceAccountToken: false`. Rollouts are surge-safe (the old pod keeps
serving until the new one passes its probe), so a hardening regression stalls the
rollout rather than causing downtime.

The **infra** StatefulSets (`01-infra.yaml`: postgres/redis) are left
un-hardened for now — a StatefulSet pod is replaced in place (no surge), and the
official images' root-then-drop entrypoints need per-image validation, so
hardening the data tier is a deliberately-scheduled follow-up rather than an
auto-applied change.

## Notes / gotchas

- **Service discovery**: each JVM service sets `EUREKA_INSTANCE_HOSTNAME=<svc>`
  + `EUREKA_PREFER_IP_ADDRESS=false` and has a matching `Service`, so the gateway
  resolves `lb://<svc>` → `<svc>:<port>` → pod.
- **Core banking**: there is no server-side core-banking provider. The Oradian
  integration was removed — the frontend talks to Veengu directly — so tier-2
  registration is a purely local state change and payment-service no longer
  carries wallet transfer/withdrawal endpoints. Login, MFA, browse, seat-hold,
  the InnBucks 2D-code payment and the ZimSwitch card rail all work.
- **`INNBUCKS_GATEWAY_URL`** (in `cell.zw.env`) is an inert placeholder — the
  `innbucks-core-gateway` spike it pointed at was retired (A06) and the SMS path
  moved to the authenticated notify API. Leave the default; nothing serves
  `:8088` in the cell today.
- **`TICKETS_PUBLIC_BASE_URL`** is set to the public origin
  (`https://dtx.innbucks.co.zw`); Compose left it at the `localhost:8080` default.
- Single replica per service; memory requests/limits mirror the Compose
  `mem_reservation`/`mem_limit`.
