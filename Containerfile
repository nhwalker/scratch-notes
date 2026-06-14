# Rootful podman-in-podman ("DinD") image on a UBI9 base, configured the same way
# as the official quay.io/podman images (containers/image_build), plus the NVIDIA
# Container Toolkit so injected GPUs can be exposed to nested containers via CDI.
FROM registry.access.redhat.com/ubi9/ubi-minimal:latest

# Core podman stack. These all live in the default UBI9 BaseOS repo, so no
# --enablerepo is required on ubi-minimal.
RUN microdnf -y update && \
    microdnf -y install \
        podman \
        fuse-overlayfs \
        crun \
        openssh-clients \
        shadow-utils \
        --exclude container-selinux && \
    microdnf clean all

# NVIDIA Container Toolkit (provides nvidia-ctk + nvidia-cdi-hook). The repo file
# is shipped in-tree because ubi-minimal lacks the usual curl|tee convenience.
COPY nvidia-container-toolkit.repo /etc/yum.repos.d/nvidia-container-toolkit.repo
RUN microdnf -y install nvidia-container-toolkit && \
    microdnf clean all

# --- quay.io/podman DinD configuration ---------------------------------------

# Engine/containers defaults tuned for running podman inside a container.
COPY containers.conf /etc/containers/containers.conf

# storage: enable fuse-overlayfs, add the shared additional image store, and
# relax fsync — identical transform to the upstream podman image.
RUN sed -e 's|^#mount_program|mount_program|g' \
        -e '/additionalimage.*/a "/var/lib/shared",' \
        -e 's|^mountopt[[:space:]]*=.*$|mountopt = "nodev,fsync=0"|g' \
        /usr/share/containers/storage.conf \
        > /etc/containers/storage.conf

# Pre-create the shared additional image store with its lock files.
RUN mkdir -p /var/lib/shared/overlay-images \
             /var/lib/shared/overlay-layers \
             /var/lib/shared/vfs-images \
             /var/lib/shared/vfs-layers && \
    touch /var/lib/shared/overlay-images/images.lock && \
    touch /var/lib/shared/overlay-layers/layers.lock && \
    touch /var/lib/shared/vfs-images/images.lock && \
    touch /var/lib/shared/vfs-layers/layers.lock

# subuid/subgid for root-owned nested user namespaces (service runs as root).
RUN echo "root:1:65535" > /etc/subuid && \
    cp /etc/subuid /etc/subgid

ENV _CONTAINERS_USERNS_CONFIGURED="" \
    BUILDAH_ISOLATION=chroot

VOLUME /var/lib/containers

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
