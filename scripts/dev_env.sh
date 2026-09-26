#!/usr/bin/env bash
# Makes sure deploy/compose/.env (git-ignored, mode 600) holds a generated PGLENS_ADMIN_PASSWORD
# before the server first starts, so the first admin gets a password `make register` can use
# (ADR-0044). Idempotent; never prints the password. Run by `make up`.
set -euo pipefail

ENV_FILE="$(cd "$(dirname "$0")/.." && pwd)/deploy/compose/.env"
umask 077
touch "$ENV_FILE"

if ! grep -q '^PGLENS_ADMIN_PASSWORD=.' "$ENV_FILE"; then
  password="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32 || true)"
  grep -v '^PGLENS_ADMIN_PASSWORD=' "$ENV_FILE" >"$ENV_FILE.tmp" || true
  printf 'PGLENS_ADMIN_PASSWORD=%s\n' "$password" >>"$ENV_FILE.tmp"
  mv "$ENV_FILE.tmp" "$ENV_FILE"
  echo "dev env: generated an admin password into deploy/compose/.env (git-ignored)"
fi
