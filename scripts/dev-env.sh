#!/usr/bin/env bash
# Source this (don't execute it) to load .env into your current shell so
# locally-launched services (mvn spring-boot:run, IDE, etc.) see the same
# DB_PASSWORD / JWT_SECRET / etc. that docker-compose injects.
#
# Usage:
#     source scripts/dev-env.sh
#     ./mvnw -pl event-service spring-boot:run
#
# IMPORTANT: this parses .env line-by-line and assigns values LITERALLY,
# matching how docker-compose reads .env. Do NOT use `set -a; . .env;
# set +a` — that runs the file as Bash, so a value like `pa$$word` would
# get $$ expanded to the shell's PID.

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

while IFS= read -r _line || [ -n "$_line" ]; do
  # Strip leading whitespace; skip blanks and comments.
  _line="${_line#"${_line%%[![:space:]]*}"}"
  [ -z "$_line" ] && continue
  case "$_line" in \#*) continue ;; esac

  # Split on the first '='. Lines without '=' are ignored.
  case "$_line" in *=*) ;; *) continue ;; esac
  _key="${_line%%=*}"
  _val="${_line#*=}"

  # Validate key shape (POSIX-ish identifier); skip otherwise.
  if ! [[ "$_key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
    continue
  fi

  # Strip a single matching pair of surrounding quotes, if present.
  case "$_val" in
    \"*\") _val="${_val#\"}"; _val="${_val%\"}" ;;
    \'*\') _val="${_val#\'}"; _val="${_val%\'}" ;;
  esac

  export "$_key=$_val"
done < "$_env_file"

# Map docker-compose's POSTGRES_USER/POSTGRES_PASSWORD onto the DB_USERNAME/
# DB_PASSWORD names that each service's application.yaml reads.
export DB_USERNAME="${DB_USERNAME:-${POSTGRES_USER:-postgres}}"
export DB_PASSWORD="${DB_PASSWORD:-${POSTGRES_PASSWORD:-}}"

echo "loaded $_env_file (DB_USERNAME=$DB_USERNAME, DB_PASSWORD=***)"
unset _env_file _line _key _val
