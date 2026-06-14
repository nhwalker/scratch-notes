#!/bin/bash
#
# Install the podman-in-podman GPU service as a systemd --user service.
# The unit simply runs start-podman-service.sh in foreground mode, so the run
# configuration lives in one place.
#
set -euo pipefail

SERVICE_NAME="podman-gpu-service"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
START_SCRIPT="${SCRIPT_DIR}/start-podman-service.sh"
UNIT_DIR="${XDG_CONFIG_HOME:-${HOME}/.config}/systemd/user"
UNIT_FILE="${UNIT_DIR}/${SERVICE_NAME}.service"

if [ ! -x "${START_SCRIPT}" ]; then
  echo "Making ${START_SCRIPT} executable"
  chmod +x "${START_SCRIPT}"
fi

mkdir -p "${UNIT_DIR}"

cat > "${UNIT_FILE}" <<EOF
[Unit]
Description=Rootful Podman-in-Podman GPU service
After=default.target

[Service]
Type=simple
ExecStart=${START_SCRIPT} --foreground
ExecStop=/usr/bin/podman rm -f ${SERVICE_NAME}
Restart=on-failure
RestartSec=5
TimeoutStopSec=30

[Install]
WantedBy=default.target
EOF

echo "Wrote ${UNIT_FILE}"

# Let the user service run without an active login session.
if command -v loginctl >/dev/null 2>&1; then
  loginctl enable-linger "$(id -un)" || \
    echo "WARN: could not enable linger; service may stop on logout" >&2
fi

systemctl --user daemon-reload
systemctl --user enable --now "${SERVICE_NAME}.service"

cat <<EOF

Installed and started ${SERVICE_NAME}.service (systemd --user).

  systemctl --user status ${SERVICE_NAME}
  journalctl --user -u ${SERVICE_NAME} -f

To remove:
  systemctl --user disable --now ${SERVICE_NAME}
  rm ${UNIT_FILE} && systemctl --user daemon-reload
EOF
