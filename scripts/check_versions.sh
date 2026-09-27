#!/usr/bin/env bash
#
# One version for a release (ADR-0050): the Gradle build, the dashboard's package.json and release
# constant, and every compose image default must agree. With a tag argument (v0.1.0-rc), they must
# also match it — the release workflow runs this before publishing anything.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

gradle="$(sed -n 's/^ *version = "\([^"]*\)".*/\1/p' build.gradle.kts)"
package="$(sed -n 's/^ *"version": "\([^"]*\)".*/\1/p' dashboard/package.json | head -n 1)"
release="$(sed -n 's/^export const PGLENS_VERSION = "\([^"]*\)";/\1/p' dashboard/src/lib/release.ts)"

status=0
check() { # name value
  if [ "$2" != "$gradle" ]; then
    echo "version mismatch: $1 is '$2', build.gradle.kts is '$gradle'" >&2
    status=1
  fi
}
check "dashboard/package.json" "$package"
check "dashboard/src/lib/release.ts" "$release"
while read -r compose; do
  check "a compose image default" "$compose"
done < <(grep -o 'PGLENS_VERSION:-[^}]*' deploy/compose/docker-compose.yml | cut -d- -f2-)

if [ "$#" -ge 1 ] && [ "$1" != "v$gradle" ]; then
  echo "tag '$1' doesn't match the build's version 'v$gradle'" >&2
  status=1
fi
[ "$status" -eq 0 ] && echo "versions: all $gradle"
exit "$status"
