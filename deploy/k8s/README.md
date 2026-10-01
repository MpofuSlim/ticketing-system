# Ticketing cell on single-node Kubernetes (k3s)

Runs the ticketing cell on **single-node k3s**, so the box can also host other
systems (each in its own namespace). This is the k8s equivalent of the Docker
Compose stack in [`../../docker-compose.yml`](../../docker-compose.yml) — same
images, same env contract (it reuses the cell env files in `../cells/`),
services found by their Kubernetes Service names, fronted by the host's nginx.

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
Compose's layered `--env-file`s (e.g. the real `REDIS_PASSWORD` overrides the
`REPLACE_ME` placeholder, and the CORS override wins over the committed default).

```sh
kubectl apply -f 00-namespace.yaml

# GHCR pull credential (a read:packages PAT)
kubectl -n ticketing create secret docker-registry ghcr \
  --docker-server=ghcr.io --docker-username=<ghcr-owner> --docker-password=<PAT>

# non-secret defaults + real secrets, from the cell env files
kubectl -n ticketing create configmap cell-zw            --from-env-file=../cells/cell.zw.env
kubectl -n ticketing create secret generic cell-zw-secrets --from-env-file=../cells/cell.zw.local.env

# Postgres init script -> creates one database per service on first boot
kubectl -n ticketing create configmap pg-init \
  --from-file=init-databases.sql=../../docker/postgres/init-databases.sql
```

### Changing a value later

Every service reads its whole environment through
`envFrom: [configMapRef: cell-zw, secretRef: cell-zw-secrets]` (loans-service
excepted, deliberately — §6), which has two consequences worth knowing before
you chase a "config didn't take" ghost:

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
kubectl apply -f 03-user-service.yaml
kubectl apply -f 04-services.yaml     # event, seat, booking, payment, loyalty, marketplace
                                      # (+ the loans Service only; its pod is §6)
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

## 6. loans-service (staging only, opt-in)

The lending API (`MpofuSlim/innbucks-loans`, image `ghcr.io/mpofuslim/loans-api`)
runs behind the gateway at `/lending/**`, with its spec in the aggregated
Swagger as `loans-service`. Its **Service** is in `04-services.yaml` and exists
on every host; its **Deployment** is `loans/loans-service.yaml`, which the
routine non-recursive apply never touches. Only the staging box applies it —
production gets it through a separate go-live decision, not a routine upgrade.
Keep this repo and innbucks-loans in lock-step per `docs/fleet-wiring.md` in
the innbucks-loans repo.

On a host without the Deployment (production, today) the Service has no
endpoints, which is expected there, not an incident: every `/lending/**` call
is a **500** from the gateway, which logs an ERROR with a `ConnectException` for
each one, and the Swagger dropdown still lists `loans-service`, which fails to
load when picked.

