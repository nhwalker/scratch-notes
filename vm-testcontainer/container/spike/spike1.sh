#!/usr/bin/env bash
# Spike #1: can UBI 9.8 install qemu-kvm (+ ffmpeg-free) using UBI -> EPEL -> Rocky 9.8 fallback?
# Runs INSIDE registry.access.redhat.com/ubi9/ubi:9.8 with this dir mounted at /spike.
set -uo pipefail
# Sandbox-only: trust an intercepting proxy CA and route dnf through the proxy when provided.
if [ -f /extra-ca.crt ]; then cp /extra-ca.crt /etc/pki/ca-trust/source/anchors/ && update-ca-trust; fi
if [ -n "${HTTPS_PROXY:-}" ]; then echo "proxy=${HTTPS_PROXY}" >> /etc/dnf/dnf.conf; fi
cp /spike/repos/*.repo /etc/yum.repos.d/
# Make UBI repos explicitly preferred
for f in /etc/yum.repos.d/ubi.repo; do sed -i '/^\[/a priority=10' "$f"; done
rpm --import https://dl.rockylinux.org/pub/rocky/RPM-GPG-KEY-Rocky-9
rpm --import https://dl.fedoraproject.org/pub/epel/RPM-GPG-KEY-EPEL-9

echo "=== baseline"; rpm -qa --qf '%{NAME}\n' | sort > /tmp/before.txt; rpm -q redhat-release
rpm -qa --qf '%{NAME} %{VERSION}-%{RELEASE}\n' | sort > /tmp/before-ver.txt

PKGS_CORE="qemu-kvm-core qemu-img edk2-ovmf seabios-bin"
PKGS_MEDIA="ffmpeg-free"
PKGS_TOOLS="xorriso jq socat passt"

for set in CORE MEDIA TOOLS; do
  eval pk=\$PKGS_$set
  echo "=== install $set: $pk"
  dnf -y -q --setopt=install_weak_deps=False install $pk > /tmp/install-$set.log 2>&1; echo "exit=$?"
  tail -15 /tmp/install-$set.log
done

echo "=== origin repo of every package installed during spike"
rpm -qa --qf '%{NAME}\n' | sort > /tmp/after.txt
comm -13 /tmp/before.txt /tmp/after.txt > /tmp/new.txt
dnf -q repoquery --installed --qf '%{from_repo} %{name}' $(cat /tmp/new.txt) 2>/dev/null | awk '{print $1}' | sort | uniq -c
echo "--- packages from rocky:"; dnf -q repoquery --installed --qf '%{from_repo} %{name}-%{version}-%{release}' $(cat /tmp/new.txt) 2>/dev/null | grep -i rocky | sort
echo "--- packages from epel:"; dnf -q repoquery --installed --qf '%{from_repo} %{name}-%{version}-%{release}' $(cat /tmp/new.txt) 2>/dev/null | grep -i epel | sort

echo "=== were any pre-existing UBI packages upgraded/replaced?"
rpm -qa --qf '%{NAME} %{VERSION}-%{RELEASE}\n' | sort > /tmp/after-ver.txt
join /tmp/before-ver.txt /tmp/after-ver.txt | awk '$2!=$3' || true
comm -23 /tmp/before.txt /tmp/after.txt | sed 's/^/REMOVED: /'
rpm -q redhat-release; rpm -qa 'rocky-*'

echo "=== rpm -Va (config/doc noise filtered)"; rpm -Va 2>/dev/null | grep -v ' [cdg] ' | head -20; echo "(end rpm -Va)"

echo "=== qemu sanity"
/usr/libexec/qemu-kvm -version
/usr/libexec/qemu-kvm -accel help
/usr/libexec/qemu-kvm -netdev help 2>&1 | head -20
/usr/libexec/qemu-kvm -audiodev help
/usr/libexec/qemu-kvm -device help > /spike/out/device-help.txt 2>&1
for d in virtio-vga VGA bochs-display qemu-xhci usb-storage usb-kbd usb-tablet usb-mouse pcie-root-port virtio-blk-pci virtio-net-pci virtio-serial-pci virtserialport pc-dimm ich9-intel-hda hda-duplex usb-audio virtio-sound-pci ide-cd scsi-cd virtio-scsi-pci virtio-rng-pci; do
  grep -q "name \"$d\"" /spike/out/device-help.txt && echo "DEV OK      $d" || echo "DEV MISSING $d"
done
echo "=== ffmpeg encoders"
command -v ffmpeg && ffmpeg -hide_banner -encoders 2>/dev/null | grep -Ei 'vp8|vp9|mjpeg|opus|vorbis|pcm_s16le|h264|av1' 
