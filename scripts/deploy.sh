#!/usr/bin/env bash
# Manual deploy / rollback helper. Used by the GitHub Actions Release
# workflow AND directly on the EC2 over SSH when you need to roll back
# fast without waiting for CI.
#
# Usage:
#   ~/superApp/scripts/deploy.sh sha-<40-char-commit>
#   ~/superApp/scripts/deploy.sh latest         # NOT for prod, here for dev
#
# Prerequisite: `docker login ghcr.io` must already have succeeded in
# the current shell — the Actions workflow does this automatically;
# for a manual rollback, log in once with a fine-grained PAT that has
# `read:packages` on this org.

set -euo pipefail

IMAGE_TAG="${1:?usage: $(basename "$0") <image-tag>  (e.g. sha-abc123... or latest)}"
COMPOSE_FILE="${COMPOSE_FILE:-$HOME/superApp/docker-compose.yml}"
PROJECT_DIR="$(dirname "$COMPOSE_FILE")"

if [ ! -f "$COMPOSE_FILE" ]; then
  echo "compose file not found at $COMPOSE_FILE" >&2
  exit 1
fi
if [ ! -f "$PROJECT_DIR/.env" ]; then
  echo "$PROJECT_DIR/.env missing — refusing to start (would otherwise blow up on undeclared env vars)" >&2
  exit 1
fi

cd "$PROJECT_DIR"

docker network inspect innbucks-shared >/dev/null 2>&1 \
  || docker network create innbucks-shared

export IMAGE_TAG
echo "Pulling images at tag $IMAGE_TAG ..."
docker compose --profile payments pull

echo "Restarting stack ..."
# --no-build: this script MUST NOT rebuild on the EC2. If a pull failed,
# fail loudly here instead of silently falling back to a stale build.
# --wait: block until every service reports healthy.
docker compose --profile payments up -d --no-build --remove-orphans --wait

echo
echo "Deployed. Final state:"
docker compose --profile payments ps
