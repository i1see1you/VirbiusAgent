#!/bin/sh
# Generate a self-signed CA + MySQL server certificate for local/prod-like
# Docker Compose deployments (P0 datastore TLS baseline).
#
# Output (in $CERT_DIR, default /certs):
#   ca.pem / ca-key.pem          — CA; ca.pem is mounted into clients
#   server-cert.pem/server-key.pem — server cert with SAN DNS:mysql,localhost + 127.0.0.1
#
# Idempotent: existing certificates are kept so container restarts don't rotate
# the CA. The CA key is generated with mode 600 and must never be committed or
# copied into client images. Certificate validity: 825 days (rotation runbook:
# delete server-* and re-run, then roll clients; see DEPLOYMENT.md §TLS).
set -eu
CERT_DIR="${CERT_DIR:-/certs}"
DAYS="${CERT_DAYS:-825}"
mkdir -p "$CERT_DIR"
cd "$CERT_DIR"

if [ -s ca.pem ] && [ -s ca-key.pem ] && [ -s server-cert.pem ] && [ -s server-key.pem ]; then
  echo "certs: existing certificates found in $CERT_DIR, keeping them"
  exit 0
fi

umask 077
if [ ! -s ca-key.pem ]; then
  openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout ca-key.pem -out ca.pem -days "$DAYS" \
    -subj "/CN=Virbius Local CA" >/dev/null 2>&1
fi

if [ ! -s server-key.pem ]; then
  openssl req -newkey rsa:2048 -nodes \
    -keyout server-key.pem -out server.csr \
    -subj "/CN=mysql" >/dev/null 2>&1
  printf 'subjectAltName=DNS:mysql,DNS:localhost,IP:127.0.0.1\n' > server-ext.cnf
  openssl x509 -req -in server.csr \
    -CA ca.pem -CAkey ca-key.pem -CAcreateserial \
    -out server-cert.pem -days "$DAYS" \
    -extfile server-ext.cnf >/dev/null 2>&1
  rm -f server.csr server-ext.cnf
fi

# Server key must stay readable by the mysql daemon user in the container.
chmod 600 ca-key.pem
chmod 644 ca.pem server-cert.pem
chmod 644 server-key.pem
echo "certs: generated CA + server certificate in $CERT_DIR (validity ${DAYS}d)"
