# Production upgrade runbook — `13add2eb` → `d74a643d`

A staged upgrade of the ZW production cell across a gap of **426 files / ~33k insertions**,
carrying **9 Flyway migrations** that run against the live database, **one new service**, and
**one new database that does not yet exist**.

This is not the restart-one-deployment routine used for a single merge. Work through it in order.

---

## 0. Why this needs its own procedure

| | |
|---|---|
| Prod was at | `13add2eb` |
| Master is at | `d74a643d` |
| Migrations that will auto-run | 9, across 4 services |
| New service | `marketplace-service` |
| New database required | `marketplace_service` — **not present on prod** |
| Removed API | Oradian transfer / withdraw / transactions |
| Config keys missing from ConfigMap | 48 |

Flyway runs on pod startup with no dry run and no prompt. The moment a new image starts, its
migrations execute.

### The migration that deserves the most care

`payment-service/V12__payment_order_generalization.sql` rewrites the payments table:

```sql
ALTER TABLE payment ADD COLUMN order_ref VARCHAR(64);
UPDATE payment SET order_ref = booking_id::text WHERE order_ref IS NULL;   -- backfills every row
ALTER TABLE payment ALTER COLUMN order_ref SET NOT NULL;
ALTER TABLE payment ALTER COLUMN booking_id DROP NOT NULL;
DROP INDEX uq_payment_active_booking;
CREATE UNIQUE INDEX uq_payment_active_order
    ON payment(order_type, order_ref)
    WHERE status NOT IN ('FAILED','REJECTED','EXPIRED');
```

If any two historical non-terminal payments share a `booking_id`, the new unique index fails, the
migration aborts, and payment-service will not start. **Step 2 checks for this before you commit
to anything.**

---

## 1. Back up the database — not optional

Everything below is recoverable only from here. `deploy/NEW_INSTANCE_RUNBOOK.md` §12 already says
backups are not optional before real traffic; this is the moment that matters.

```bash
PGPOD=$(kubectl -n ticketing get pod -l app=postgres -o jsonpath='{.items[0].metadata.name}')
STAMP=$(date -u +%Y%m%dT%H%M%SZ)

# every service database, one file, inside the pod
kubectl -n ticketing exec "$PGPOD" -- sh -c \
  'pg_dumpall -U "$POSTGRES_USER" --clean' > ~/prod-backup-$STAMP.sql

ls -lh ~/prod-backup-$STAMP.sql          # sanity: must not be a few bytes
grep -c 'CREATE TABLE' ~/prod-backup-$STAMP.sql
```

Copy it **off the box** before continuing. A backup on the instance you are about to change is not
a backup.

```bash
# from your laptop
scp -i aristocrat-pem.pem ec2-user@16.28.12.206:~/prod-backup-*.sql ./
```

---

## 2. Pre-flight — will V12 actually succeed?

Run this **before** anything else. It answers the one question that can strand payment-service.

```bash
PGPOD=$(kubectl -n ticketing get pod -l app=postgres -o jsonpath='{.items[0].metadata.name}')

kubectl -n ticketing exec "$PGPOD" -- psql -U postgres -d payment_service -c "
SELECT booking_id, count(*)
FROM payment
WHERE status NOT IN ('FAILED','REJECTED','EXPIRED')
GROUP BY booking_id
HAVING count(*) > 1;"
```

- **Zero rows** → V12's unique index will build. Proceed.
- **Any rows** → **STOP.** V12 will fail and payment-service will not start. Those duplicates must
  be resolved first (decide per row which payment is real, and set the others to a terminal
  status). Do not improvise this against production — it is money data.

Also confirm what you're starting from, so a rollback has a target:

```bash
kubectl -n ticketing exec "$PGPOD" -- psql -U postgres -d payment_service \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"
kubectl -n ticketing exec "$PGPOD" -- psql -U postgres -d user_service \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"
```

---

## 3. Create the missing database

`init-databases.sql` only runs on **first** Postgres init, so the new database will never appear
on its own. Without it, marketplace-service crashloops.

