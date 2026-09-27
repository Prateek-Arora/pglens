#!/bin/sh
# Dev-only TLS material for the agent <-> server gRPC channel (ADR-0044), run by the compose `certs`
# one-shot service into the `grpc-certs` volume:
#   ca/ca.pem                  the dev CA certificate — the agent trusts it (mounted alone)
#   server/server.pem + .key   the server's certificate + key. Its names: server (the compose
#                              network), localhost + 127.0.0.1, host.docker.internal (an agent
#                              container on this machine), and PGLENS_TLS_EXTRA_SANS
#   server/sans.txt            the names the certificate was issued for
# The CA's private key is thrown away after signing, so nothing else can ever be signed with it.
# Idempotent: certificates valid for 30+ more days and issued for the same names are kept. When the
# names change, a new CA and certificate are made (the old CA key is gone), so an agent on another
# machine needs the new ca.pem. For a real deployment, give the server your own certificate
# (PGLENS_GRPC_TLS_CERT / PGLENS_GRPC_TLS_KEY) instead.
set -eu

OUT="${CERTS_DIR:-/certs}"
SERVER_UID="${SERVER_UID:-10001}" # the pglens user in the server image
SANS="DNS:server,DNS:localhost,IP:127.0.0.1,DNS:host.docker.internal"
if [ -n "${PGLENS_TLS_EXTRA_SANS:-}" ]; then # e.g. DNS:pglens.internal,IP:10.0.0.5
  SANS="$SANS,$PGLENS_TLS_EXTRA_SANS"
fi

if [ -s "$OUT/server/server.pem" ] && [ -s "$OUT/ca/ca.pem" ] \
  && [ "$(cat "$OUT/server/sans.txt" 2>/dev/null)" = "$SANS" ] \
  && openssl x509 -checkend 2592000 -noout -in "$OUT/server/server.pem" >/dev/null; then
  echo "certs: dev certificates present, valid for 30+ days, for the same names — nothing to do"
  exit 0
fi

mkdir -p "$OUT/ca" "$OUT/server"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$tmp/ca.key"
openssl req -x509 -new -key "$tmp/ca.key" -sha256 -days 825 -subj "/CN=PgLens dev CA" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -out "$OUT/ca/ca.pem"

# genpkey writes PKCS#8 ("BEGIN PRIVATE KEY"), the format grpc-java's TlsServerCredentials reads.
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$OUT/server/server.key"
openssl req -new -key "$OUT/server/server.key" -subj "/CN=server" -out "$tmp/server.csr"
printf 'subjectAltName=%s\nextendedKeyUsage=serverAuth\nbasicConstraints=CA:FALSE\n' "$SANS" \
  >"$tmp/server.ext"
openssl x509 -req -in "$tmp/server.csr" -CA "$OUT/ca/ca.pem" -CAkey "$tmp/ca.key" \
  -CAcreateserial -CAserial "$tmp/ca.srl" -days 397 -sha256 -extfile "$tmp/server.ext" \
  -out "$OUT/server/server.pem"

printf '%s' "$SANS" >"$OUT/server/sans.txt"
chmod 0444 "$OUT/ca/ca.pem" "$OUT/server/server.pem" "$OUT/server/sans.txt"
chown "$SERVER_UID" "$OUT/server/server.key"
chmod 0400 "$OUT/server/server.key"
echo "certs: new dev CA + server certificate (SANs: $SANS)"
