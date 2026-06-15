# Cell deploy template (multi-cell step 5)

Each InnBucks country runs as its own independent stack — a "cell". This
directory turns "add a new country" into a config-only change: copy one file,
edit a few values, run one command. No service code changes, ever.

Built on Docker Compose today; the cell abstraction (one recipe + N per-cell
value files) is the same shape Helm uses, so when k8s adoption is on the
table later, the per-cell values move across essentially unchanged — only
the rendering layer (this script vs `helm install`) differs.

## Layout

```
deploy/
├── cell.sh                   # the one wrapper you run
├── README.md                 # this file
└── cells/
    ├── cell.example.env      # template — copy this for new cells
    ├── cell.zw.env           # Zimbabwe cell (committed defaults only)
    └── cell.zw.local.env     # gitignored — real secrets, host-side only
```

Two-file layered config per cell:

| File | Where it lives | Holds |
|---|---|---|
| `cell.<iso>.env` | git, reviewed in PRs | Non-secret defaults: `INNBUCKS_COUNTRY`, CORS origins, image tag, ports, gateway URLs |
| `cell.<iso>.local.env` | host filesystem only (gitignored) | Real passwords / JWT secrets / API keys |

Docker Compose's `--env-file` flag is repeatable; `cell.sh` passes the
committed file first, then the local file. The local file wins on any key
that appears in both — so committed `REPLACE_ME_in_cell.zw.local.env`
placeholders get overridden by real values at deploy time without those
values ever entering git.

## Usage

```sh
# Bring the Zimbabwe cell up (uses cell.zw.env + cell.zw.local.env)
deploy/cell.sh zw up

# Health / status
deploy/cell.sh zw status

# Tail one service's logs
deploy/cell.sh zw logs payment-service

# Restart one service
deploy/cell.sh zw restart user-service

# Pull a new image tag from GHCR (override IMAGE_TAG in cell.zw.local.env
# or in the env first)
IMAGE_TAG=sha-abc123 deploy/cell.sh zw pull
IMAGE_TAG=sha-abc123 deploy/cell.sh zw up

# Stop the cell (data volumes kept; pass `-v` to wipe them)
deploy/cell.sh zw down
```

The wrapper hides the underlying `docker compose --env-file ... --env-file
... --profile payments up -d --no-build --wait` so you don't need to remember
the flags.

## Adding a new cell — six steps

1. **Pick the ISO 3166-1 alpha-2 code** for the new country (one of:
   `ZW KE ZM MW ZA BW MZ LS SZ NG` — the InnBucks markets table validated at
   service startup).
2. **Copy the template**:
   ```sh
   cp deploy/cells/cell.example.env deploy/cells/cell.ke.env
   ```
3. **Edit the committed file** — at minimum:
   - `INNBUCKS_COUNTRY=KE`
   - `CORS_ALLOWED_ORIGINS=https://app-ke.example.com`
   - `WHATSAPP_GATEWAY_URL=` (the Kenyan provider's URL)
   - `INNBUCKS_GATEWAY_URL=http://<KE-host-private-IP>:8088`
   - `GHCR_OWNER` / `IMAGE_TAG` if different from defaults
4. **On the cell's host machine**, create `deploy/cells/cell.ke.local.env`
   (do NOT commit it — `.gitignore` excludes `cells/*.local.env`):
   ```sh
   cat > deploy/cells/cell.ke.local.env <<EOF
   POSTGRES_PASSWORD=$(openssl rand -base64 32)
   REDIS_PASSWORD=$(openssl rand -base64 32)
   EUREKA_PASSWORD=$(openssl rand -hex 24)
   JWT_SECRET=$(openssl rand -base64 48)
   ORADIAN_INTERNAL_TOKEN=$(openssl rand -base64 32)
   INTERNAL_API_TOKEN=$(openssl rand -base64 32)
   LOYALTY_VOUCHER_SECRET=$(openssl rand -base64 32)
   LOYALTY_QR_SECRET=$(openssl rand -base64 32)
   WHATSAPP_API_KEY=<from provider>
   BOOTSTRAP_ADMIN_PASSWORD=$(openssl rand -base64 24)
   EOF
   chmod 600 deploy/cells/cell.ke.local.env
   ```
5. **Deploy**:
   ```sh
   deploy/cell.sh ke up
   ```
6. **Smoke test**:
   ```sh
   deploy/cell.sh ke status            # all containers `(healthy)`
   curl -s http://localhost:8081/actuator/health   # = {"status":"UP"}
   # check a startup log line:
   deploy/cell.sh ke logs user-service | grep "pinned to country"
   #   → [startup] user-service pinned to country=KE
   ```

After step 6 the cell is live: all 6 reactor services are running with
`country=KE` in their MDC, the Kenyan customer DB is isolated, and JWT
tokens minted on this cell are scoped to Kenyan MSISDNs.

## What about the existing single-cell setup?

The legacy `scripts/deploy.sh <image-tag>` + root-level `.env` workflow
**still works unchanged**. Step 5 adds a parallel path; nothing is removed.
You can stay on the legacy path for the Zimbabwe cell while spinning up
cell #2 via `deploy/cell.sh`.

When you're ready to fully migrate Zimbabwe over:
```sh
cp .env deploy/cells/cell.zw.local.env   # secrets carry across as-is
deploy/cell.sh zw up
```

## Cross-cell concerns explicitly NOT solved here

- **Service-discovery across cells** — each cell has its own Eureka. Services
  in cell ZW cannot discover services in cell KE. This is by design — see the
  multi-cell architecture notes for the home-anchored model.
- **JWT trust across cells** — each cell has its own `JWT_SECRET` today. A
  token minted in ZW will not validate in KE. Step 6 of the roadmap swaps
  this for RS256 + JWKS so cells trust each other's tokens by published
  public key.
- **Edge routing** (Kenyan customer → KE cell automatically) — step 7. Today
  each cell is reached directly by its own URL.

## Migrating to Kubernetes later

If/when the team adopts k8s, the cell abstraction carries over 1:1:

- `deploy/cells/cell.zw.env` → Helm `values-zw.yaml`
- `deploy/cells/cell.zw.local.env` → k8s `Secret` (sealed-secrets / external
  secrets / vault)
- `deploy/cell.sh zw up` → `helm install zw-cell ./chart --values values-zw.yaml`

The `docker-compose.yml` itself needs replacing with chart templates
(`Deployment` + `Service` per app); the tool `kompose` does ~70% of that
conversion mechanically. Realistic effort: 3–5 days for someone learning
k8s. The *per-cell values* — what makes ZW different from KE — don't
change at all.
