#!/usr/bin/env bash
# Installs the spike runner packages on ubi9/ubi:9.8 using UBI -> EPEL -> Rocky 9.8 (fallback).
# Usage: install-packages.sh [slim|media]   (media = + GStreamer for VNC screen recording)   (repo files expected in /spike/repos)
set -euo pipefail
VARIANT=${1:-slim}
cp /spike/repos/*.repo /etc/yum.repos.d/
grep -q '^priority=10' /etc/yum.repos.d/ubi.repo || sed -i '/^\[/a priority=10' /etc/yum.repos.d/ubi.repo
rpm --import https://dl.rockylinux.org/pub/rocky/RPM-GPG-KEY-Rocky-9
rpm --import https://dl.fedoraproject.org/pub/epel/RPM-GPG-KEY-EPEL-9
PKGS="qemu-kvm-core qemu-img edk2-ovmf seabios-bin xorriso jq socat python3"
[ "$VARIANT" = media ] && PKGS="$PKGS gstreamer1 gstreamer1-plugins-base gstreamer1-plugins-base-tools gstreamer1-plugins-good gstreamer1-plugins-bad-free"
dnf -y --setopt=install_weak_deps=False install $PKGS
dnf clean all
