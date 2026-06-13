# Java/Gradle build image with Allure reporting and nested Podman support.
#
# Based on UBI 9 minimal, providing:
#   - OpenJDK 21 and 25 (devel RPMs, so javac is available for both)
#   - Gradle 9.2.1
#   - Allure 2 commandline (test reporting)
#   - Helm 3 (chart linting)
#   - Podman, for docker-in-docker style image builds and Testcontainers runs
#
# Build:
#   podman build -t java-gradle-ci -f Containerfile .
#
# Nested containers (DinD-style builds / Testcontainers) require a
# privileged run:
#   podman run --rm -it --privileged java-gradle-ci
#
# Set START_PODMAN=1 to have the entrypoint fork the Docker-compatible
# podman API service and export DOCKER_HOST at its socket (used by
# Testcontainers). When a GPU is visible via nvidia-smi, the entrypoint
# also generates the NVIDIA CDI spec so nested containers can use
# --device nvidia.com/gpu=all.

FROM registry.access.redhat.com/ubi9/ubi-minimal:latest

ARG GRADLE_VERSION=9.2.1
ARG GRADLE_SHA256=72f44c9f8ebcb1af43838f45ee5c4aa9c5444898b3468ab3f4af7b6076c5bc3f
ARG ALLURE_VERSION=2.42.1
ARG ALLURE_SHA256=f8f73bf4bbd2cf5b8eee51d27487aef55c9d2f2650db111a52fa8108866ba86c
# OpenJDK 21 + 25, Podman plus rootless plumbing (fuse-overlayfs,
# shadow-utils for newuidmap/newgidmap), and the archive/VCS tools that
# Gradle builds commonly expect on the PATH.
RUN microdnf -y install \
        java-21-openjdk-devel \
        java-25-openjdk-devel \
        podman \
        fuse-overlayfs \
        git-core \
        tar \
        gzip \
        zip \
        unzip \
        which \
        findutils \
    && microdnf clean all

# NVIDIA Container Toolkit, from NVIDIA's official RPM repo, so nested
# podman runs can expose host GPUs. The entrypoint generates the CDI
# spec when a GPU is present; nested containers then use:
#   podman run --device nvidia.com/gpu=all ...
RUN curl -fsSL -o /etc/yum.repos.d/nvidia-container-toolkit.repo \
        "https://nvidia.github.io/libnvidia-container/stable/rpm/nvidia-container-toolkit.repo" \
    && microdnf -y install nvidia-container-toolkit \
    && microdnf clean all

# Gradle, verified against the published distribution checksum.
RUN curl -fsSL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
    && echo "${GRADLE_SHA256}  /tmp/gradle.zip" | sha256sum -c - \
    && unzip -q /tmp/gradle.zip -d /opt \
    && ln -s "/opt/gradle-${GRADLE_VERSION}" /opt/gradle \
    && rm -f /tmp/gradle.zip

# Allure commandline from Maven Central.
RUN curl -fsSL -o /tmp/allure.tgz "https://repo.maven.apache.org/maven2/io/qameta/allure/allure-commandline/${ALLURE_VERSION}/allure-commandline-${ALLURE_VERSION}.tgz" \
    && echo "${ALLURE_SHA256}  /tmp/allure.tgz" | sha256sum -c - \
    && tar -xzf /tmp/allure.tgz -C /opt \
    && ln -s "/opt/allure-${ALLURE_VERSION}" /opt/allure \
    && rm -f /tmp/allure.tgz

# Helm 3 (chart linting) from the EPEL 9 "helm3" RPM, GPG-verified
# against the EPEL signing key (EPEL's "helm" package is the Helm 4
# line). The RPM installs the binary as helm3, so a helm symlink is
# added for scripts that expect the usual name. microdnf can't install
# RPMs by URL, so epel-release is fetched and installed with rpm
# directly.
RUN curl -fsSL -o /tmp/epel-release.rpm \
        "https://dl.fedoraproject.org/pub/epel/epel-release-latest-9.noarch.rpm" \
    && rpm -Uvh /tmp/epel-release.rpm \
    && rm -f /tmp/epel-release.rpm \
    && microdnf -y install helm3 \
    && microdnf clean all \
    && ln -s /usr/bin/helm3 /usr/local/bin/helm

# Nested-podman storage setup (root-only): fuse-overlayfs as the
# storage mount program so overlay works when /var/lib/containers
# itself sits on an overlay filesystem.
RUN sed -i -e 's|^#mount_program|mount_program|g' \
        -e 's|^mountopt[[:space:]]*=.*$|mountopt = "nodev,fsync=0"|g' \
        /etc/containers/storage.conf

COPY <<'EOF' /etc/containers/containers.conf
[containers]
log_driver = "k8s-file"

[engine]
cgroup_manager = "cgroupfs"
events_logger = "file"
runtime = "crun"
EOF

# Java 21 (LTS) is the default JVM for running Gradle; Java 25 is
# available at JAVA_25_HOME and is auto-detected by Gradle toolchains
# from /usr/lib/jvm.
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
    JAVA_21_HOME=/usr/lib/jvm/java-21-openjdk \
    JAVA_25_HOME=/usr/lib/jvm/java-25-openjdk \
    GRADLE_HOME=/opt/gradle \
    ALLURE_HOME=/opt/allure \
    _CONTAINERS_USERNS_CONFIGURED="" \
    # BUILDAH_ISOLATION sets how buildah/podman-build runs RUN steps:
    #   oci      - full OCI container per step via crun (the default);
    #              needs nesting privileges this image often won't have
    #   chroot   - plain chroot with bind-mounted /proc and /dev; works
    #              reliably when building inside a container
    #   rootless - like oci but forces rootless settings
    BUILDAH_ISOLATION=chroot
ENV PATH="${JAVA_HOME}/bin:${GRADLE_HOME}/bin:${ALLURE_HOME}/bin:${PATH}"

# Container storage lives on a volume so nested image pulls/builds
# don't write through the image's own overlay layer.
VOLUME /var/lib/containers

# Build-time sanity check that every tool resolves and runs.
RUN java -version \
    && "${JAVA_25_HOME}/bin/java" -version \
    && gradle --version \
    && allure --version \
    && podman --version \
    && nvidia-ctk --version \
    && helm version \
    && rm -rf /root/.gradle

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 0755 /usr/local/bin/entrypoint.sh

WORKDIR /workspace

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["/bin/bash"]
