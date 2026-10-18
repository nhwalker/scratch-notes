#!/usr/bin/env bash
# Engine pull of a private containerDisk + image mount, through Testcontainers -> rootless podman.
# Expects the htpasswd TLS registry from spike-oras/run-oras-ci.sh still running at registry.test:5443 (vmtc / s3cret),
# with its CA at $CA_PEM. Runs scenarios S1 (no creds), S2 (docker config.json), S3 (podman login only).
set -uo pipefail
cd "$(dirname "$0")"
REG=registry.test:5443 U=vmtc P=s3cret REF=registry.test:5443/vmtc/disk:v1
IMAGE=${IMAGE:-localhost/vmtc-spike:media}
# trust the registry CA for rootless podman
mkdir -p ~/.config/containers/certs.d/$REG && cp "$CA_PEM" ~/.config/containers/certs.d/$REG/ca.crt
# build + push the containerDisk (8 MiB qcow2) with podman
mkdir -p containerdisk
[ -f containerdisk/disk.qcow2 ] || podman run --rm -v "$PWD/containerdisk:/w:Z" "$IMAGE" qemu-img create -q -f qcow2 /w/disk.qcow2 8M
podman build -q -t $REF containerdisk >/dev/null
podman login -u $U -p $P $REG >/dev/null && podman push -q $REF && podman logout $REG >/dev/null
SOCK="$XDG_RUNTIME_DIR/podman/podman-pull.sock"; mkdir -p "$(dirname "$SOCK")"
podman system service --time=0 "unix://$SOCK" & SVC=$!; sleep 3
run() { DOCKER_HOST="unix://$SOCK" TESTCONTAINERS_RYUK_DISABLED=true PULL_REF=$REF PULL_SCENARIO=$1 \
          mvn -B -q test -Dtest=PullAuthProbeTest -Dprobe.image="$IMAGE" || true; }
clean() { podman rmi -f $REF >/dev/null 2>&1; rm -f ~/.docker/config.json "$XDG_RUNTIME_DIR/containers/auth.json"; }
rm -f target/pull-results.txt
clean; run S1-no-credentials
clean; mkdir -p ~/.docker; printf '{"auths":{"%s":{"auth":"%s"}}}' $REG "$(printf '%s:%s' $U $P | base64 -w0)" > ~/.docker/config.json
run S2-docker-config-json
# image mount of the pulled private image, through Testcontainers (S2 left it pulled)
DOCKER_HOST="unix://$SOCK" TESTCONTAINERS_RYUK_DISABLED=true mvn -B -q test -Dtest=PodmanKvmProbeTest#imageMount \
  -Dprobe.image="$IMAGE" -Dprobe.containerdisk=$REF || true
clean; podman login -u $U -p $P $REG >/dev/null; ls -l "$XDG_RUNTIME_DIR/containers/auth.json"
run S3-podman-login-only
clean; kill $SVC 2>/dev/null; true
echo "=== pull results"; cat target/pull-results.txt; echo "=== image mount (private image)"; grep image-mount target/probe-results.txt
