#!/bin/bash
# Entrypoint for the single-node k3s + CRI-O image.
#
# Chains to the podman base image's entrypoint (podman API service when
# START_PODMAN is set, NVIDIA CDI spec generation when a GPU is present)
# by sourcing it, then starts CRI-O and a single-node k3s server that
# uses CRI-O as its container runtime.
#
# Run privileged, with cgroup v2 available to the container:
#   podman run --rm -it --privileged k3s-crio
#
# Pass a command (e.g. bash) to skip launching k3s and get a shell with
# CRI-O already running.
#
# Extra k3s server flags can be supplied via K3S_EXTRA_ARGS, e.g.
#   K3S_EXTRA_ARGS="--disable=traefik --disable=servicelb"
set -euo pipefail

# Reuse the base image setup. Sourcing (rather than exec'ing) returns
# control here; the base entrypoint skips its own exec when sourced.
# shellcheck source=/dev/null
source /usr/local/bin/entrypoint-podman.sh

CRIO_SOCKET=/run/crio/crio.sock

start_crio() {
    mkdir -p /run/crio
    crio &
    local crio_pid=$!
    for _ in $(seq 1 150); do
        [[ -S "${CRIO_SOCKET}" ]] && return 0
        if ! kill -0 "${crio_pid}" 2>/dev/null; then
            echo "entrypoint: CRI-O exited during startup" >&2
            return 1
        fi
        sleep 0.2
    done
    echo "entrypoint: CRI-O socket ${CRIO_SOCKET} did not appear" >&2
    return 1
}

start_crio

# A command overrides the default of launching k3s (useful for debugging
# with CRI-O already up).
if [[ "$#" -gt 0 ]]; then
    exec "$@"
fi

# k3s points its kubelet at CRI-O instead of the embedded containerd.
# cgroupfs matches the CRI-O cgroup_manager (no systemd in the
# container); host-gw flannel avoids needing the vxlan kernel module.
read -ra k3s_extra <<< "${K3S_EXTRA_ARGS:-}"
exec k3s server \
    --container-runtime-endpoint="unix://${CRIO_SOCKET}" \
    --kubelet-arg=cgroup-driver=cgroupfs \
    --flannel-backend=host-gw \
    --write-kubeconfig-mode=0644 \
    "${k3s_extra[@]}"
