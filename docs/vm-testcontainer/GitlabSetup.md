# QEMU VM Testcontainer: GitLab CI/CD Setup

> **Purpose:** the GitLab CI/CD design for the project described in [PLAN.md](PLAN.md). It replaces PLAN.md §12 (which describes a GitHub Actions workflow) for a project hosted on a **self-managed GitLab** with **runners you control**. It gives the runner platform, the normative `.gitlab-ci.yml`, the Gradle and document changes that follow, and the first-run checklist.
> **Status:** **[U]** throughout. Every shell recipe inside the jobs is the one verified on GitHub-hosted runners in 36 spike runs (PLAN.md §17), but no GitLab pipeline has run yet: this document could not be executed from the environment it was written in. Section 9 lists exactly what the first run must prove.
> **Names:** `${…}` are the PLAN.md §0 parameters. `<value>` means "type the real value". GitLab predefined variables (`CI_REGISTRY_IMAGE`, `CI_JOB_TOKEN`, …) are written as GitLab writes them.

---

## 1. BLUF

- **Topology:** one small **runner manager** VM runs `gitlab-runner` with the **instance executor** and the GitLab Runner **autoscaler** (fleeting plugin). Every job that needs KVM gets a **fresh ephemeral VM** created from an image with nested virtualization, rootless Podman and the `/dev/kvm` udev rule already in place; the VM is deleted after the job. Engine-free jobs (`check`, `unit`, `pages`, `publish-maven`) run on the manager with the docker executor.
- **Pipeline:** stages `check → images → test → publish → pages`. Jobs mirror PLAN.md §12 one to one: `unit`, `integration` as a `parallel: matrix` over `ACCEL: [kvm, tcg]`, `compat` as a matrix over scenarios A/B/C (each on its own throwaway VM, so the host mutations of PLAN-STEPS.md step 1.25 need no restore), `oci-auth`, plus `publish-images`, `publish-maven` and `pages`.
- **GitLab features used:** `artifacts:reports:junit` (every test rendered in the merge request), the project **Container Registry** for the four images, the project **Maven Package Registry** as the publishing destination (closes PLAN.md §16 item 6), a weekly **pipeline schedule** for the `-PnoCache=true` rebuild, a **distributed cache** (object storage) because ephemeral VMs have no local cache, and **GitLab Pages** for the default branch's Allure report.
- **Decisions taken** (2026-10-19, project owner): GitLab is the only CI (no GitHub workflow in the target repository); autoscaled ephemeral VMs; both GitLab registries; Pages for the default-branch report; this document is the home of everything GitLab-specific.
- **What must be proven first:** `/dev/kvm` and rootless Podman inside a job on your VM image (section 4.6). Until that passes, nothing else in this document is worth running.

---

## 2. Decisions

| # | Decision | Consequence |
|---|----------|-------------|
| G1 | **GitLab only.** The target repository carries `.gitlab-ci.yml` and no `.github/` directory | PLAN.md §12 is superseded by section 5; the GitHub workflow of the spike repository remains evidence only |
| G2 | **Ephemeral VM per KVM job** through the GitLab Runner autoscaler (instance executor + fleeting plugin) | No persistent host is ever mutated by `compat`; every job starts from the same image; a distributed cache replaces the runner-local cache |
| G3 | **GitLab Container Registry** holds `${IMAGE_RUNNER}`, `${IMAGE_RECORDER}`, `${IMAGE_GUEST}`, `${IMAGE_GUEST_BLACKBOX}` | PLAN.md §0 `${IMAGE_REGISTRY}` = the project's registry path (for example `registry.example.com/platform/vm-testcontainer`); jobs log in with `CI_JOB_TOKEN`; `pushImages` runs on the default branch and on tags |
| G4 | **GitLab Maven Package Registry** is the publishing destination | PLAN.md §16 item 6 is decided; `maven-publish` gets one repository block with a `Job-Token` header; consumers outside CI use a deploy token or a personal access token |
| G5 | **Allure report:** downloadable per job, plus the default branch's `integration: [kvm]` report on GitLab Pages | `pages` job on the default branch only; merge requests use the JUnit view and the artifact download |
| G6 | **The verified shell recipe is kept verbatim:** `podman system service` on a private socket, `DOCKER_HOST`, `TESTCONTAINERS_RYUK_DISABLED=true`, the `/dev/kvm` udev rule | Nothing about how the library talks to Podman changes between GitHub and GitLab |

---

## 3. Topology

