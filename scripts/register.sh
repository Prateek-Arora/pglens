#!/usr/bin/env bash
# `make register`: registers the demo database through the HTTP API (B10, ADR-0044) — or rotates
# its token if it is already registered — writes the agent token into deploy/compose/.env and
# recreates the agent so it picks the token up. Secrets never reach argv (visible in `ps`) or the
# terminal: the password and bearer token go to curl on stdin.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$ROOT/deploy/compose/.env"
COMPOSE=(docker compose -f "$ROOT/deploy/compose/docker-compose.yml")

bash "$ROOT/scripts/dev_env.sh"

# Like compose: the shell environment wins over .env.
from_env_file() { grep "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2- || true; }
DB_NAME="${PGLENS_DB_NAME:-$(from_env_file PGLENS_DB_NAME)}"
DB_NAME="${DB_NAME:-demo}"
PORT="${PGLENS_HTTP_PORT:-$(from_env_file PGLENS_HTTP_PORT)}"
API="http://127.0.0.1:${PORT:-8080}/api/v1"
ADMIN="${PGLENS_ADMIN_USERNAME:-$(from_env_file PGLENS_ADMIN_USERNAME)}"
ADMIN="${ADMIN:-admin}"
PASSWORD="${PGLENS_ADMIN_PASSWORD:-$(from_env_file PGLENS_ADMIN_PASSWORD)}"

json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
json_field() { sed -n "s/.*\"$1\":\"\\([^\"]*\\)\".*/\\1/p"; }

# Prints the response body, then the HTTP status on its own last line.
api() { # method path [json-body]; the bearer token comes from $SESSION via curl's stdin config
  local args=(-sS -X "$1" "$API$2" -w '\n%{http_code}' --config -)
  if [ $# -ge 3 ]; then args+=(-H 'Content-Type: application/json' --data "$3"); fi
  printf 'header = "Authorization: Bearer %s"\n' "$SESSION" | curl "${args[@]}"
}
status_of() { tail -1 <<<"$1"; }
body_of() { sed '$d' <<<"$1"; }

login="$(printf '{"username":"%s","password":"%s"}' "$(json_escape "$ADMIN")" \
  "$(json_escape "$PASSWORD")" \
  | curl -sS --retry 10 --retry-connrefused --retry-delay 2 -X POST "$API/auth/login" \
    -H 'Content-Type: application/json' --data @- -w '\n%{http_code}')" || {
  echo "register: the server's HTTP API is not reachable at $API — run \`make up\` first" >&2
  exit 1
}
if [ "$(status_of "$login")" != 200 ]; then
  echo "register: login as '$ADMIN' failed (HTTP $(status_of "$login"))." >&2
  echo "  The admin was probably created before deploy/compose/.env had PGLENS_ADMIN_PASSWORD:" >&2
  echo "  use the password logged on first start (\`docker compose logs server\`) via" >&2
  echo "  PGLENS_ADMIN_PASSWORD=… make register, or reset the local demo stack with \`make clean\`." >&2
  exit 1
fi
SESSION="$(body_of "$login" | json_field token)"
trap 'api POST /auth/logout >/dev/null 2>&1 || true' EXIT

created="$(api POST /databases "{\"name\":\"$DB_NAME\"}")"
case "$(status_of "$created")" in
  201) action="registered" ;;
  409)
    created="$(api POST "/databases/$DB_NAME/token")"
    action="already registered — rotated its agent token"
    ;;
  *)
    echo "register: HTTP $(status_of "$created"): $(body_of "$created")" >&2
    exit 1
    ;;
esac
TOKEN="$(body_of "$created" | json_field agentToken)"
[ -n "$TOKEN" ] || {
  echo "register: HTTP $(status_of "$created") without an agent token" >&2
  exit 1
}

umask 077
{
  grep -v '^PGLENS_AGENT_TOKEN=' "$ENV_FILE" || true
  printf 'PGLENS_AGENT_TOKEN=%s\n' "$TOKEN"
} >"$ENV_FILE.tmp"
mv "$ENV_FILE.tmp" "$ENV_FILE"

echo "register: '$DB_NAME' $action; agent token saved to deploy/compose/.env (git-ignored)"
"${COMPOSE[@]}" up -d --no-deps agent
