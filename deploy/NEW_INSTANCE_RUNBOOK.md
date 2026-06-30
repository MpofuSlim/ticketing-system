# New-instance standup runbook (a cell, from scratch)

How to stand a cell up on a **brand-new host with an empty database** — e.g.
moving the Zimbabwe (`zw`) cell to a fresh EC2 instance. This is the
*fresh-start* path: **no data migration**. For the per-cell config model and
the "add a new country" workflow, see [`README.md`](./README.md).

> Examples below use the `zw` cell. Substitute your ISO code (`ke`, `zm`, …)
> and the matching `cell.<iso>.env` / `cell.<iso>.local.env` files.

## What self-provisions vs what you supply

On a fresh host the stack builds most of itself:

| Self-provisions (do nothing) | You must supply |
|---|---|
| The 6 per-service databases (`docker/postgres/init-databases.sql` runs once on the empty volume) | Secrets → `cell.<iso>.local.env` |
| Every table schema (Flyway runs per service on first boot, `ddl-auto=validate`) | This host's IP in `cell.<iso>.env` (`INNBUCKS_GATEWAY_URL`) |
| Redis / Kafka state (start empty) | A GHCR login to pull the private images |
| The first `SUPER_ADMIN` row (only if `BOOTSTRAP_ADMIN_PASSWORD` is set) | DNS + TLS/edge cutover to the new host |

So an empty DB is expected and correct — the only seeded row is the bootstrap
admin.

## 1. Provision the instance

- **Size:** the cell runs 8 JVM services + Postgres + Redis + Kafka. Use
  **≥ 16 GB RAM**, 4 vCPU, 40 GB+ disk.
- **Firewall / security group:**
  - `22` (SSH) — your IP only
  - `18080` (api-gateway — the public entrypoint) — from your edge / Cloudflare only
  - **Nothing else.** Every other port (`5432`, `6379`, `8081–8086`, `8761`, …)
    is published on `127.0.0.1` only in `docker-compose.yml`; do not expose them.

## 2. Install Docker + the Compose v2 plugin

```bash
# Amazon Linux 2023
sudo dnf install -y docker git && sudo systemctl enable --now docker
sudo usermod -aG docker "$USER" && newgrp docker
sudo dnf install -y docker-compose-plugin
docker compose version    # confirm v2.x
```

## 3. Clone the repo

```bash
cd ~ && git clone https://github.com/MpofuSlim/ticketing-system.git
cd ticketing-system        # master has the latest released code
```

## 4. Log in to GHCR (images are private)

```bash
echo "<GHCR_PAT_with_read:packages>" | docker login ghcr.io -u <ghcr-owner> --password-stdin
```

## 5. Point the committed cell config at this host

Edit `deploy/cells/cell.<iso>.env`. The host-specific line is the
core-gateway adapter URL:

```properties
# set to THIS host's private IP (the host-resident innbucks-core-gateway jar, :8088)
INNBUCKS_GATEWAY_URL=http://<NEW_HOST_PRIVATE_IP>:8088
```

