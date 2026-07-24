# Observability stack — OpenSearch logs + traces

OpenSearch-based tracing/monitoring for the cell, matching the InnBucks org
standard. Five components, ~3GB RAM total, sized for the single-node 15GB box:

```
services (OTLP/HTTP, 10% sampled) ──> otel-collector ──gRPC──> data-prepper ──> OpenSearch
container JSON logs (ticketing ns) ──> fluent-bit ─────────────────────────────^
                                                            opensearch-dashboards (UI)
```

The Spring services need no code changes — they already carry the OTel
bridge + OTLP exporter, JSON logging (`json` profile), and fleet-wide
`X-Correlation-Id` propagation. The switches live in `cell.zw.env`:
`TRACING_ENABLED=true` + `OTLP_ENDPOINT` pointing at the collector.

## Deploy

```sh
kubectl apply -f ~/ticketing-system/deploy/k8s/observability/
kubectl -n observability get pods   # wait until all 5 are Running/Ready

# flip the fleet's tracing switch (cell.zw.env changed in this repo):
kubectl -n ticketing delete configmap cell-zw
kubectl -n ticketing create configmap cell-zw \
  --from-env-file=/home/ec2-user/ticketing-system/deploy/cells/cell.zw.env
kubectl -n ticketing get deploy -o name | xargs kubectl -n ticketing rollout restart
```

(Deliberately NOT under the root `deploy/k8s/` apply path — this stack is
opt-in per cell.)

## Use

```sh
kubectl -n observability port-forward svc/opensearch-dashboards 5601:5601
```

Then open <http://localhost:5601>:

- **Logs**: Discover → create index pattern `ticketing-logs-*` (time field
  `@time`/`@timestamp`). The JSON encoder's fields (`level`, `logger_name`,
  `traceId`, `correlationId`, `message`, k8s pod/container labels) are
  top-level and filterable.
- **Traces**: Observability → Trace analytics (reads the
  `otel-v1-apm-span-*` / service-map indices Data Prepper writes). The
  service map shows the gateway → service → sibling call graph; a `traceId`
  from any log line links straight to its trace.

## Security posture

The OpenSearch security plugin is **disabled** — acceptable only while every
Service in this namespace is ClusterIP on the single-node cell (nothing is
reachable from outside the node; the box security group + gateway edge carry
the perimeter). **Never** expose Dashboards/OpenSearch via NodePort/Ingress
without re-enabling security + credentials first.

## Retention

local-path PVC is 15Gi. Indices are daily; prune manually until an ISM policy
is configured (Dashboards → Index Management → ISM lets you automate this):

```sh
# delete log indices older than e.g. 14 days (adjust the date):
kubectl -n observability exec statefulset/opensearch -- \
  curl -s -XDELETE "http://localhost:9200/ticketing-logs-2026.07.01"
# disk usage per index:
kubectl -n observability exec statefulset/opensearch -- \
  curl -s "http://localhost:9200/_cat/indices?v&s=store.size:desc"
```

## Removal

```sh
kubectl delete namespace observability   # PVC/data removed with it
# and set TRACING_ENABLED=false in cell.zw.env + recreate the configmap
```
