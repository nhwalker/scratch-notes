#!/usr/bin/env bash
# Runs spike1 in the Claude cloud sandbox (proxy + CA). On a normal host just run:
#   podman run --rm -v "$PWD":/spike:Z registry.access.redhat.com/ubi9/ubi:9.8 /spike/spike1.sh
cd "$(dirname "$0")"
${ENGINE:-docker} run --rm --network host -e HTTPS_PROXY -e https_proxy -e NO_PROXY -e no_proxy \
  -v /root/.ccr/ca-bundle.crt:/extra-ca.crt:ro -v "$PWD":/spike \
  registry.access.redhat.com/ubi9/ubi:9.8 /spike/spike1.sh