Also confirm (change only if they differ for this deploy):
`IMAGE_TAG` (`latest`, or pin a `sha-<commit>` for a real release),
`CORS_ALLOWED_ORIGINS`, and `INNBUCKS_CELLS_REGISTRY` (keep the public
hostname if you're repointing DNS to this host rather than changing it).

## 6. Create the secrets file (host-only, gitignored)

`.gitignore` excludes `cells/*.local.env` — it never enters git.

```bash
cat > deploy/cells/cell.zw.local.env <<EOF
# --- generate fresh: empty DB, nothing encrypted/hashed/signed under old keys ---
POSTGRES_PASSWORD=$(openssl rand -base64 32)
REDIS_PASSWORD=$(openssl rand -base64 32)
EUREKA_PASSWORD=$(openssl rand -hex 24)
JWT_SECRET=$(openssl rand -base64 48)
INTERNAL_API_TOKEN=$(openssl rand -base64 32)
LOYALTY_VOUCHER_SECRET=$(openssl rand -base64 32)
LOYALTY_QR_SECRET=$(openssl rand -base64 32)
NATIONAL_ID_HMAC_SECRET=$(openssl rand -base64 32)
MFA_ENCRYPTION_KEY=$(openssl rand -base64 32)        # MUST decode to 32 bytes (AES-GCM-256)
SWAGGER_USER=admin
SWAGGER_PASSWORD=$(openssl rand -base64 18)          # gateway Swagger UI login (recommended)
BOOTSTRAP_ADMIN_PASSWORD=$(openssl rand -base64 24)  # one-time; cleared in step 9

# --- copy REAL values (external providers / sibling stacks) — do NOT regenerate ---
WHATSAPP_API_KEY=<real WhatsApp provider key>
BANK_API_URL=https://staging.innbucks.co.zw
BANK_API_KEY=<real InnBucks Notify API key>
BANK_API_USERNAME=<real>
BANK_API_PASSWORD=<real>
ORADIAN_INTERNAL_TOKEN=<MUST equal the Oradian middleware's INTERNAL_API_TOKEN>
EOF
chmod 600 deploy/cells/cell.zw.local.env
```

**Two buckets, on purpose:**

- **Generate fresh** — the internal secrets. With an empty DB there is no
  existing data encrypted/hashed/signed under the old keys, so new values are
  safe. (`MFA_ENCRYPTION_KEY` and `NATIONAL_ID_HMAC_SECRET`: once the cell has
  live data, these become *stable forever* — rotating them orphans every TOTP
  secret / national-ID hash respectively.)
- **Copy real values** — `BANK_API_*` and `WHATSAPP_API_KEY` are external
  provider credentials; `ORADIAN_INTERNAL_TOKEN` must match the Oradian
  middleware. Pull these from your secrets store (or the previous host's
  `cell.<iso>.local.env`).

## 7. Bring the cell up

```bash
deploy/cell.sh zw up
```

This creates the `innbucks-shared` docker network, pulls the `IMAGE_TAG`
images, and blocks until every container reports healthy.

## 8. Smoke test

```bash
deploy/cell.sh zw status                              # every container (healthy)
curl -s http://localhost:8081/actuator/health         # {"status":"UP"}
curl -s http://localhost:18080/actuator/health        # gateway up
deploy/cell.sh zw logs user-service | grep "pinned to country"   # → country=ZW
# confirm Flyway built the schema on the empty DB:
docker exec innbucks-postgres psql -U postgres -d user_service -c '\dt' | head
```

## 9. First-run admin, then disarm the bootstrap

```bash
# 1. Log in once via POST /auth/login with BOOTSTRAP_ADMIN_PASSWORD; change the password.
# 2. Remove the seed so it can't re-seed, and restart user-service:
sed -i '/^BOOTSTRAP_ADMIN_PASSWORD=/d' deploy/cells/cell.zw.local.env
deploy/cell.sh zw restart user-service
```

## 10. External dependency NOT in this compose

The **InnBucks core-gateway adapter** (the host-resident jar on `:8088` that
SMS routes through, referenced by `INNBUCKS_GATEWAY_URL`) is **not** part of
`docker-compose.yml`. Install and run it on the new host (or make it reachable
from there) the same way it runs on the existing host — otherwise the
SMS/notification fallback path fails.

## 11. Cut traffic over

1. Put your edge in front of the gateway:
   **Cloudflare / nginx → `http://<NEW_HOST>:18080`** (the only externally
   exposed port), terminating TLS as the current host does.
2. Validate against the new host directly first (temp subdomain or a `Host:`
   override) before touching production DNS.
3. When green, **repoint the public hostname** (e.g. `dtx.innbucks.co.zw` /
   the Cloudflare origin) to the new instance.
4. Watch logs under real traffic, then **decommission the old instance.**

## Required-secret checklist

The container fails to boot (a `${VAR:?}` guard fires) if any of these is
missing. All live in `cell.<iso>.local.env` except `INNBUCKS_COUNTRY`, which is
in the committed `cell.<iso>.env`:

`POSTGRES_PASSWORD`, `REDIS_PASSWORD`, `EUREKA_PASSWORD`, `JWT_SECRET`,
`INTERNAL_API_TOKEN`, `ORADIAN_INTERNAL_TOKEN`, `LOYALTY_VOUCHER_SECRET`,
`LOYALTY_QR_SECRET`, `NATIONAL_ID_HMAC_SECRET`, `MFA_ENCRYPTION_KEY`,
`WHATSAPP_API_KEY`, `BANK_API_KEY`, `BANK_API_USERNAME`, `BANK_API_PASSWORD`
— plus `INNBUCKS_COUNTRY` (committed).

### Recommended (boots without it, but you should set it)

`SWAGGER_PASSWORD` is **not** guarded by `${VAR:?}`, so the cell boots without
it. Cells run the `prod` profile, so a blank password makes the gateway's
aggregated Swagger UI **fail closed** — it's disabled and returns `404` (a loud
startup warning is logged). Set `SWAGGER_PASSWORD` in `cell.<iso>.local.env` to
expose the docs behind an HTTP Basic login instead. `SWAGGER_USER` defaults to
`admin`; generate a password with `openssl rand -base64 18`. (Step 6's secrets
heredoc already does this.)

## Published ports (reference)

| Port (host) | Service | Exposure |
|---|---|---|
| `18080` | api-gateway (→ 8080) | **public** (front with edge/TLS) |
| `8081–8086` | user / event / seat / booking / payment / loyalty | `127.0.0.1` |
| `18761` / `18762` | discovery-server (Eureka) | `127.0.0.1` |
| `5432` / `6379` / `29092` | Postgres / Redis / Kafka | `127.0.0.1` |
| `19090` | gateway management/actuator | `127.0.0.1` |
