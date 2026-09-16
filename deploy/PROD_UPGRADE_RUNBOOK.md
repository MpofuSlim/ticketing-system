# Production upgrade runbook — `13add2eb` → `d74a643d`

A staged upgrade of the ZW production cell across a gap of **426 files / ~33k insertions**,
carrying **9 Flyway migrations** that run against the live database, **one new service**, and
**one new database that does not yet exist**.

This is not the restart-one-deployment routine used for a single merge. Work through it in order.

> **Executed against production on 2026-09-11, successfully.** All 9 migrations applied
> `success = t` against live data, no rollback was needed, and no step had to be retried. The
> sections below were corrected afterwards to match what actually worked — most importantly §4,
> whose original `IMAGE_TAG` instruction does nothing on k8s. Where a section says what happened on
> the day, that is observed, not predicted.

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
| Config keys missing from ConfigMap | 48 — **all defaulted in code; none were added** (§5) |

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

On 2026-09-11 the §2 pre-flight returned `(0 rows)` on prod and V12 then applied cleanly. That is
the only genuinely irreversible step in this runbook, and it is worth the two minutes to check.

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

## 4. Pin the image tag — NOT via `IMAGE_TAG`

> **Corrected after the 2026-09-11 production run.** An earlier draft of this section told you to
> set `IMAGE_TAG` in the ConfigMap. **That does nothing on k8s.** `IMAGE_TAG` is referenced nowhere
> in `deploy/k8s/` — it is consumed by the compose path only — and every manifest hardcodes
> `image: ghcr.io/mpofuslim/<svc>:latest`. Setting it pins nothing, and worse, it looks like it
> worked.

Two facts decide how pinning actually works here:

1. **`kubectl apply` resets images to `:latest`**, because that is what the manifests say. So
   pinning must happen **after** §6, never before it.
2. **`kubectl set image` both pins and triggers the rollout.** It therefore *replaces* the
   `rollout restart` in §7 rather than being an extra step before it.

**The tag is `sha-` followed by the FULL 40-character commit SHA.** `release.yml` tags images with
`type=sha,format=long`; the abbreviated form does not exist in GHCR and fails `ImagePullBackOff`.
Derive it rather than typing it:

```bash
cd ~/ticketing-system
TAG=sha-$(git rev-parse HEAD)
echo "$TAG"          # sha-d74a643dd9a8bf2718cd831b367c09caf766050c
```

Confirm that commit's **Release** workflow run is green first — the images exist only once it has
pushed them. Note that a red Release run does not by itself mean the image is missing: the
build-provenance attestation step fails permanently on this user-owned private repo *after* the
image has been scanned and pushed. Check whether the push step itself succeeded.

**So: run nothing in this section.** Carry `$TAG` forward; §7 is where it is applied. `$TAG` is a
shell variable, so re-export it in any new SSH session — a lost `$TAG` silently produces an
invalid image reference.

---

## 5. Add the config keys

48 keys are missing. Checking all 48 against every service's `application.yaml` shows each one is
either **not referenced at all** (compose-only, or owned by a service outside this repo) or
referenced **only** as `${VAR:default}`. **Neither shape can fail a boot**, so a missing key here
costs you the default's behaviour, never a crashloop. The payment rails and federation additionally
fail safe when blank — a clean 503, not an odd error.

Re-run that check for your own diff rather than trusting this list:

```bash
grep -rhoE '\$\{ZIMSWITCH_ENTITY_ID(:[^}]*)?\}' --include=application.yaml .
```

A hit with no `:` means that key has no default and its absence WILL fail the boot.

**This section is therefore optional.** The 2026-09-11 production upgrade skipped it entirely and
nothing misbehaved. Add keys only where you want to change behaviour away from the default.

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
(−7). This must happen **before** the rollouts, and before the pinning in §7 — apply overwrites the
image field with `:latest`.

Applying also creates marketplace-service, which then starts on its own. You do not roll it later;
see §7.5.

