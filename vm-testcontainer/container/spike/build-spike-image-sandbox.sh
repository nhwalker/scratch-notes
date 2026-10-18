#!/usr/bin/env bash
# Sandbox-only equivalent of: podman build -f Containerfile.spike -t vmtc-spike:gst .
# (adds the Claude sandbox proxy CA at run time, then commits)
set -euo pipefail; cd "$(dirname "$0")"
docker rm -f vmtc-spike-build >/dev/null 2>&1 || true
docker run --name vmtc-spike-build --network host -e HTTPS_PROXY -e https_proxy \
  -v /root/.ccr/ca-bundle.crt:/extra-ca.crt:ro -v "$PWD":/spike:ro \
  registry.access.redhat.com/ubi9/ubi:9.8 bash -c '
    cp /extra-ca.crt /etc/pki/ca-trust/source/anchors/ && update-ca-trust
    echo "proxy=$HTTPS_PROXY" >> /etc/dnf/dnf.conf
    /spike/install-packages.sh media
    sed -i "/^proxy=/d" /etc/dnf/dnf.conf
    rm /etc/pki/ca-trust/source/anchors/extra-ca.crt && update-ca-trust'
docker commit vmtc-spike-build vmtc-spike:gst >/dev/null && docker rm vmtc-spike-build >/dev/null
docker images vmtc-spike:gst
