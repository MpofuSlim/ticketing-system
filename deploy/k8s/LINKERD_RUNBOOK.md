# Linkerd on the ticketing cell — phase 2 of service discovery

**When to run this:** before any service in the `ticketing` namespace goes to
more than one replica, or when the in-cluster TLS item (CLAUDE.md, A02
"In-cluster TLS/mTLS") is scheduled — whichever comes first.

**Why:** phase 1 retired Eureka; every service now reaches its siblings by
Kubernetes Service name (`http://booking-service:8084`). A ClusterIP Service
balances per **connection**, and our HTTP clients pool keep-alive connections,
so with several replicas one pod takes most of the traffic and a new replica
sits idle until old connections recycle. Linkerd's proxy balances per
**request** across ready pods (latency-aware), and encrypts every pod-to-pod hop
with mTLS — without changing a line of application code or the discovery map,
because callers keep using the same Service names.

What it does NOT do: it does not fix anything that makes a service unsafe to
replicate (see [§8 checklist](#8-before-the-first-service-goes-past-1-replica)),
and it adds a proxy container to every pod (memory, see §2).

---

## 0. Before you start

- [ ] Phase 1 is deployed everywhere: all services run the Service-DNS build and
      `discovery-server` is gone (or at least nothing depends on it).
- [ ] Read the current install guide at <https://linkerd.io/2/getting-started/>
      and the release notes. **Check the release channel**: the open-source
      project publishes *edge* releases; *stable* builds are distributed by
      Buoyant (Buoyant Enterprise for Linkerd, free tier for small companies at
      the time of writing). Pick one deliberately and record it here.
- [ ] Take a maintenance window for §4 (every pod restarts once).
- [ ] Box headroom: `kubectl top node` — budget in §2.

## 1. Certificates — do not skip

A default `linkerd install` generates a trust anchor that **expires in one
year**. When it expires every meshed connection fails at once — a full outage
that looks like a network fault. Generate long-lived material yourself:

```sh
# step CLI: https://smallstep.com/docs/step-cli/installation
step certificate create root.linkerd.cluster.local ca.crt ca.key \
  --profile root-ca --no-password --insecure --not-after=87600h      # 10y trust anchor
step certificate create identity.linkerd.cluster.local issuer.crt issuer.key \
  --profile intermediate-ca --not-after=8760h --no-password --insecure \
  --ca ca.crt --ca-key ca.key                                        # 1y issuer
```

- Store `ca.key` **offline** (it is only needed to issue the next issuer cert).
- Put a calendar reminder **30 days before the issuer expires** (or install
  cert-manager to rotate the issuer automatically — preferred once there is
  more than one cell). `linkerd check` warns inside 60 days of expiry.
- Same A02 custody rules as every other secret: never committed.

## 2. Resource budget

| Component | Rough memory | Notes |
|---|---|---|
| Control plane (destination, identity, proxy-injector) | ~250–400 Mi total | one replica each on a single node |
| Proxy sidecar | ~20–60 Mi per pod | set requests/limits via annotations (below) |
| `linkerd-viz` (optional) | ~300–500 Mi | Prometheus + dashboard; skip if the cell's own Prometheus is enough |

Roughly: control plane + (pods × ~50 Mi). With 9 pods that is ~0.7 Gi — about
what the two Eureka JVMs used to cost (2 × 320–512 Mi). Pin proxy resources on
the namespace so no pod runs unbounded:

```sh
kubectl annotate ns ticketing --overwrite \
  config.linkerd.io/proxy-memory-request=20Mi \
  config.linkerd.io/proxy-memory-limit=128Mi \
  config.linkerd.io/proxy-cpu-request=10m
```

## 3. Install the control plane

```sh
curl --proto '=https' --tlsv1.2 -sSfL https://run.linkerd.io/install | sh   # or the channel you chose in §0
export PATH=$HOME/.linkerd2/bin:$PATH
linkerd version --client

linkerd check --pre                        # k3s, kernel, RBAC, clock skew; fix everything it flags
linkerd install --crds | kubectl apply -f -   # plus any Gateway API CRDs the pre-check says are missing
linkerd install \
  --identity-trust-anchors-file ca.crt \
  --identity-issuer-certificate-file issuer.crt \
  --identity-issuer-key-file issuer.key \
  | kubectl apply -f -
linkerd check                              # must be all green before going on
```

## 4. Mesh the application tier (Postgres and Redis stay out, for now)

Postgres (5432) and Redis (6379) are in Linkerd's default **opaque-ports** list
(proxied as raw TCP, not parsed as HTTP), so meshing them is safe — but it
restarts the databases. Mesh the stateless tier first; do the StatefulSets in a
separate, later window (§6).

```sh
# keep the StatefulSets out of the automatic injection
kubectl -n ticketing patch statefulset postgres --type merge \
  -p '{"spec":{"template":{"metadata":{"annotations":{"linkerd.io/inject":"disabled"}}}}}'
kubectl -n ticketing patch statefulset redis --type merge \
  -p '{"spec":{"template":{"metadata":{"annotations":{"linkerd.io/inject":"disabled"}}}}}'

# opt the namespace in, then restart the Deployments ONE AT A TIME
kubectl annotate ns ticketing linkerd.io/inject=enabled --overwrite
for d in user-service event-service seat-service booking-service payment-service \
         loyalty-service marketplace-service api-gateway; do
  kubectl -n ticketing rollout restart deployment/$d
  kubectl -n ticketing rollout status  deployment/$d --timeout=5m || { echo "STOP: $d"; break; }
done
```

Restart the gateway **last**: it is the public edge (NodePort 30080 from the
host nginx), and meshing it last means every backend already speaks mTLS when
it starts sending. Traffic from nginx into the gateway is from outside the mesh
and is accepted as plain HTTP — that hop stays as it is today (loopback on the
box).

## 5. Verify

```sh
linkerd -n ticketing check --proxy                # every meshed pod healthy
kubectl -n ticketing get pods                     # meshed pods show 2/2 containers
```

With `linkerd-viz` installed (optional):

```sh
linkerd viz install | kubectl apply -f - && linkerd viz check
linkerd viz -n ticketing stat deploy              # success rate, RPS, latency per service
linkerd viz -n ticketing edges deploy             # every service->service edge should read SECURED
```

Functional checks through the edge, same as after any deploy: an
unauthenticated call to a secured endpoint returns `401`; a login works; a
ticket scan works; a payment code generates. Watch `linkerd viz stat` success
rates for 15 minutes.

## 6. Later: mesh Postgres and Redis (in-cluster TLS to the data tier)

In a maintenance window, remove the `linkerd.io/inject: disabled` annotation
from the two StatefulSets and restart them. Their ports are opaque by default;
annotate explicitly anyway so it survives a change to Linkerd's defaults:

```sh
kubectl -n ticketing annotate svc postgres config.linkerd.io/opaque-ports=5432 --overwrite
kubectl -n ticketing annotate svc redis    config.linkerd.io/opaque-ports=6379 --overwrite
```

This is what closes CLAUDE.md's "service↔Postgres / ↔Redis are plaintext behind
the edge" deferral, without per-client TLS configuration.

## 7. Retries, timeouts — carefully

Linkerd can retry failed requests, but **never enable retries on a route that
moves money or creates something**. CLAUDE.md is explicit: InnBucks code
generation, the ZimSwitch prepare-checkout and the EcoCash charge are **never
retried**, because a retry can mint a second payable instrument or debit twice.
A mesh-level retry would silently break that rule. If retries are ever added,
scope them to idempotent `GET` routes by route, not by service, and list them
here.

## 8. Before the first service goes past 1 replica

The mesh makes replicas *balanced*; it does not make a service *safe* to run
twice. Check each one first:

- [ ] **Postgres connection budget** — N replicas × pool size must fit
      `max_connections` (docs/booking-capacity-and-scaling.md §4.1).
- [ ] **Scheduled jobs must be safe to run on every replica at once.** Each
      replica runs every `@Scheduled` method. As of this writing (checked by
      grepping for `@SchedulerLock`):
      - **booking-service** and **seat-service**: jobs carry ShedLock.
      - **event-service** `EventExpiryScheduler`: no lock, but documented
        idempotent (`WHERE active = true`) and safe on multiple replicas.
      - **payment-service — NOT safe to replicate yet.** `PaymentResolutionJob`
        (the code / card / EcoCash pollers and the reconciliation scan),
        `SettlementReconciliationJob` and `AuditIntegrityVerifier` have no
        lock. Two replicas would poll the same payment rows concurrently, and
        the ZimSwitch final-status read is one-shot and throttled upstream to
        two per checkout per minute (CLAUDE.md, card rail) — a race there can
        consume the one read and park the row. Add ShedLock (or per-row claim
        with `SKIP LOCKED`) **before** payment-service goes past 1 replica.
      - **user-service**: the jobs in `TokenRevocationService`, `OtpService`,
        `RefreshTokenService`, `LoginRateLimiter` and `AuditIntegrityVerifier`
        have no lock; they look like housekeeping sweeps, but confirm each is
        idempotent before replicating.
      Any new `@Scheduled` job needs a lock or a documented reason it is
      idempotent.
- [ ] **In-memory state**: user-service's `LoginRateLimiter` falls back to an
      in-memory counter when Redis is unavailable (per-replica limits during a
      Redis outage — acceptable, but know it); short caches such as
      `TicketScanService`'s event-window cache are per replica by design.
- [ ] **Anything that assumes one instance** — e.g. a future notifications SSE
      stream needs Redis pub/sub fan-out (CLAUDE.md, Notifications).
- [ ] **Single node**: replicas protect against a pod crash, not the box going
      down. Real node-level HA needs a second node, then `topologySpreadConstraints`
      and a `PodDisruptionBudget` per service.

## 9. Rollback

```sh
kubectl annotate ns ticketing linkerd.io/inject-                 # stop injecting
kubectl -n ticketing get deploy -o name | xargs kubectl -n ticketing rollout restart
# once no pod shows 2/2:
linkerd viz uninstall | kubectl delete -f -    # if installed
linkerd uninstall     | kubectl delete -f -
```

The application never depended on the mesh for correctness — only for
balancing and encryption — so removing it returns the cell exactly to the
phase-1 state (Service DNS, per-connection balancing, plaintext in-cluster).