```
 GitLab (self-managed)                           runner manager VM (persistent, small)
 ┌──────────────────────────────┐                ┌──────────────────────────────────────────┐
 │ project repo + .gitlab-ci.yml│   jobs/tags    │ gitlab-runner                            │
 │ Container Registry  (G3)     │◄──────────────►│  ├─ runner "vmtc-kvm": executor=instance │
 │ Maven Package Registry (G4)  │                │  │    autoscaler → fleeting plugin ───────┼──► cloud API: create / delete VMs
 │ Pages (G5)                   │                │  └─ runner "vmtc-small": executor=docker │
 │ pipeline schedule (weekly)   │                └──────────────────────────────────────────┘
 └──────────────────────────────┘                                   │ SSH (job shell)
                                                                     ▼
            distributed cache bucket             ephemeral VM (one per job, deleted after)
            ┌──────────────────────┐             ┌──────────────────────────────────────────┐
            │ Gradle caches        │◄───────────►│ Ubuntu 24.04 + nested virt (/dev/kvm)    │
            │ images/build/guest/  │             │ rootless podman ── runner container ── QEMU VM
            │   base.qcow2         │             │                 └─ recorder container     │
            └──────────────────────┘             └──────────────────────────────────────────┘
```

- The **instance executor** runs the job's shell directly on the ephemeral VM over SSH (no container around the job), which is exactly the GitHub-hosted-runner situation the spikes verified: rootless Podman owned by the job user, the library talking to its socket.
- The **docker executor** on the manager runs engine-free jobs in a plain JDK image.
- The two runners carry the tags `vmtc-kvm` and `vmtc-small`; every job names one.

---

## 4. Runner platform

### 4.1 Cloud and machine type

The design is cloud-neutral; the worked example is Google Compute Engine because nested virtualization is a documented per-instance switch there. Facts to re-check against the provider's current documentation when adopting:

| Provider | Nested virtualization | Notes |
|----------|-----------------------|-------|
| Google Compute Engine | Supported on Intel (Haswell or later) machine series when enabled on the instance or baked into the image as a license; the documentation lists E2, AMD-based and Arm VMs as **not** supported, and warns of a ≥ 10 % CPU penalty for nested guests | Use an Intel series such as `n2-standard-4` (4 vCPU, 16 GB: the GitHub runner size the timings in PLAN.md §3.3 come from) |
| AWS EC2 | Only `*.metal` instance types expose VMX | Expensive per job; prefer a static pool (section 4.7) |
| Azure | Dv3/Ev3 and later families support nested virtualization | Fleeting plugin availability to be checked |
| On-premises (libvirt/Proxmox) | Native KVM | No official fleeting plugin known to the author; use the static pool of section 4.7 |

### 4.2 VM image (what every ephemeral VM boots from)

Build one image, rebuild it when Podman or the OS changes. Contents:

| Item | Value | Why |
|------|-------|-----|
| OS | Ubuntu 24.04 LTS (x86-64) | The verified host (Podman 4.9.3, crun, cgroup v2, netavark + aardvark-dns, PLAN.md §3.6) |
| Packages | `podman`, `uidmap`, `slirp4netns`, `passt`, `fuse-overlayfs`, `git`, `curl`, `openssl`, `python3`, `python3-yaml`, `openssh-client` (for `ssh-keygen`), `xorriso`, `qemu-utils`, `unzip` | Everything PLAN-STEPS.md step 0.5 lists; `xorriso`/`qemu-utils` only for local debugging (the library runs them inside the runner container) |
| JDK | **not** baked in: `./gradlew` uses the toolchain from `setup-java`'s equivalent, see section 5 (`eclipse-temurin` download via Gradle toolchain auto-provisioning, or bake Temurin `${JAVA_RELEASE}` into the image) | One fewer moving part if baked; the pipeline below assumes it is baked at `/usr/lib/jvm/temurin-<JAVA_RELEASE>` |
| Job user | `runner`, uid 1000, in group `kvm`, `subuid`/`subgid` ranges (`100000:65536`), `loginctl enable-linger runner` | Rootless Podman needs the ranges; lingering makes `/run/user/1000` exist at boot so `XDG_RUNTIME_DIR` is valid in the SSH session the instance executor opens |
| udev rule | `/etc/udev/rules.d/99-kvm4all.rules` = `KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"` | The verified recipe (PLAN.md §12.1 step 1). The `compat` scenarios B/C remove it on their own VM |
| sudo | `runner ALL=(ALL) NOPASSWD:ALL` | `compat` and `oci-auth` need root for `/dev/kvm` ownership, `usermod`, `/etc/hosts` |
| sshd | PAM enabled (Ubuntu default); the runner manager's public key in `/home/runner/.ssh/authorized_keys` | The instance executor connects over SSH |
| Pre-pulled images | none required; optional: `registry.access.redhat.com/ubi9/ubi:9.8`, `docker.io/library/registry:2`, `docker.io/library/httpd:2` | Saves 10–30 s per job; the pipeline works without them |

Image build procedure (GCE example; adapt the commands, keep the content):

