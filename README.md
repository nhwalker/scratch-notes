# podman-gpu-service

A rootful **podman-in-podman** ("DinD") container image built on `ubi9-minimal`,
configured the same way as the official [`quay.io/podman`](https://github.com/containers/image_build/tree/main/podman)
images, plus the **NVIDIA Container Toolkit**. It runs `podman system service`
inside the container and exposes the API socket back to the host, so the host can
drive an isolated Podman engine — including GPU workloads when a GPU is injected
from the host's NVIDIA Container Toolkit.

## Contents

| File | Purpose |
| --- | --- |
| `Containerfile` | ubi9-minimal + podman (DinD config) + nvidia-container-toolkit |
| `entrypoint.sh` | Detects an injected GPU, runs `nvidia-ctk cdi generate`, then starts `podman system service --time=0` |
| `containers.conf` | Engine/containers defaults shipped to `/etc/containers/containers.conf` |
| `nvidia-container-toolkit.repo` | NVIDIA yum repo definition used during the build |
| `start-podman-service.sh` | Host-side launcher (rootless podman) that mounts the socket dir + storage volume |
| `install-systemd.sh` | Installs the launcher as a systemd `--user` service |

## Build

```sh
podman build -t podman-gpu-service:latest .
```

## Run

```sh
./start-podman-service.sh
```

This starts the container detached, bind-mounting a socket directory
(`$XDG_RUNTIME_DIR/podman-gpu` by default) and a named storage volume
(`podman-gpu-storage`). If the host has the NVIDIA Container Toolkit, it injects
the GPU with `--device nvidia.com/gpu=all`.

### Connect from the host

```sh
export CONTAINER_HOST=unix://$XDG_RUNTIME_DIR/podman-gpu/podman.sock
podman --url "$CONTAINER_HOST" info
podman --url "$CONTAINER_HOST" run --rm docker.io/library/alpine echo ok
```

Docker clients can use `DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman-gpu/podman.sock`.

### GPU workloads

When a GPU is present, the entrypoint writes a CDI spec to `/etc/cdi/nvidia.yaml`,
so nested containers can request it:

```sh
podman --url "$CONTAINER_HOST" run --rm --device nvidia.com/gpu=all \
    docker.io/nvidia/cuda:12.4.1-base-ubi9 nvidia-smi
```

## Install as a systemd --user service

```sh
./install-systemd.sh
systemctl --user status podman-gpu-service
```

Remove with:

```sh
systemctl --user disable --now podman-gpu-service
rm ~/.config/systemd/user/podman-gpu-service.service && systemctl --user daemon-reload
```

## Configuration

Environment variables honored by `start-podman-service.sh`: `IMAGE`, `NAME`,
`SOCK_DIR`, `STORAGE_VOLUME`, `FORCE_GPU`. The entrypoint honors `FORCE_CDI`,
`SKIP_CDI`, `CDI_OUTPUT`, and `PODMAN_SOCKET`.