```bash
cd ~/ticketing-system && git log --oneline -1      # confirm d74a643d
kubectl apply -f deploy/k8s/
kubectl -n ticketing get deploy
```

---

## 7. Roll services one at a time

**Order matters.** Each step ends with a health check; do not start the next until the current one
is green. If any service fails, go to §9 before continuing.

Each step below uses `kubectl set image` with `$TAG` from §4, which pins and rolls in one action.
Re-export `TAG` if you have opened a new shell since §4.

### 7.1 discovery-server — usually skip

Roll this **only if discovery-server's own source changed**. For the 13add2eb → d74a643d upgrade
only its Dockerfile base image and pom dependencies moved, so it was deliberately left alone:
restarting the registry churns every service's registration for no functional gain.

```bash
# check first — if this shows only Dockerfile/pom, skip the section
git diff --stat <previous-sha> HEAD -- discovery-server/

# only if there are real source changes:
kubectl -n ticketing set image deployment/discovery-server "*=ghcr.io/mpofuslim/discovery-server:$TAG"
kubectl -n ticketing rollout status deployment/discovery-server --timeout=5m
kubectl -n ticketing set image deployment/discovery-server-2 "*=ghcr.io/mpofuslim/discovery-server:$TAG"
kubectl -n ticketing rollout status deployment/discovery-server-2 --timeout=5m
```

### 7.2 user-service — runs V35, V36, V37

V35 is the roles-and-permissions migration; V37 adds notifications.

```bash
kubectl -n ticketing set image deployment/user-service "*=ghcr.io/mpofuslim/user-service:$TAG"
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
kubectl -n ticketing set image deployment/payment-service "*=ghcr.io/mpofuslim/payment-service:$TAG"
kubectl -n ticketing rollout status deployment/payment-service --timeout=10m

kubectl -n ticketing exec "$PGPOD" -- psql -U postgres -d payment_service \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 4;"

# V12 backfilled every row, then set NOT NULL — belt and braces
kubectl -n ticketing exec "$PGPOD" -- psql -U postgres -d payment_service \
  -c "SELECT count(*) AS null_order_refs FROM payment WHERE order_ref IS NULL;"
```

`null_order_refs` must be `0`. (V12 ends with `ALTER COLUMN order_ref SET NOT NULL`, so a
`success = t` row already proves the backfill covered every row — this check cannot fail
independently. Keep it as a cheap confirmation, not as the real gate.)

### 7.4 the remaining services

```bash
# event-service BEFORE seat-service: seat's oversell guard reads totalCapacity from it
for svc in event-service seat-service booking-service; do
  kubectl -n ticketing set image deployment/$svc "*=ghcr.io/mpofuslim/$svc:$TAG"
  kubectl -n ticketing rollout status deployment/$svc --timeout=10m
done

kubectl -n ticketing exec "$PGPOD" -- psql -U postgres -d booking_service \
  -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"
kubectl -n ticketing exec "$PGPOD" -- psql -U postgres -d event_service \
  -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 2;"
```

### 7.5 marketplace-service — new; started by §6, not rolled here

**Do not `set image` this one.** It is built from a different repository
(`MpofuSlim/market-place`), so `$TAG` is not its commit and that tag does not exist for it. §6's
apply created the deployment and it starts on `:latest` by itself — this step is a check, not an
action.

```bash
kubectl -n ticketing rollout status deployment/marketplace-service --timeout=10m
kubectl -n ticketing logs -l app=marketplace-service --tail=50
```

A healthy first boot logs `Secrets guard passed`, registers with Eureka, and ends with
`Started MarketplaceServiceApplication`. If it crashloops, the usual cause is the database from §3
missing; `ImagePullBackOff` instead means no image has ever been published from its own repo.

### 7.6 api-gateway — last

Restart the edge only once everything behind it is healthy.

```bash
kubectl -n ticketing set image deployment/api-gateway "*=ghcr.io/mpofuslim/api-gateway:$TAG"
kubectl -n ticketing rollout status deployment/api-gateway --timeout=10m
```

---

