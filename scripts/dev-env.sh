#!/usr/bin/env bash
# Source this (don't execute it) to load .env into your current shell so
# locally-launched services (mvn spring-boot:run, IDE, etc.) see the same
# DB_PASSWORD / JWT_SECRET / etc. that docker-compose injects.
#
# Usage:
#     source scripts/dev-env.sh
#     ./mvnw -pl event-service spring-boot:run
#
# `set -a` exports every variable assigned until `set +a`, which is what
# turns plain `KEY=value` lines in .env into real environment variables.

if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
  echo "scripts/dev-env.sh must be sourced, not executed:" >&2
  echo "    source scripts/dev-env.sh" >&2
  exit 1
fi

_env_file="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/.env"

if [ ! -f "$_env_file" ]; then
  echo "scripts/dev-env.sh: $_env_file not found — copy .env.example to .env first" >&2
  unset _env_file
  return 1
fi

set -a
# shellcheck disable=SC1090
. "$_env_file"
set +a

# Map docker-compose's POSTGRES_USER/POSTGRES_PASSWORD onto the DB_USERNAME/
# DB_PASSWORD names that each service's application.yaml reads.
export DB_USERNAME="${DB_USERNAME:-${POSTGRES_USER:-postgres}}"
export DB_PASSWORD="${DB_PASSWORD:-${POSTGRES_PASSWORD:-}}"

echo "loaded $_env_file (DB_USERNAME=$DB_USERNAME, DB_PASSWORD=***)"
unset _env_file