Three things differ from every other Deployment, each on purpose (the
manifest's header has the full reasoning):

- **No `envFrom` of `cell-zw` / `cell-zw-secrets`.** Loans still signs its own
  tokens, and Spring binds env vars onto properties by name, so the cell's
  `JWT_SECRET`, `SPRING_PROFILES_ACTIVE` and `BOOTSTRAP_ADMIN_PASSWORD` would land
  on loans' own settings. It reads only its Secret `loans-service-secrets` plus a
  few cell keys named one by one. That Secret holds **only the keys
  `loans.example.env` lists**: an explicit `env:` entry beats it for the same
  key only, so a `SPRING_*`, `SERVER_*`, `DB_*` or `JAVA_*` line there could
  still repoint the database or switch the jobs on.
- **`SPRING_PROFILES_ACTIVE=api` only.** The scheduled jobs (Ndasenda
  lodgement, InnBucks booking — which pays — and the rest) stay off.
- **`strategy: Recreate`, one replica.** Never two loans pods, even mid-rollout.

**Before you start:** the merge commit's Release `Build, scan, push (api-gateway)`
job is green, and an innbucks-loans Release run is green for a commit with the
fleet-member change (health endpoint, stdout-only logging, `DB_*` /
`SERVER_PORT` placeholders) — an older image never becomes Ready here. The cell
runs **pinned** images (`CLAUDE.md`, "Deploying"); loans' Release tags its
images `sha-<full commit SHA>`, like this repo.
From the repo root on the box:

```sh
cd ~/ticketing-system && git pull
LOANS_SHA=<full 40-char innbucks-loans commit whose Release is green>
GW_SHA=<full 40-char ticketing-system merge commit>

# 0. Can the cell's `ghcr` pull secret read that loans image? (A tag the PAT
#    cannot see is ImagePullBackOff later, not an error now.)
kubectl -n ticketing run loans-pulltest --image="ghcr.io/mpofuslim/loans-api:sha-${LOANS_SHA}" \
  --restart=Never --overrides='{"spec":{"imagePullSecrets":[{"name":"ghcr"}]}}' --command -- true
kubectl -n ticketing wait pod/loans-pulltest --for=jsonpath='{.status.phase}'=Succeeded --timeout=180s
#   succeeded = pulled; a timeout with ErrImagePull in `kubectl describe` = it cannot
kubectl -n ticketing delete pod loans-pulltest

# 1. Its database on the cell postgres. init-databases.sql only runs on a new
#    volume, so a running cell needs it created by hand; this form is a no-op
#    when it already exists. Then refresh pg-init so a rebuilt volume has it.
PGPOD=$(kubectl -n ticketing get pod -l app=postgres -o jsonpath='{.items[0].metadata.name}')
echo "SELECT 'CREATE DATABASE loans_service' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'loans_service')\gexec" \
  | kubectl -n ticketing exec -i "$PGPOD" -- psql -U postgres
kubectl -n ticketing exec "$PGPOD" -- psql -U postgres -c "\l" | grep loans_service
kubectl -n ticketing create configmap pg-init \
  --from-file=init-databases.sql=docker/postgres/init-databases.sql --dry-run=client -o yaml | kubectl apply -f -

# 2. Its Secret, from a host-only file (deploy/cells/*.local.env is gitignored).
#    First time: copy the template, set JWT_SECRET (`openssl rand -base64 48` —
#    never the fleet's) and BOOTSTRAP_ADMIN_PASSWORD, uncomment what else this
#    host needs. After a later edit, re-run the create | apply, then
#    `kubectl -n ticketing rollout restart deployment/loans-service` (envFrom is
#    not live-reloaded; the restart keeps the pinned image).
[ -f deploy/cells/loans.zw.local.env ] || cp deploy/cells/loans.example.env deploy/cells/loans.zw.local.env
chmod 600 deploy/cells/loans.zw.local.env
"${EDITOR:-vi}" deploy/cells/loans.zw.local.env
kubectl -n ticketing create secret generic loans-service-secrets \
  --from-env-file=deploy/cells/loans.zw.local.env --dry-run=client -o yaml | kubectl apply -f -

# 3. The loans Service ALONE. Never a plain `kubectl apply -f deploy/k8s/` (or of
#    04-services.yaml) for this: it would un-pin every other Deployment back to
#    :latest. The label selects just this one object.
kubectl apply -f deploy/k8s/04-services.yaml -l app=loans-service

# 4. The Deployment, pinned from its first boot (the manifest says :latest like
#    every other one; a plain apply of it would un-pin loans the same way).
sed "s|ghcr.io/mpofuslim/loans-api:latest|ghcr.io/mpofuslim/loans-api:sha-${LOANS_SHA}|" \
  deploy/k8s/loans/loans-service.yaml | kubectl apply -f -
kubectl -n ticketing rollout status deployment/loans-service --timeout=10m

# 5. Only if a browser app (the loans portal) will call /foundry/lending/**:
#    allow its origin at the gateway, now loans' only CORS authority. Set
#    CORS_ALLOWED_ORIGINS in THIS host's cell.zw.local.env — never the shared
#    cell.zw.env, which production runs too — as the WHOLE list, the origins
#    cell.zw.env already has included: the Secret's value replaces that one.
#    Step 6's rollout picks it up; every other pod on its next restart.
"${EDITOR:-vi}" deploy/cells/cell.zw.local.env
kubectl -n ticketing create secret generic cell-zw-secrets \
  --from-env-file=deploy/cells/cell.zw.local.env --dry-run=client -o yaml | kubectl apply -f -

# 6. The gateway: its new image carries the /lending/** route, the docs proxy,
#    the dropdown entry and the discovery-map line. The other five services only
#    gained the map line and none of them calls loans, so they need nothing now
#    (they carry it from their next deploy).
kubectl -n ticketing set image deployment/api-gateway "*=ghcr.io/mpofuslim/api-gateway:sha-${GW_SHA}"
kubectl -n ticketing rollout status deployment/api-gateway
```

**Verify** (NodePort `30080` on the box, so the edge is not in the way):

```sh
kubectl -n ticketing logs deploy/loans-service --tail=200   # Flyway migrated loans_service, Tomcat on 8088
kubectl -n ticketing get endpoints loans-service             # one address = the gateway can reach it

# The route reaches loans: an empty body is loans' own 400 VALIDATION_ERROR envelope.
curl -s -X POST http://127.0.0.1:30080/lending/v1/auth/login \
  -H 'Content-Type: application/json' -d '{}'
# forgot-password stops at the gateway (see "Passwords" below): 404, empty body.
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:30080/lending/v1/auth/forgot-password \
  -H 'Content-Type: application/json' -d '{"username":"nobody"}'

# The bootstrap admin signs in: 200 with data.accessToken (a LOANS token, not a
# fleet one). The password is read without echo and goes to curl on stdin
# (printf is a shell builtin), so it is in neither the history nor `ps`.
read -rsp 'loans admin password: ' LOANS_PW; echo
printf '{"username":"admin","password":"%s"}' "$LOANS_PW" \
  | curl -s -X POST http://127.0.0.1:30080/lending/v1/auth/login \
      -H 'Content-Type: application/json' --data-binary @-
unset LOANS_PW

# The spec through the docs proxy, the Basic credentials passed the same way
# (curl -K - reads them from stdin). With SWAGGER_PASSWORD blank on this host,
# drop the read and the printf and call curl alone.
read -rp 'swagger user: ' SW_USER; read -rsp 'swagger password: ' SW_PW; echo
printf 'user = "%s:%s"\n' "$SW_USER" "$SW_PW" \
  | curl -s -K - http://127.0.0.1:30080/loans-service/v3/api-docs | grep -o '"servers":\[[^]]*\]'
unset SW_USER SW_PW
#   -> "servers":[{"url":"/foundry","description":"Gateway relative server"}]
```

Then in a browser, `https://<staging edge>/foundry/swagger-ui/index.html`:
"Select a definition" lists **loans-service**, and its server reads
**/foundry - Gateway relative server**. Try-it-out needs a token from
`POST /lending/v1/auth/login` (the loans sign-in); a fleet token is refused.
Once the admin exists, remove `BOOTSTRAP_ADMIN_PASSWORD` from
`loans.zw.local.env` and re-run step 2 (with its restart).

**Passwords in the cell.** Loans sends passwords by SMS — the one a new user is
created with, and the one forgot-password sets — through the InnBucks
notification API, with loans' own `INNBUCKS_NOTIFY_*` keys in
`loans.zw.local.env` (without them those messages reach nobody). The gateway
still edge-denies `/lending/*/auth/forgot-password` (`loans-forgot-password-deny`,
an empty 404): the endpoint takes a username alone and replaces the password
BEFORE sending it, so published without a limiter it would let one request per
attempt change any known account's password, `admin` first. To hand a user a
password, a super-admin creates the user and then calls
`POST /lending/v1/users/{id}/password-reset` with `{"channel":"EMAIL"}` or
`{"channel":"WHATSAPP"}` (it delivers first and changes nothing if delivery
fails), which needs the `INNBUCKS_NOTIFY_*` or `WHATSAPP_API_KEY` keys. Lift the
deny only by moving the path onto an IP-keyed, fail-safe route like
`auth-password-reset-route` instead of the `/lending/**` catch-all.

**After a loans merge** (that commit's innbucks-loans Release run green), pin it —
a `rollout restart` alone re-runs the build already pinned:

```sh
kubectl -n ticketing set image deployment/loans-service "*=ghcr.io/mpofuslim/loans-api:sha-<full-commit-sha>"
kubectl -n ticketing rollout status deployment/loans-service
```

**Rollback** is the same `set image` to the previous pin (note it first:
`kubectl -n ticketing get deploy loans-service -o jsonpath='{.spec.template.spec.containers[0].image}'`).
To take loans out of the cell entirely — `apply` never deletes; the Service
stays and `/lending/**` goes back to the gateway's 500, while `loans_service`
and the Secret are kept:

```sh
kubectl delete -f deploy/k8s/loans/
```

The gateway rolls back by the fleet procedure in `CLAUDE.md` ("Rolling back").

> [!WARNING]
> **Never switch on the `scheduled-tasks` profile** — not in the manifest, not
> with `kubectl set env`, and not through the Secret. The manifest's
> `SPRING_PROFILES_ACTIVE=api` and empty `SPRING_PROFILES_INCLUDE` beat Secret
> lines of those two names only; another spelling there, such as
> `SPRING_PROFILES_GROUP_API=scheduled-tasks`, still adds the profile, which is
> why the Secret carries only the keys `loans.example.env` lists. The jobs act
> on the database's whole backlog at their first tick: InnBucks **pays** on the
> booking call and a Ndasenda lodgement cannot be taken back. Turning them on is
> its own go-live step, reviewed on its own, and it keeps `replicas: 1` +
> `Recreate`.

Not covered yet: loans has **no Prometheus scrape job** (it serves health only,
no metrics endpoint), so `ServiceDown` does not watch it. The staging box's
older loans container (host port 8080, its own database) is independent of this
pod; retire it separately once its clients call `/foundry/lending/**`.

## Workload hardening (OWASP A05)

Every **application** Deployment (`02`–`05`) runs with a locked-down
`securityContext`: non-root `runAsUser: 10001`, `seccompProfile: RuntimeDefault`,
`allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true` (with a `/tmp`
`emptyDir` for the JVM's temp/hsperfdata), all Linux capabilities dropped, and
`automountServiceAccountToken: false`. Rollouts are surge-safe (the old pod keeps
serving until the new one passes its probe), so a hardening regression stalls the
rollout rather than causing downtime. `loans/loans-service.yaml` carries the same
block but is the one exception to the surge: it is `Recreate` (§6), so a bad
loans rollout means loans is down until it is fixed or rolled back.

The **infra** StatefulSets (`01-infra.yaml`: postgres/redis) are left
un-hardened for now — a StatefulSet pod is replaced in place (no surge), and the
official images' root-then-drop entrypoints need per-image validation, so
hardening the data tier is a deliberately-scheduled follow-up rather than an
auto-applied change.

## Notes / gotchas

- **Service discovery**: every JVM service has a matching `Service`, and each
  service's `application.yaml` maps `<svc>` → `http://<svc>:<port>` (Spring's
  static discovery client), so the gateway resolves `lb://<svc>` →
  `<svc>:<port>` → a ready pod. A Service's name and port are therefore part of
  the contract: `FleetServiceMapTest` fails the build if they drift from the
  map. There is no registry: the Eureka pair (`02-discovery.yaml`) is retired.
- **Core banking**: there is no server-side core-banking provider. The Oradian
  integration was removed — the frontend talks to Veengu directly — so tier-2
  registration is a purely local state change and payment-service no longer
  carries wallet transfer/withdrawal endpoints. Login, MFA, browse, seat-hold,
  the InnBucks 2D-code payment and the ZimSwitch card rail all work.
- **`INNBUCKS_GATEWAY_URL`** (in `cell.zw.env`) is an inert placeholder — the
  `innbucks-core-gateway` spike it pointed at was retired (A06) and the SMS path
  moved to the authenticated notify API. Leave the default. Its `:8088` is a
  port on an off-cluster host IP and has nothing to do with the in-cluster
  `loans-service` Service, which also listens on 8088.
- **`TICKETS_PUBLIC_BASE_URL`** is set to the public origin
  (`https://dtx.innbucks.co.zw`); Compose left it at the `localhost:8080` default.
- Single replica per service; memory requests/limits mirror the Compose
  `mem_reservation`/`mem_limit`.
