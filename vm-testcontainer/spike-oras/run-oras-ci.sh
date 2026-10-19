#!/usr/bin/env bash
# R13 spike harness (GitHub runner, rootless podman). Starts two registry:2 instances with htpasswd auth
# (TLS with a self-signed CA on :5443, plain HTTP on :5000), pushes artifacts with the official oras CLI,
# then runs the Java tests in three credential scenarios. Results: target/{http,oras}-results.txt
set -euo pipefail
cd "$(dirname "$0")"
W=$PWD/work; mkdir -p "$W"/{certs,auth,expect}
U=vmtc P=s3cret FAIL=0

# --- official oras CLI (pinned to the latest release at run time, checksum-verified) ---
VER=$(curl -fsSL -H "Authorization: Bearer ${GITHUB_TOKEN:-}" https://api.github.com/repos/oras-project/oras/releases/latest | jq -r .tag_name | sed 's/^v//')
curl -fsSL -o "$W/oras.tgz" "https://github.com/oras-project/oras/releases/download/v$VER/oras_${VER}_linux_amd64.tar.gz"
curl -fsSL -o "$W/checksums.txt" "https://github.com/oras-project/oras/releases/download/v$VER/oras_${VER}_checksums.txt"
(cd "$W" && grep -E "  oras_${VER}_linux_amd64\.tar\.gz$" checksums.txt | sed "s|oras_${VER}_linux_amd64.tar.gz|oras.tgz|" | sha256sum -c -)
tar -xzf "$W/oras.tgz" -C "$W" oras
ORAS="$W/oras --registry-config $W/oras-config.json"   # keep oras' own login out of ~/.docker/config.json
"$W/oras" version | head -1

# --- registries ---
# TLS registry is addressed as registry.test (oras CLI forces plain HTTP for "localhost"; real registries have names)
grep -q " registry.test" /etc/hosts || echo "127.0.0.1 registry.test" | sudo tee -a /etc/hosts >/dev/null
T=registry.test:5443
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$W/certs/key.pem" -out "$W/certs/cert.pem" -days 2 \
  -subj /CN=registry.test -addext "subjectAltName=DNS:registry.test,DNS:localhost,IP:127.0.0.1" 2>/dev/null
podman run --rm --entrypoint htpasswd docker.io/library/httpd:2 -Bbn "$U" "$P" > "$W/auth/htpasswd"
AUTH=(-e REGISTRY_AUTH=htpasswd -e REGISTRY_AUTH_HTPASSWD_REALM=spike -e REGISTRY_AUTH_HTPASSWD_PATH=/auth/htpasswd)
podman run -d --name reg-tls -p 5443:5000 -v "$W/certs:/certs:Z" -v "$W/auth:/auth:Z" "${AUTH[@]}" \
  -e REGISTRY_HTTP_TLS_CERTIFICATE=/certs/cert.pem -e REGISTRY_HTTP_TLS_KEY=/certs/key.pem docker.io/library/registry:2 >/dev/null
podman run -d --name reg-plain -p 5000:5000 -v "$W/auth:/auth:Z" "${AUTH[@]}" docker.io/library/registry:2 >/dev/null
for i in $(seq 1 30); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' --cacert "$W/certs/cert.pem" https://$T/v2/)" = 401 ] && \
  [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:5000/v2/)" = 401 ] && break; sleep 1; done
podman image inspect docker.io/library/registry:2 --format 'registry image {{.Id}} {{index .Labels "org.opencontainers.image.version"}}' || true

# --- artifacts (pushed from inside $W/expect so layer titles are bare file names) ---
cd "$W/expect"
head -c 8388608 /dev/urandom > disk.qcow2
echo "vmtc spike readme" > README.txt
xz -k -T0 disk.qcow2
CA="--ca-file $W/certs/cert.pem"
$ORAS login $CA -u "$U" -p "$P" $T >/dev/null
$ORAS login --plain-http -u "$U" -p "$P" localhost:5000 >/dev/null
AT="--artifact-type application/vnd.vmtc.vm-image.v1"
$ORAS push $CA $AT $T/vmtc/single:v1 disk.qcow2 >/dev/null
$ORAS push $CA $AT $T/vmtc/multi:v1 disk.qcow2 README.txt >/dev/null
$ORAS push $CA $AT $T/vmtc/xz:v1 disk.qcow2.xz >/dev/null
$ORAS push $CA $AT $T/vmtc/moving:latest disk.qcow2 >/dev/null
export ORAS_MOVING_OLD=$($ORAS manifest fetch $CA --descriptor $T/vmtc/moving:latest | jq -r .digest)
$ORAS push $CA $AT $T/vmtc/moving:latest README.txt >/dev/null
export ORAS_MOVING_NEW=$($ORAS manifest fetch $CA --descriptor $T/vmtc/moving:latest | jq -r .digest)
$ORAS push --plain-http $AT localhost:5000/vmtc/single:v1 disk.qcow2 >/dev/null
echo "--- oras manifest (single:v1) as pushed by oras $VER:"; $ORAS manifest fetch $CA $T/vmtc/single:v1 | jq -c .
cd - >/dev/null

# --- tests ---
export ORAS_TLS=$T ORAS_PLAIN=localhost:5000 ORAS_CA="$W/certs/cert.pem" ORAS_USER="$U" ORAS_PASS="$P" ORAS_EXPECT_DIR="$W/expect"
rm -f target/*-results.txt
[ -f ~/.docker/config.json ] && mv ~/.docker/config.json ~/.docker/config.json.spike-bak || true
mvn -B -q test || FAIL=1                                                   # default scenario + HTTP tests
printf '{"auths":{"%s":{"auth":"%s"}}}' "$T" "$(printf '%s:%s' "$U" "$P" | base64 -w0)" > "$W/authfile.json"
ORAS_SCENARIO=authfile REGISTRY_AUTH_FILE="$W/authfile.json" mvn -B -q test -Dtest=OrasPullTest || FAIL=1
podman login --tls-verify=false -u "$U" -p "$P" localhost:5000 >/dev/null
ls -l "$XDG_RUNTIME_DIR/containers/auth.json"
ORAS_SCENARIO=podman mvn -B -q test -Dtest=OrasPullTest || FAIL=1
echo "=== results"; cat target/http-results.txt target/oras-results.txt
[ "${KEEP_REGISTRIES:-0}" = 1 ] || podman rm -f reg-tls reg-plain >/dev/null || true
exit $FAIL