```bash
# 1. template VM with nested virtualization
gcloud compute instances create vmtc-image-builder --zone <zone> --machine-type n2-standard-4 \
  --image-family ubuntu-2404-lts-amd64 --image-project ubuntu-os-cloud --enable-nested-virtualization
# 2. provision (run images/ci/provision-runner-vm.sh from section 4.3 over SSH)
# 3. stop and capture
gcloud compute instances stop vmtc-image-builder --zone <zone>
gcloud compute images create vmtc-runner-$(date +%Y%m%d) --source-disk vmtc-image-builder --source-disk-zone <zone> \
  --family vmtc-runner --licenses https://www.googleapis.com/compute/v1/projects/vm-options/global/licenses/enable-vmx
```

The `enable-vmx` license on the image is what makes `--enable-nested-virtualization` unnecessary on the instances the autoscaler creates (the instance template cannot always carry the flag; the license on the image always works). Verify once that a VM created from the image shows `/dev/kvm` **[U]**.

### 4.3 `images/ci/provision-runner-vm.sh` (normative; runs as root on the template VM)

```bash
#!/usr/bin/env bash
# Provisions an Ubuntu 24.04 VM as a vm-testcontainer CI worker. Idempotent.
set -euo pipefail
JAVA_RELEASE=${JAVA_RELEASE:-21}
RUNNER_PUBKEY=${RUNNER_PUBKEY:?public key of the runner manager}

apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
  podman uidmap slirp4netns passt fuse-overlayfs git curl ca-certificates openssl \
  python3 python3-yaml openssh-client xorriso qemu-utils unzip jq

# Temurin JDK (adoptium apt repo)
install -d /etc/apt/keyrings
curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public -o /etc/apt/keyrings/adoptium.asc
echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release; echo "$VERSION_CODENAME") main" \
  > /etc/apt/sources.list.d/adoptium.list
apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y "temurin-${JAVA_RELEASE}-jdk"

# job user
id runner >/dev/null 2>&1 || useradd -m -u 1000 -s /bin/bash runner
usermod -aG kvm runner
grep -q '^runner:' /etc/subuid || echo 'runner:100000:65536' >> /etc/subuid
grep -q '^runner:' /etc/subgid || echo 'runner:100000:65536' >> /etc/subgid
echo 'runner ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/90-runner && chmod 440 /etc/sudoers.d/90-runner
install -d -m 700 -o runner -g runner /home/runner/.ssh
echo "$RUNNER_PUBKEY" > /home/runner/.ssh/authorized_keys
chown runner:runner /home/runner/.ssh/authorized_keys && chmod 600 /home/runner/.ssh/authorized_keys
loginctl enable-linger runner

# /dev/kvm for everyone (PLAN.md §12.1 step 1); compat scenarios B/C remove this rule on their own VM
echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' > /etc/udev/rules.d/99-kvm4all.rules
udevadm control --reload-rules && udevadm trigger --name-match=kvm || true

# sanity
ls -l /dev/kvm
sudo -iu runner podman info --format 'podman {{.Version.Version}} rootless={{.Host.Security.Rootless}} net={{.Host.NetworkBackend}} runtime={{.Host.OCIRuntime.Name}}'
```

Expected sanity output on the template VM: `crw-rw-rw- 1 root kvm … /dev/kvm` and `podman 4.9.x rootless=true net=netavark runtime=crun` **[U]** (the GitHub runners showed the same values, PLAN.md §3.6).

### 4.4 Runner manager (`/etc/gitlab-runner/config.toml`)

