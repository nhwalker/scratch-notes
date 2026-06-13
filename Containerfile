# Java/Gradle build image with Allure reporting and nested Podman support.
#
# Based on UBI 9 minimal, providing:
#   - OpenJDK 21 and 25 (devel RPMs, so javac is available for both)
#   - Gradle 9.2.1
#   - Allure 2 commandline (test reporting)
#   - Podman, for docker-in-docker style image builds and Testcontainers runs
#
# Build:
#   podman build -t java-gradle-ci -f Containerfile .
#
# Nested containers (DinD-style builds / Testcontainers) require a
# privileged run:
#   podman run --rm -it --privileged java-gradle-ci
#
# For Testcontainers, start the Docker-compatible API socket inside the
# container first (DOCKER_HOST below already points at it):
#   podman system service --time=0 unix:///run/podman/podman.sock &

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
        shadow-utils \
        git-core \
        tar \
        gzip \
        zip \
        unzip \
        which \
        findutils \
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

# Nested-podman setup, mirroring the upstream podman-in-podman image:
# subordinate ID ranges for a dedicated rootless "podman" user, and
# fuse-overlayfs as the storage mount program so overlay works when
# /var/lib/containers itself sits on an overlay filesystem.
RUN useradd -m podman \
    && printf 'podman:1:999\npodman:1001:64535\n' > /etc/subuid \
    && printf 'podman:1:999\npodman:1001:64535\n' > /etc/subgid \
    && sed -e 's|^#mount_program|mount_program|g' \
           -e 's|^mountopt[[:space:]]*=.*$|mountopt = "nodev,fsync=0"|g' \
           /usr/share/containers/storage.conf > /etc/containers/storage.conf \
    && mkdir -p /home/podman/.config/containers /home/podman/.local/share/containers \
    && chown -R podman:podman /home/podman

COPY <<'EOF' /etc/containers/containers.conf
[containers]
log_driver = "k8s-file"

[engine]
cgroup_manager = "cgroupfs"
events_logger = "file"
runtime = "crun"
EOF

COPY --chown=podman:podman <<'EOF' /home/podman/.config/containers/containers.conf
[containers]
volumes = ["/proc:/proc"]

[engine]
cgroup_manager = "cgroupfs"
events_logger = "file"
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
    BUILDAH_ISOLATION=chroot \
    DOCKER_HOST=unix:///run/podman/podman.sock
ENV PATH="${JAVA_HOME}/bin:${GRADLE_HOME}/bin:${ALLURE_HOME}/bin:${PATH}"

# Container storage lives on volumes so nested image pulls/builds don't
# write through the image's own overlay layer.
VOLUME /var/lib/containers
VOLUME /home/podman/.local/share/containers

# Build-time sanity check that every tool resolves and runs.
RUN java -version \
    && "${JAVA_25_HOME}/bin/java" -version \
    && gradle --version \
    && allure --version \
    && podman --version \
    && rm -rf /root/.gradle

WORKDIR /workspace

CMD ["/bin/bash"]
