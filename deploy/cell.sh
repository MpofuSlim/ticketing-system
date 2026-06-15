#!/usr/bin/env bash
# deploy/cell.sh — multi-cell deploy wrapper. Step 5 of the multi-cell roadmap.
#
# Usage:
#   deploy/cell.sh <cell> <command> [args...]
#
# Examples:
#   deploy/cell.sh zw up                    # bring up the Zimbabwe cell
#   deploy/cell.sh zw status                # show service health
#   deploy/cell.sh zw logs payment-service  # tail one service's logs
#   deploy/cell.sh zw restart user-service  # restart one service
#   deploy/cell.sh zw pull                  # fetch the IMAGE_TAG'd images
#   deploy/cell.sh zw down                  # stop the cell (data volumes kept)
#
# Per-cell layered config:
#   deploy/cells/cell.<cell>.env        — committed, non-secret defaults
#   deploy/cells/cell.<cell>.local.env  — gitignored, real secrets (optional;
#                                          if present, overrides the committed file)
#
# Designed so a future migration to k8s + Helm reuses the same per-cell value
# files essentially unchanged — only the rendering layer (this script vs
# `helm install`) differs.

set -euo pipefail

usage() {
    cat >&2 <<'EOF'
Usage: deploy/cell.sh <cell> <command> [args...]

Commands:
  up         start the cell (detached, no rebuild)
  down       stop the cell (volumes kept; add `-v` to wipe data)
  pull       fetch the IMAGE_TAG'd images from GHCR
  restart    restart the cell (or one named service)
  status|ps  show service status
  logs       tail logs (defaults to all services; pass one to scope)
  config     render the merged compose config (debug only)

Cells available:
EOF
    if compgen -G "$(dirname "$0")/cells/cell.*.env" >/dev/null 2>&1; then
        for f in "$(dirname "$0")"/cells/cell.*.env; do
            local name
            name=$(basename "$f" .env)
            name=${name#cell.}
            [[ "$name" == "example" ]] && continue
            echo "  - ${name}" >&2
        done
    else
        echo "  (none — copy deploy/cells/cell.example.env to deploy/cells/cell.<iso>.env)" >&2
    fi
    exit 2
}

CELL="${1:-}"; CMD="${2:-}"
[[ -z "$CELL" || -z "$CMD" ]] && usage
shift 2

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$REPO_DIR/docker-compose.yml"
CELL_ENV="$SCRIPT_DIR/cells/cell.${CELL}.env"
CELL_LOCAL_ENV="$SCRIPT_DIR/cells/cell.${CELL}.local.env"

if [[ ! -f "$CELL_ENV" ]]; then
    echo "no such cell: ${CELL}" >&2
    echo "expected env file at: ${CELL_ENV}" >&2
    echo "(copy deploy/cells/cell.example.env and rename to cell.${CELL}.env)" >&2
    exit 2
fi
if [[ ! -f "$COMPOSE_FILE" ]]; then
    echo "compose file not found at: ${COMPOSE_FILE}" >&2
    exit 1
fi

# Compose's --env-file flag is repeatable as of Compose v2; the later file
# wins for duplicate keys. So the gitignored local file's real secrets
# override the REPLACE_ME placeholders in the committed file.
COMPOSE_ARGS=(--env-file "$CELL_ENV")
if [[ -f "$CELL_LOCAL_ENV" ]]; then
    COMPOSE_ARGS+=(--env-file "$CELL_LOCAL_ENV")
else
    # Soft warning, not a hard fail — a CI dry-run might intentionally use
    # only the committed file. The :? guards in docker-compose.yml will
    # fail on actually-required secrets if they're still placeholders.
    echo "[cell.sh] note: no ${CELL_LOCAL_ENV} found — using committed defaults only" >&2
fi

# `payments` profile is used by the existing scripts/deploy.sh; keep parity
# so cell.sh brings up the same set of services.
PROFILE_ARGS=(--profile payments)

cd "$REPO_DIR"

case "$CMD" in
    up)
        # Ensure the shared docker network exists for sibling stacks
        # (e.g. innbucks-core-gateway, OradianMiddleware).
        docker network inspect innbucks-shared >/dev/null 2>&1 \
            || docker network create innbucks-shared
        # --no-build: pull images, don't compile locally. --wait: block until
        # every service reports healthy. --remove-orphans: drop containers that
        # are no longer in the compose file (e.g. retired services).
        exec docker compose -f "$COMPOSE_FILE" "${COMPOSE_ARGS[@]}" \
            "${PROFILE_ARGS[@]}" up -d --no-build --remove-orphans --wait "$@"
        ;;
    down)
        exec docker compose -f "$COMPOSE_FILE" "${COMPOSE_ARGS[@]}" \
            "${PROFILE_ARGS[@]}" down "$@"
        ;;
    pull)
        exec docker compose -f "$COMPOSE_FILE" "${COMPOSE_ARGS[@]}" \
            "${PROFILE_ARGS[@]}" pull "$@"
        ;;
    restart)
        exec docker compose -f "$COMPOSE_FILE" "${COMPOSE_ARGS[@]}" \
            "${PROFILE_ARGS[@]}" restart "$@"
        ;;
    status|ps)
        exec docker compose -f "$COMPOSE_FILE" "${COMPOSE_ARGS[@]}" \
            "${PROFILE_ARGS[@]}" ps "$@"
        ;;
    logs)
        exec docker compose -f "$COMPOSE_FILE" "${COMPOSE_ARGS[@]}" \
            "${PROFILE_ARGS[@]}" logs -f --tail=200 "$@"
        ;;
    config)
        exec docker compose -f "$COMPOSE_FILE" "${COMPOSE_ARGS[@]}" \
            "${PROFILE_ARGS[@]}" config "$@"
        ;;
    *)
        echo "unknown command: ${CMD}" >&2
        usage
        ;;
esac
