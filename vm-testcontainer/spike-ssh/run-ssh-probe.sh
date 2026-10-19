#!/usr/bin/env bash
# Spikes A/C runner (GitHub runner, rootless podman). Usage:
#   run-ssh-probe.sh <label> <kvm|tcg> <guest.qcow2> <keydir> <expectAgent true|false>
# Env: IMAGE (runner image). Starts a podman API service on a private socket, runs the Testcontainers probe,
# writes target/ssh-probe-<label>-<accel>.txt (+ serial/container logs next to it) and prints it.
set -uo pipefail
LABEL=$1 ACCEL=$2 DISK=$(readlink -f "$3") KEYDIR=$(readlink -f "$4") EXPECT_AGENT=${5:-true}
cd "$(dirname "$0")"
SPIKE_DIR=$(readlink -f ../container/spike)
SOCK="$XDG_RUNTIME_DIR/podman/podman-ssh-$LABEL-$ACCEL.sock"; mkdir -p "$(dirname "$SOCK")"
podman system service --time=0 "unix://$SOCK" & SVC=$!; sleep 3
mkdir -p target/results
JAR=$PWD/target/vm-testcontainer-spike-ssh-0.0.0-SNAPSHOT-shaded.jar
[ -f "$JAR" ] || mvn -B -q -Peddsa -DskipTests package
RES=$PWD/target/results/ssh-probe-$LABEL-$ACCEL.txt
DOCKER_HOST="unix://$SOCK" TESTCONTAINERS_RYUK_DISABLED=true mvn -B -q -Peddsa test \
  -Dprobe.image="$IMAGE" -Dprobe.disk="$DISK" -Dprobe.accel="$ACCEL" -Dprobe.keyDir="$KEYDIR" -Dprobe.spikeDir="$SPIKE_DIR" \
  -Dprobe.results="$RES" -Dprobe.label="$LABEL" -Dprobe.expectAgent="$EXPECT_AGENT" -Dprobe.shadedJar="$JAR" \
  || echo "MVN_FAILED" >> "$RES"
echo "=== ssh-probe $LABEL $ACCEL ($(podman --version))"; cat "$RES"
kill "$SVC" 2>/dev/null; pkill -f "podman system service --time=0 unix://$SOCK" 2>/dev/null; true
