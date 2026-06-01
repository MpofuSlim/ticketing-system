#!/usr/bin/env bash
# Run the ticketing stack with Docker Compose, pulling prebuilt images from GHCR
# (the same images CI publishes on each master merge). This avoids building on
# the box, so it needs neither a recent buildx nor build-time RAM/CPU.
#
# One-time prerequisite (GHCR packages are private):
#     docker login ghcr.io -u <your-github-username>
#     # paste a GitHub Personal Access Token with the `read:packages` scope
#
# Run from the repo root (docker-compose.yml + a filled .env must be present).
#
#   ./run-stack.sh up           pull :latest + start full stack (infra + 8 services, ~8 GB)
#   ./run-stack.sh slim         start only discovery + user + event + gateway
#                               (+ postgres/redis, no kafka) -- login + events, ~4 GB
#   ./run-stack.sh build        build images locally instead of pulling (needs buildx >= 0.17)
#   ./run-stack.sh down         stop + remove containers (named volumes/data are kept)
#   ./run-stack.sh restart      down, then pull + up
#   ./run-stack.sh ps           status
#   ./run-stack.sh logs [svc]   follow logs (all, or one service)
#
# Pin a specific build instead of latest:  IMAGE_TAG=sha-<commit> ./run-stack.sh up
set -euo pipefail
export IMAGE_TAG="${IMAGE_TAG:-latest}"
SLIM="discovery-server user-service event-service api-gateway"

[ -f docker-compose.yml ] || { echo "Run this from the repo root (no docker-compose.yml here)."; exit 1; }
[ -f .env ]              || { echo "No .env found -- copy .env.example to .env and fill it in first."; exit 1; }

case "${1:-up}" in
  up)
    docker compose pull
    docker compose up -d --no-build      # --no-build: pull images, never invoke buildx
    docker compose ps
    echo "Gateway -> http://localhost:18080   (./run-stack.sh logs <service> to tail)"
    ;;
  slim)
    # Pull the app images first; without this, `up` would BUILD them (the
    # compose services carry a build: section), which needs buildx >= 0.17.
    docker compose pull $SLIM
    docker compose up -d --no-build $SLIM   # postgres/redis deps pulled automatically
    docker compose ps
    echo "Slim stack up (discovery, user, event, gateway). Gateway -> http://localhost:18080"
    ;;
  build)
    docker compose up -d --build
    docker compose ps
    ;;
  down)    docker compose down ;;
  restart) docker compose down; docker compose pull; docker compose up -d --no-build; docker compose ps ;;
  ps)      docker compose ps ;;
  logs)    shift || true; docker compose logs -f "$@" ;;
  *) echo "usage: $0 {up|slim|build|down|restart|ps|logs [service]}"; exit 1 ;;
esac