One manager VM (2 vCPU is plenty) with `gitlab-runner` ≥ 17.x, Docker (for the `vmtc-small` runner) and the fleeting plugin installed with `gitlab-runner fleeting install`. Register two runners (`gitlab-runner register` with the project's runner authentication token) and then replace their sections with:

```toml
concurrent = 6                      # = max_instances × capacity_per_instance (+ small runner jobs)
check_interval = 3

[[runners]]
  name = "vmtc-kvm"
  url = "https://gitlab.example.com"
  token = "<runner authentication token>"
  executor = "instance"
  [runners.cache]                   # distributed cache: mandatory with ephemeral VMs
    Type = "gcs"                    # or "s3" / "azure"
    Shared = true
    [runners.cache.gcs]
      BucketName = "<bucket>"
      CredentialsFile = "/etc/gitlab-runner/gcs-cache.json"
  [runners.autoscaler]
    plugin = "googlecloud"          # gitlab-runner fleeting install
    capacity_per_instance = 1       # one job per VM
    max_use_count = 1               # VM deleted after its single job (G2)
    max_instances = 5
    instance_ready_command = "test -c /dev/kvm && test -S /run/user/1000/bus || exit 1"
    [runners.autoscaler.plugin_config]
      name = "vmtc-runner-mig"      # a managed instance group whose template uses the image family vmtc-runner
      project = "<gcp project>"
      zone = "<zone>"
    [runners.autoscaler.connector_config]
      username = "runner"
      use_external_addr = false
    [[runners.autoscaler.policy]]
      idle_count = 0                # 0 = pay nothing when idle; 1 = first job starts ~60 s sooner
      idle_time = "20m0s"

[[runners]]
  name = "vmtc-small"
  url = "https://gitlab.example.com"
  token = "<runner authentication token>"
  executor = "docker"
  [runners.docker]
    image = "eclipse-temurin:21-jdk"
    privileged = false
    volumes = ["/cache"]
  [runners.cache]                   # same bucket, so Gradle caches are shared with the KVM jobs
    Type = "gcs"
    Shared = true
    [runners.cache.gcs]
      BucketName = "<bucket>"
      CredentialsFile = "/etc/gitlab-runner/gcs-cache.json"
```

Notes:

- `instance_ready_command` keeps the autoscaler from handing a job to a VM whose udev rule has not fired yet or whose user session bus is not up; both are cheap checks **[U]**.
- The managed instance group's template: image family `vmtc-runner`, machine type `n2-standard-4`, a 60 GB disk (two 0.8 GB guest disks, the 646 MB base image, Podman storage and Gradle caches fit in 20 GB; 60 GB keeps TCG builds from running out of space), no public IP if the manager is in the same VPC.
- `max_instances = 5` lets a full pipeline (`integration` ×2, `compat` ×3, `oci-auth`) run with one job queued; raise it with the budget.
- The plugin keys above are the ones the GitLab documentation shows for the Google Cloud plugin; check them against the plugin's own README at adoption **[U]**.

### 4.5 Tags and job placement

| Job | Runner tag | Executor | Why |
|-----|-----------|----------|-----|
| `check`, `unit`, `publish-maven`, `pages` | `vmtc-small` | docker | No engine, no KVM |
| `integration: [kvm]`, `integration: [tcg]`, `compat: [A|B|C]`, `oci-auth`, `publish-images` | `vmtc-kvm` | instance (ephemeral VM) | Need rootless Podman; `kvm` entries need `/dev/kvm`. The `tcg` entry does not need KVM but needs Podman, and the same image is the simplest way to get it |

### 4.6 Proof job (run this before anything else)

Add this job to the first pipeline of the adopted repository and keep it (it is cheap and it documents the platform):

```yaml
platform-facts:
  stage: check
  tags: [vmtc-kvm]
  script:
    - nproc; free -g; df -h / | tail -1; uname -r; id; echo "XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-unset}"
    - test -c /dev/kvm && test -r /dev/kvm && test -w /dev/kvm && ls -l /dev/kvm
    - podman --version
    - podman info --format 'rootless={{.Host.Security.Rootless}} runtime={{.Host.OCIRuntime.Name}} cgroup={{.Host.CgroupsVersion}} net={{.Host.NetworkBackend}}'
    - java -version 2>&1 | head -1
    - podman run --rm --device /dev/kvm registry.access.redhat.com/ubi9/ubi:9.8 sh -c 'ls -l /dev/kvm'
```

Pass criteria **[U]**: `rootless=true`, `runtime=crun`, `cgroup=v2`, `net=netavark`, `/dev/kvm` readable and writable by `runner`, the UBI container sees `/dev/kvm`. If `XDG_RUNTIME_DIR` prints `unset`, the SSH session did not get a systemd user session: fix with `loginctl enable-linger` (section 4.3) or set `XDG_RUNTIME_DIR=/run/user/$(id -u)` in the job's `before_script`, and record it in PLAN-STEPS.md §D.

### 4.7 Fallback: static pool without the autoscaler

If no fleeting plugin fits your platform, register two or three VMs built from the same image as **shell-executor** runners with tag `vmtc-kvm`. Two consequences, both already covered by the plan:

- `compat` must restore the host: `it/fixtures/kvm-scenario.sh` (PLAN-STEPS.md step 1.25) already removes `containers.conf` and re-applies the udev rule at the start of every scenario; add `resource_group: kvm-host` to the `compat` and `oci-auth` jobs so they never overlap on one host.
- The runner-local cache replaces the bucket (`[runners.cache]` omitted), so `base.qcow2` and the Gradle caches persist per host.

---

## 5. `.gitlab-ci.yml` (normative)

Copy verbatim into the target repository root, replacing `<…>` values. Every `script` line that touches Podman or the library is the verified GitHub recipe (PLAN.md §12.1 and the spike workflow).

```yaml
# GitLab CI for vm-testcontainer (GitlabSetup.md §5). Supersedes PLAN.md §12.
workflow:
  rules:
    - if: $CI_PIPELINE_SOURCE == "merge_request_event"
    - if: $CI_PIPELINE_SOURCE == "push" && $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH
    - if: $CI_COMMIT_TAG
    - if: $CI_PIPELINE_SOURCE == "schedule"
    - if: $CI_PIPELINE_SOURCE == "web"

stages: [check, images, test, publish, pages]

variables:
  GIT_DEPTH: "20"
  GRADLE_USER_HOME: "$CI_PROJECT_DIR/.gradle-home"       # inside the workspace so it can be cached
  GRADLE_OPTS: "-Dorg.gradle.daemon=false -Dorg.gradle.console=plain"
  JAVA_HOME: "/usr/lib/jvm/temurin-21"                   # ${JAVA_RELEASE}; the docker executor image provides its own
  VMTC_IT_ENGINE_VERSION: ""                             # ${ENV_PREFIX}IT_ENGINE_VERSION, filled by .engine-session
  GRADLE_EXTRA: ""                                       # "-PnoCache=true" on the weekly schedule (see .scheduled)

# ---- reusable pieces -------------------------------------------------------------------------------

.gradle-cache:
  cache:
    - key:
        files: [gradle/wrapper/gradle-wrapper.properties, gradle/libs.versions.toml]
      paths: [.gradle-home/caches, .gradle-home/wrapper]
      policy: pull-push
    - key:
        files: [images/guest/guest.properties]            # the base image is keyed by its SHA-256 (PLAN.md §0)
      paths: [images/build/guest/base.qcow2]
      policy: pull-push

.scheduled:
  rules:
    - if: $CI_PIPELINE_SOURCE == "schedule"
      variables: { GRADLE_EXTRA: "-PnoCache=true" }       # REQ-BLD-05: weekly rebuild against fresh repos
    - when: on_success

# The verified Podman session (PLAN.md §12.1 steps 2–3): a private API socket, Ryuk off.
.engine-session:
  before_script:
    - export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"; mkdir -p "$XDG_RUNTIME_DIR/podman"
    - podman system service --time=0 "unix://$XDG_RUNTIME_DIR/podman/podman.sock" & sleep 2
    - export DOCKER_HOST="unix://$XDG_RUNTIME_DIR/podman/podman.sock" TESTCONTAINERS_RYUK_DISABLED=true
    - export VMTC_IT_ENGINE_VERSION="$(podman --version | awk '{print $3}')"
    - podman login -u "$CI_REGISTRY_USER" -p "$CI_REGISTRY_PASSWORD" "$CI_REGISTRY"   # pulls of pushed images (§9.3.3 fallback otherwise)
    - podman info --format 'rootless={{.Host.Security.Rootless}} net={{.Host.NetworkBackend}}'; ls -l /dev/kvm || true

.test-artifacts:
  artifacts:
    when: always
    expire_in: 14 days
    paths:
      - build/allure-report/
      - "**/build/allure-results/"
      - "**/build/vm-artifacts/"
      - "**/build/reports/"
    reports:
      junit: "**/build/test-results/**/TEST-*.xml"       # rendered in the merge request

# ---- check -----------------------------------------------------------------------------------------

platform-facts:            # GitlabSetup.md §4.6; cheap, keeps the platform documented
  stage: check
  tags: [vmtc-kvm]
  script:
    - nproc; free -g; df -h / | tail -1; uname -r; id; echo "XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-unset}"
    - test -c /dev/kvm && test -r /dev/kvm && test -w /dev/kvm && ls -l /dev/kvm
    - podman info --format 'rootless={{.Host.Security.Rootless}} runtime={{.Host.OCIRuntime.Name}} cgroup={{.Host.CgroupsVersion}} net={{.Host.NetworkBackend}}'
    - podman run --rm --device /dev/kvm registry.access.redhat.com/ubi9/ubi:9.8 sh -c 'ls -l /dev/kvm'

check:
  stage: check
  tags: [vmtc-small]
  image: eclipse-temurin:21-jdk
  extends: [.gradle-cache]
  interruptible: true
  script:
    - ./gradlew checkParameterHygiene checkDependencies checkRequirementTraceability
  artifacts:
    when: always
    paths: [build/reports/requirements/traceability.md]
    expire_in: 90 days

# ---- test (engine-free) ----------------------------------------------------------------------------

unit:
  stage: test
  needs: [check]
  tags: [vmtc-small]
  image: eclipse-temurin:21-jdk
  extends: [.gradle-cache, .test-artifacts]
  interruptible: true
  timeout: 20m
  script:
    - ./gradlew :lib:test :lib:shadedSmokeTest :junit5:test publishToMavenLocal allureReport
    # REQ-BLD-02 / REQ-BLD-08: coordinates and a POM without the shaded library
    - POM=$(find ~/.m2/repository -name '*.pom' -path "*/$(./gradlew -q printArtifactLib)/*" | head -1); test -n "$POM"
    - grep -q "<artifactId>$(./gradlew -q printArtifactLib)</artifactId>" "$POM"
    - "! grep -E 'org.apache.sshd|net.i2p.crypto' \"$POM\""

# ---- test (engine, ephemeral KVM VMs) --------------------------------------------------------------

integration:
  stage: test
  needs: [check, platform-facts]
  tags: [vmtc-kvm]
  extends: [.gradle-cache, .engine-session, .test-artifacts, .scheduled]
  interruptible: true
  parallel:
    matrix:
      - ACCEL: [kvm, tcg]
  timeout: 90m                                           # 30 min KVM / 90 min TCG (PLAN.md §12.1)
  script:
    - |
      case "$ACCEL" in
        kvm) MODE=REQUIRE ;;
        tcg) MODE=DISABLE; sudo rm -f /etc/udev/rules.d/99-kvm4all.rules; sudo chmod 0600 /dev/kvm ;;   # T20 relies on an unusable /dev/kvm
      esac
    - ./gradlew $GRADLE_EXTRA :it:integrationTest :junit5:extensionIT -Pvmtc.kvm=$MODE      # ${SYSPROP_PREFIX}.kvm
  after_script:
    - ./gradlew allureReport || true
  variables:
    ALLURE_JOB_NAME: "integration-$ACCEL"

compat:
  stage: test
  needs: [check, platform-facts]
  tags: [vmtc-kvm]
  extends: [.gradle-cache, .engine-session, .test-artifacts]
  interruptible: true
  parallel:
    matrix:
      - SCENARIO: [A, B, C]
  timeout: 30m
  script:
    - ./gradlew :images:verifyRunnerImages
    - it/fixtures/kvm-scenario.sh "$SCENARIO"            # writes DOCKER_HOST, VMTC_IT_KVM_SCENARIO, VMTC_IT_PODMAN_VERSION to kvm-scenario.env
    - set -a; . ./kvm-scenario.env; set +a
    - |
      if [ "$SCENARIO" = A ]; then ./gradlew :it:compatTest
      else sudo -iu "$USER" env XDG_RUNTIME_DIR="$XDG_RUNTIME_DIR" JAVA_HOME="$JAVA_HOME" DOCKER_HOST="$DOCKER_HOST" \
             TESTCONTAINERS_RYUK_DISABLED=true VMTC_IT_KVM_SCENARIO="$SCENARIO" VMTC_IT_PODMAN_VERSION="$VMTC_IT_PODMAN_VERSION" \
             GRADLE_USER_HOME="$GRADLE_USER_HOME" bash -c "cd '$CI_PROJECT_DIR' && ./gradlew --no-daemon :it:compatTest"
      fi
  after_script:
    - ./gradlew allureReport || true

oci-auth:
  stage: test
  needs: [check, platform-facts]
  tags: [vmtc-kvm]
  extends: [.gradle-cache, .engine-session, .test-artifacts]
  interruptible: true
  timeout: 45m
  script:
    - it/fixtures/test-registry.sh start                 # prints export lines; writes them to test-registry.env as well
    - set -a; . ./test-registry.env; set +a
    - ./gradlew :it:ociAuthTest -Pvmtc.kvm=REQUIRE
  after_script:
    - it/fixtures/test-registry.sh stop || true
    - ./gradlew allureReport || true

# ---- publish ---------------------------------------------------------------------------------------

publish-images:            # G3: the four images to the project Container Registry
  stage: publish
  needs: [integration, compat, oci-auth]
  tags: [vmtc-kvm]
  extends: [.gradle-cache, .engine-session]
  rules:
    - if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH
    - if: $CI_COMMIT_TAG
  timeout: 30m
  script:
    - ./gradlew :images:pushImages -PpushImages=true     # tags are ${IMAGE_REGISTRY}/<name>:<version>[-<suffix>] (PLAN.md §6.1)

publish-maven:             # G4: the library and the extension to the project Maven Package Registry
  stage: publish
  needs: [unit, integration, compat, oci-auth]
  tags: [vmtc-small]
  image: eclipse-temurin:21-jdk
  extends: [.gradle-cache]
  rules:
    - if: $CI_COMMIT_TAG
  script:
    - ./gradlew publishAllPublicationsToGitLabRepository   # repository "GitLab" (section 6.2); Job-Token from CI_JOB_TOKEN

# ---- pages (G5): the default branch's KVM Allure report ---------------------------------------------

pages:
  stage: pages
  needs:
    - job: "integration: [kvm]"
      artifacts: true
  tags: [vmtc-small]
  image: alpine:3.20
  rules:
    - if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH
  script:
    - mkdir -p public && cp -r build/allure-report/. public/
  artifacts:
    paths: [public]
    expire_in: 30 days
```

Reading notes for the implementer:

- **Job names with a matrix** are `integration: [kvm]`, `compat: [B]` and so on; `needs` on such a job uses that exact string (see `pages`).
- **`needs: [integration, …]`** on a plain job name waits for every matrix entry of that job.
- **The TCG entry** removes the udev rule and closes `/dev/kvm` on its own throwaway VM so that T20 (`KvmUnavailableException` under `REQUIRE`) is meaningful, the same effect as "the tcg job does not apply the udev rule" in PLAN.md §12.1.
- **`kvm-scenario.sh` and `test-registry.sh`** write their exports to `kvm-scenario.env` / `test-registry.env` in the workspace in addition to printing them (there is no `$GITHUB_ENV`); PLAN-STEPS.md steps 1.25 and 3.6 gain that line.
- **`printArtifactLib`** is a two-line Gradle task in the root build file printing `artifactLib`; it keeps the POM assertion free of a second copy of the parameter (REQ-BLD-01).
- **Allure environment:** `AllureEnvironment` reads `CI_COMMIT_SHA` instead of `GITHUB_SHA` (section 6.3) and names the report after `ALLURE_JOB_NAME` when set.
- **Schedules** live in the GitLab UI (CI/CD → Schedules), not in the file: create one weekly schedule on the default branch; the `.scheduled` rule turns it into `-PnoCache=true`.
- **Protected variables:** none are needed for the registries (`CI_REGISTRY_*` and `CI_JOB_TOKEN` are predefined); the bucket credentials live on the runner manager, never in the pipeline.
- **Timeouts** are per job and match PLAN.md §12.1; the project setting "timeout" must be ≥ 90 min or the job-level value is capped.

---

## 6. Gradle and library changes that follow from GitLab

### 6.1 `gradle.properties` / PLAN.md §0

| Parameter | GitLab value | Note |
|-----------|--------------|------|
| `${IMAGE_REGISTRY}` | the project's registry path, for example `registry.example.com/platform/vm-testcontainer` | Still one home (`gradle.properties`); equals `$CI_REGISTRY_IMAGE` for that project. Image names become `<registry path>/vmtc-runner:<version>-ubi9.8` and so on |
| `${MAVEN_REPO_URL}` (**new**) | `https://gitlab.example.com/api/v4/projects/<project id>/packages/maven` | The project-level endpoint; `gradle.properties` → `mavenRepoUrl`. Group-level publishing is possible but the project-level URL keeps one package list per project |
| `${REQ_DOC_URL}` | `https://gitlab.example.com/<group>/<project>/-/blob/main/docs/vm-testcontainer/REQUIREMENTS.md` | GitLab blob URL form |

### 6.2 Publishing (`lib/build.gradle`, `junit5/build.gradle`)

```groovy
publishing {
    repositories {
        maven {
            name = 'GitLab'
            url = uri(mavenRepoUrl)
            credentials(HttpHeaderCredentials) {
                name = System.getenv('CI_JOB_TOKEN') ? 'Job-Token' : 'Private-Token'
                value = System.getenv('CI_JOB_TOKEN') ?: System.getenv('GITLAB_TOKEN') ?: ''
            }
            authentication { header(HttpHeaderAuthentication) }
        }
    }
}
```

`publishAllPublicationsToGitLabRepository` is the task name Gradle derives from `name = 'GitLab'`. Consumers add the same URL as a repository with a `Deploy-Token` or `Private-Token` header (read-only deploy token with `read_package_registry`), which the README documents (PLAN-STEPS.md step 6.6).

### 6.3 Small code changes

- `AllureEnvironment`: `git.commit` from `CI_COMMIT_SHA` (fallback `GITHUB_SHA`, then `unknown`); `engine.version` from `${ENV_PREFIX}IT_ENGINE_VERSION` as before.
- `checkParameterHygiene` (PLAN.md §11.5): the cron grep on `.github/workflows/ci.yml` becomes `grep -q 'CI_PIPELINE_SOURCE == "schedule"' .gitlab-ci.yml` plus `grep -q 'noCache=true' .gitlab-ci.yml`; the weekly schedule itself is confirmed by hand (section 9, REQ-BLD-05).
- Root `build.gradle`: `tasks.register('printArtifactLib') { doLast { println artifactLib } }`.
- `images/build.gradle` `pushImages`: unchanged (the job logs in before it runs).

### 6.4 Image pulls by consumers

Guest containerDisks and the runner image are pulled by the engine (PLAN.md §9.3.3). On a private project the consumer needs `podman login registry.example.com` with a deploy token (`read_registry`) once; `VmImage.referenceGuest()` then works through Podman's own `auth.json` (verified path, PLAN.md §3.8). Public projects need nothing.

---

## 7. Edits the three planning documents need (not applied; listed for approval)

| Document | Place | Edit |
|----------|-------|------|
| PLAN.md | §0 | `${IMAGE_REGISTRY}` example value → a GitLab registry path; new row `${MAVEN_REPO_URL}`; `${REQ_DOC_URL}` example → GitLab blob URL |
| PLAN.md | §11.1, §11.5 | Add the publishing block of section 6.2 and `printArtifactLib`; replace the `ci.yml` cron grep with the `.gitlab-ci.yml` greps |
| PLAN.md | §12 | Replace the body with "superseded by GitlabSetup.md §5; keep §12.2 durations and §12.3 hygiene" |
| PLAN.md | §16 item 6 | Decided: GitLab Maven Package Registry (G4) |
| PLAN.md | §17 | Add this document to the evidence index as **[U]** until the first pipeline |
| REQUIREMENTS.md | REQ-PLAT-01 | "The CI `integration` matrix runs on `ubuntu-24.04` and `ubuntu-26.04`" → "runs on the project's KVM runner image (Ubuntu 24.04 x86-64)" |
| REQUIREMENTS.md | REQ-BLD-02, BLD-05, BLD-07 | "CI `unit` job" wording stays; BLD-05 acceptance → the `.scheduled` rule greps plus a manual schedule check; BLD-07 → "uploaded as job artifacts `if: always()`" |
| REQUIREMENTS.md | §3.5 | Artifact names → GitLab job artifacts; add the Pages location for the default branch |
| PLAN-STEPS.md | new step 0.7 | Provision the runner image and manager (sections 4.2–4.4), run `platform-facts` (4.6), record the facts in §D |
| PLAN-STEPS.md | 1.24, 1.25, 3.6, 3.7, 6.5, 6.7 | `.gitlab-ci.yml` instead of `ci.yml`; `.env` files instead of `$GITHUB_ENV`; `oci-auth` job as in section 5; 6.7 un-blocked: `publish-maven` on tags |
| PLAN-STEPS.md | §D | New row: "GitLab pipeline [U] until first run; platform facts: …" |

---

## 8. Costs and durations (estimates, **[U]**)

| Item | Estimate | Basis |
|------|----------|-------|
| Ephemeral VM start to job start | 60–120 s | cloud boot + SSH; `idle_count = 1` hides it for the first job |
| Per KVM job overhead before tests | runner image 51 s + recorder image ~1 min + two guest builds 1.5 min each (base image from the cache) | PLAN.md §12.2; no Podman layer cache on a fresh VM |
| `integration: [tcg]` | about 2 × 8 min guest builds + 20 min suite | PLAN.md §12.2 |
| Full pipeline wall clock | about the TCG job: 40–60 min | the other jobs run in parallel on their own VMs |
| Idle cost | zero with `idle_count = 0` | VMs exist only during jobs |

Optimisation, if the per-job image builds hurt: a `build-images` job on the `images` stage pushes the four images tagged `$CI_COMMIT_SHA` to the Container Registry, and the test jobs pull them by that tag (`-Pvmtc.runnerImage=…` and the `guestImage`/`blackBoxGuestImage` overrides, PLAN.md §9.1). It saves about 4 min per KVM job and 16 min on the TCG job, at the price of a registry round trip and one more job. Not in the normative file above; adopt it when the pipeline is green.

---

## 9. First-run checklist (what turns the [U] markers into [V])

1. `platform-facts` passes on an ephemeral VM: `/dev/kvm` 0666, `rootless=true`, `net=netavark`, `XDG_RUNTIME_DIR` set, the UBI container sees `/dev/kvm`. Record the output in PLAN-STEPS.md §D.
2. `unit` passes on `vmtc-small` with the shared cache populated (second run shows a Gradle cache hit in the job log).
3. `integration: [kvm]` passes with the `base.qcow2` cache hit on the second run; `integration: [tcg]` passes with T20 **not skipped** (the udev rule was removed on that VM).
4. `compat: [B]` and `[C]` pass: the `sudo -iu` JVM sees `/dev/kvm` readable and writable; the inspect JSON in the Allure report shows the annotation path (PLAN.md §9.6).
5. `oci-auth` passes: `/etc/hosts` edit and port 5443 work on the ephemeral VM.
6. The merge request shows the JUnit summary; the job pages offer the Allure artifact; the default branch's Pages URL serves `index.html` of the report.
7. `publish-images` on the default branch: the four images appear in the Container Registry; the next pipeline's `warnings.txt` no longer contains `using local image` for the guest images.
8. `publish-maven` on a tag: the two packages appear in the Package Registry; a scratch project resolves them with a deploy token.
9. The weekly schedule exists and its run shows `-PnoCache=true` in the Gradle command line.
10. Update PLAN.md §17 and PLAN-STEPS.md §D; change the status line at the top of this document.

---

## In Summary

- GitLab changes nothing about how the library is built or tested; it changes where the jobs run and where the outputs land.
- The one thing to prove early is the runner image: nested virtualization plus rootless Podman inside a job (section 4.6). Everything else is a transcription of the verified GitHub recipe into `.gitlab-ci.yml` (section 5).
- Autoscaled ephemeral VMs remove the host-mutation risk of `compat` and need a distributed cache in return.
- Both GitLab registries are adopted, which settles the publishing destination (PLAN.md §16 item 6).
- Section 7 lists the edits the three planning documents need; they are not applied yet.