```bash
PGPOD=$(kubectl -n ticketing get pod -l app=postgres -o jsonpath='{.items[0].metadata.name}')
kubectl -n ticketing exec "$PGPOD" -- psql -U postgres -c "CREATE DATABASE marketplace_service;"
kubectl -n ticketing exec "$PGPOD" -- psql -U postgres -c "\l" | grep marketplace
```

---

## 4. Pin the image tag

Do **not** run an upgrade this size on `latest`. If anything misbehaves you need to know exactly
what is running, and `latest` moves under you on every restart.

Pick the SHA you intend to ship — the master commit you tested:

```bash
SHA=sha-d74a643d      # or whichever commit you validated

kubectl -n ticketing get configmap cell-zw -o json \
  | jq --arg t "$SHA" '.data.IMAGE_TAG = $t' \
  | kubectl apply -f -

kubectl -n ticketing get configmap cell-zw -o jsonpath='{.data.IMAGE_TAG}{"\n"}'
```

> The manifests hardcode `:latest` in the `image:` field, so also confirm whether `IMAGE_TAG` is
> actually consumed by the k8s path on this cell — it is used by the compose path. If the
> deployments pin `:latest` directly, set the image explicitly instead:
> `kubectl -n ticketing set image deployment/<svc> '*=ghcr.io/mpofuslim/<svc>:sha-d74a643d'`

---

## 5. Add the config keys

48 keys are missing. Most are inert — the payment rails and federation **fail safe when blank**, so
leave them blank until you have real credentials. Add the ones that change behaviour.

```bash
cd ~/ticketing-system

# review the full list first
comm -23 <(grep -oE '^[A-Z][A-Z0-9_]*=' deploy/cells/cell.zw.env | sed 's/=$//' | sort -u) \
         <(kubectl -n ticketing get configmap cell-zw -o json | jq -r '.data|keys[]' | sort)
```

**Do not regenerate the ConfigMap wholesale from `cell.zw.env`.** It carries blank/placeholder
values for `JWT_SECRET`, `INTERNAL_API_TOKEN`, `REDIS_PASSWORD` and `POSTGRES_PASSWORD` — a
`--from-env-file` rebuild would overwrite prod's live values with placeholders.

Add keys individually:

```bash
kubectl -n ticketing get configmap cell-zw -o json \
  | jq '.data.LOYALTY_SUPPORTED_CURRENCIES = "USD,ZWG"
      | .data.LOYALTY_FX_MAX_CHANGE_PERCENT = "10"
      | .data.MAIL_ENABLED = "false"' \
  | kubectl apply -f -
```

Leave blank deliberately (each returns a clean 503 rather than failing oddly): all `ZIMSWITCH_*`,
all `ECOCASH_*`, `AUTH_FEDERATION_PUBLIC_KEY`, all `LOYALTY_PARTNER_REGISTRATION_*`.

---

## 6. Apply the manifests

Manifests changed — `04-services.yaml` (+62, adds marketplace-service), `03-user-service.yaml`
(−7). This must happen before the rollouts.

```bash
cd ~/ticketing-system && git log --oneline -1      # confirm d74a643d
kubectl apply -f deploy/k8s/
kubectl -n ticketing get deploy
```

---

## 7. Roll services one at a time

**Order matters.** Each step ends with a health check; do not start the next until the current one
is green. If any service fails, go to §9 before continuing.

### 7.1 discovery-server

```bash
kubectl -n ticketing rollout restart deployment/discovery-server deployment/discovery-server-2
kubectl -n ticketing rollout status deployment/discovery-server --timeout=5m
```

### 7.2 user-service — runs V35, V36, V37

V35 is the roles-and-permissions migration; V37 adds notifications.

```bash
kubectl -n ticketing rollout restart deployment/user-service
kubectl -n ticketing rollout status deployment/user-service --timeout=10m

# confirm the migrations actually applied and succeeded
PGPOD=$(kubectl -n ticketing get pod -l app=postgres -o jsonpath='{.items[0].metadata.name}')
kubectl -n ticketing exec "$PGPOD" -- psql -U postgres -d user_service \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 4;"
```

Every row must show `success = t`. A single `f` means Flyway halted — stop and read the pod logs.