## 8. Verify

```bash
kubectl -n ticketing get pods            # every pod Running, RESTARTS 0
kubectl -n ticketing get deploy          # every deployment fully available
```

Confirm every service is on the tag you intended:

```bash
kubectl -n ticketing get deploy \
  -o custom-columns='NAME:.metadata.name,IMAGE:.spec.template.spec.containers[0].image'
```

**You do not need the public edge hostname to test routing.** The api-gateway Service is a NodePort
on **30080**, so curl it straight from the box. The gateway's own routes are un-prefixed — the
`/foundry` public prefix is stripped by nginx at the edge, and `PUBLIC_API_PREFIX` only feeds the
Swagger doc URLs — so use bare paths:

```bash
# a route that exists ONLY in the new config: 401 proves the new route table is live, 404 means old
curl -s -o /dev/null -w "notifications:%{http_code}\n" http://localhost:30080/notifications
# control: worked before and after
curl -s -o /dev/null -w "events:%{http_code}\n"        http://localhost:30080/events
```

Through the public edge:

- An unauthenticated call to a secured endpoint returns **401**, not 404 (new image present, routed).
- Sign in to the console as an admin — confirm no 403s on pages that worked before (the §7.2 note).
- Create a test booking end to end; confirm the InnBucks 2D-code rail still issues a code.
- Scan a ticket for an event running **today** → allowed; one for another day → `WRONG_EVENT_DAY`.
- A pure `TEAM_MEMBER` logs in with a password only, no TOTP prompt.

**Known-off by design after this upgrade:** card (ZimSwitch) and EcoCash payments return 503 until
credentials are provisioned; `/auth/exchange` returns 404 until federation is provisioned; the
Oradian transfer/withdraw/transactions endpoints are gone for good.

### 8.1 Return to `:latest` once you are satisfied

The SHA pins are for the upgrade window. Leaving them means the manifests (`:latest`) and the live
deployments disagree, so the next routine `kubectl apply -f deploy/k8s/` silently reverts every
service. Once prod is behaving, put it back in step with the standard deploy routine in `CLAUDE.md`:

```bash
for s in user-service payment-service event-service seat-service booking-service api-gateway; do
  kubectl -n ticketing set image deployment/$s "*=ghcr.io/mpofuslim/$s:latest"
done
```

`:latest` and your `$TAG` are the same image as long as nothing has merged since, so this is a
restart rather than a version change — but it **is** six rolling updates at once on a single node,
each briefly running old and new pods together. Watch for memory pressure, and re-run the §8 checks
afterwards:

```bash
for s in user-service payment-service event-service seat-service booking-service api-gateway; do
  kubectl -n ticketing rollout status deployment/$s --timeout=10m
done
kubectl -n ticketing get pods
```

---

## 9. Rollback

There is no rollback workflow, and **Flyway migrations do not roll back**. Reverting images while
leaving the new schema in place is usually survivable (the old code ignores new columns) but is not
guaranteed — V12's `DROP INDEX uq_payment_active_booking` removes a constraint the old
payment-service relies on.

**Images only** (schema stays migrated). The rollback tag needs the **full 40-character SHA** for
the same reason as §4 — an abbreviated tag does not exist in GHCR and will leave you staring at
`ImagePullBackOff` during an incident. Derive it instead of typing it:

```bash
cd ~/ticketing-system
OLD=sha-$(git rev-parse 13add2eb)     # sha-13add2eb4a532eb7174c6fb4af91b23b5804dd09
kubectl -n ticketing set image deployment/<svc> "*=ghcr.io/mpofuslim/<svc>:$OLD"
kubectl -n ticketing rollout status deployment/<svc> --timeout=10m
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

> **This was skipped on 2026-09-11 and the upgrade still succeeded.** That is not evidence the
> rehearsal is unnecessary — it is evidence that the §2 pre-flight caught the one question that
> mattered for *this* diff. A future upgrade whose risky migration is not a single checkable
> uniqueness constraint will not have that shortcut available. Keep this section.
