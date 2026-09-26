#!/usr/bin/env bash
# Secret scan with gitleaks (https://github.com/gitleaks/gitleaks).
#
#   scripts/secret_scan.sh            # the whole git history (what CI runs)
#   scripts/secret_scan.sh --staged   # only what is staged for the next commit (the pre-commit hook)
#
# Uses a local `gitleaks` binary when there is one, else the pinned Docker image, so nobody has to
# install anything. Exits non-zero when a secret is found.
set -euo pipefail

GITLEAKS_IMAGE="ghcr.io/gitleaks/gitleaks:v8.28.0"
root="$(git rev-parse --show-toplevel)"

args=(git --no-banner --redact -v)
if [[ "${1:-}" == "--staged" ]]; then
  args+=(--pre-commit --staged)
fi

if command -v gitleaks >/dev/null 2>&1; then
  exec gitleaks "${args[@]}" "$root"
elif docker info >/dev/null 2>&1; then
  exec docker run --rm -v "$root":/repo "$GITLEAKS_IMAGE" "${args[@]}" /repo
else
  echo "secret scan: neither gitleaks nor a running Docker daemon is available." >&2
  echo "Install gitleaks (brew install gitleaks) or start Docker, then retry." >&2
  exit 1
fi