> **Expect a session-turnover effect.** V35 migrates authorization from `hasRole` to
> `hasAuthority`. Tokens already in admins' browsers carry `roles` but no `perms` claim.
> `JwtFilter.permissionsFor` bridges this by re-deriving permissions from roles, so it should be
> invisible — but if admins report 403s on console pages, have them sign out and back in, and check
> that bridge is present before assuming it is a new bug.

### 7.3 payment-service — the risky one (V12, V13, V14)

```bash
kubectl -n ticketing rollout restart deployment/payment-service
kubectl -n ticketing rollout status deployment/payment-service --timeout=10m

kubectl -n ticketing exec "$PGPOD" -- psql -U postgres -d payment_service \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 4;"

# V12 backfilled every row — no NULLs should remain
kubectl -n ticketing exec "$PGPOD" -- psql -U postgres -d payment_service \
  -c "SELECT count(*) AS null_order_refs FROM payment WHERE order_ref IS NULL;"
```

`null_order_refs` must be `0`.

### 7.4 the remaining services

```bash
for svc in event-service seat-service booking-service; do
  kubectl -n ticketing rollout restart deployment/$svc
  kubectl -n ticketing rollout status deployment/$svc --timeout=10m
done

kubectl -n ticketing exec "$PGPOD" -- psql -U postgres -d booking_service \
  -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"
kubectl -n ticketing exec "$PGPOD" -- psql -U postgres -d event_service \
  -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 2;"
```

### 7.5 marketplace-service — new, and the most likely to fail

```bash
kubectl -n ticketing rollout status deployment/marketplace-service --timeout=10m
kubectl -n ticketing logs -l app=marketplace-service --tail=50
```

If it crashloops, the usual cause is the database from §3 missing, or its config keys absent.

### 7.6 api-gateway — last

Restart the edge only once everything behind it is healthy.

```bash
kubectl -n ticketing rollout restart deployment/api-gateway
kubectl -n ticketing rollout status deployment/api-gateway --timeout=5m
```

---

## 8. Verify

```bash
kubectl -n ticketing get pods            # every pod Running, RESTARTS 0
kubectl -n ticketing get deploy          # every deployment fully available
```

Through the public edge:

- An unauthenticated call to a secured endpoint returns **401**, not 404 (new image present, routed).
- Sign in to the console as an admin — confirm no 403s on pages that worked before (the §7.2 note).
- Create a test booking end to end; confirm the InnBucks 2D-code rail still issues a code.
- Scan a ticket for an event running **today** → allowed; one for another day → `WRONG_EVENT_DAY`.
- A pure `TEAM_MEMBER` logs in with a password only, no TOTP prompt.

**Known-off by design after this upgrade:** card (ZimSwitch) and EcoCash payments return 503 until
credentials are provisioned; the Oradian transfer/withdraw/transactions endpoints are gone for good.

---

## 9. Rollback

There is no rollback workflow, and **Flyway migrations do not roll back**. Reverting images while
leaving the new schema in place is usually survivable (the old code ignores new columns) but is not
guaranteed — V12's `DROP INDEX uq_payment_active_booking` removes a constraint the old
payment-service relies on.

**Images only** (schema stays migrated):

```bash
kubectl -n ticketing set image deployment/<svc> '*=ghcr.io/mpofuslim/<svc>:sha-13add2eb'
kubectl -n ticketing rollout status deployment/<svc>
```

**Full revert** (schema included) — only from §1's backup, and it discards everything written since:

```bash
PGPOD=$(kubectl -n ticketing get pod -l app=postgres -o jsonpath='{.items[0].metadata.name}')
kubectl -n ticketing exec -i "$PGPOD" -- psql -U postgres < ~/prod-backup-<stamp>.sql
```

Scale every service to 0 before restoring, and back up again afterwards.

---

## 10. Strongly recommended before any of this

Rehearse against a **restored copy** of the production database. It is the only way to find out
whether V12's backfill survives your actual payment history, and it converts the riskiest step here
into something you have already watched succeed.

Restore §1's dump into a scratch Postgres, point a local stack at it, and start user-service and
payment-service. If their migrations apply cleanly there, the production run is far more
predictable.
