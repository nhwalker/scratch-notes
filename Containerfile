#
# Podman-in-Podman image, rebuilt on ubi9-minimal.
#
# This is a port of the official quay.io/podman/stable image
# (github.com/containers/image_build/tree/main/podman) from
# registry.fedoraproject.org/fedora to RHEL UBI 9 minimal.
#
# It produces a container that can safely run podman *inside* a
# container ("podman-in-podman") when run with appropriate privileges,
# e.g.:
#
#   podman build -t pinp -f Containerfile .
#   podman run --rm --privileged \
#       --device /dev/fuse pinp \
#       podman run --rm docker.io/library/alpine echo "hello from the nested container"
#
# Rootless nested podman:
#   podman run --rm --user podman --security-opt label=disable \
#       --device /dev/fuse pinp \
#       podman run --rm docker.io/library/alpine echo hello
#

FROM registry.access.redhat.com/ubi9/ubi-minimal:latest

# Be tolerant of slow mirrors during the build.
RUN printf '\n# Added during image build\nminrate=100\ntimeout=60\n' >> /etc/dnf/dnf.conf

# UBI minimal ships microdnf and omits shadow-utils/sed, which the
# original Fedora build relies on (useradd, setcaps, the storage.conf
# rewrite), so install them alongside the podman stack.
ARG INSTALL_RPMS="podman fuse-overlayfs crun openssh-clients git-core shadow-utils sed"

RUN microdnf -y update && \
    microdnf -y install --setopt=install_weak_deps=0 --nodocs $INSTALL_RPMS && \
    rpm --setcaps shadow-utils 2>/dev/null ; \
    microdnf clean all && \
    rm -fv /etc/machine-id /var/lib/systemd/random-seed && \
    rm -rf /var/cache/* /var/log/dnf* /var/log/yum.*

# Rootless user + subordinate UID/GID ranges, matching the upstream image.
RUN useradd podman && \
    printf 'root:1:65535\npodman:1:999\npodman:1001:64535\n' > /etc/subuid && \
    printf 'root:1:65535\npodman:1:999\npodman:1001:64535\n' > /etc/subgid

# System-wide containers.conf tuned for running inside a container:
# share the host namespaces, disable cgroups, use the file event logger
# and cgroupfs manager, and default to crun.
RUN mkdir -p /etc/containers && \
    printf '%s\n' \
      '[containers]' \
      'netns="host"' \
      'userns="host"' \
      'ipcns="host"' \
      'utsns="host"' \
      'cgroupns="host"' \
      'cgroups="disabled"' \
      'log_driver = "k8s-file"' \
      '[engine]' \
      'cgroup_manager = "cgroupfs"' \
      'events_logger="file"' \
      'runtime="crun"' \
      > /etc/containers/containers.conf && \
    chmod 644 /etc/containers/containers.conf

# Per-user containers.conf for the rootless "podman" user.
RUN mkdir -p /home/podman/.config/containers && \
    printf '%s\n' \
      '[containers]' \
      'volumes = [' \
      '	"/proc:/proc",' \
      ']' \
      'default_sysctls = []' \
      > /home/podman/.config/containers/containers.conf && \
    mkdir -p /home/podman/.local/share/containers && \
    chown podman:podman -R /home/podman

# Switch storage to fuse-overlayfs, register the shared additional image
# store, and relax mount options for nested use.
RUN sed -e 's|^#mount_program|mount_program|g' \
        -e '/additionalimage.*/a "/var/lib/shared",' \
        -e 's|^mountopt[[:space:]]*=.*$|mountopt = "nodev,fsync=0"|g' \
        /usr/share/containers/storage.conf \
        > /etc/containers/storage.conf

# Pass RHSM/entitlement secrets through to nested containers (UBI/RHEL).
RUN printf '/run/secrets/etc-pki-entitlement:/run/secrets/etc-pki-entitlement\n/run/secrets/rhsm:/run/secrets/rhsm\n' > /etc/containers/mounts.conf

VOLUME /var/lib/containers
VOLUME /home/podman/.local/share/containers

# Pre-create the shared (additional) image store with its lock files.
RUN mkdir -p /var/lib/shared/overlay-images \
             /var/lib/shared/overlay-layers \
             /var/lib/shared/vfs-images \
             /var/lib/shared/vfs-layers && \
    touch /var/lib/shared/overlay-images/images.lock && \
    touch /var/lib/shared/overlay-layers/layers.lock && \
    touch /var/lib/shared/vfs-images/images.lock && \
    touch /var/lib/shared/vfs-layers/layers.lock

ENV _CONTAINERS_USERNS_CONFIGURED="" \
    BUILDAH_ISOLATION=chroot
