#!/usr/bin/env bash
# Spike: record one VNC server to WebM/VP8 with GStreamer. Usage: gst-record.sh <vnc-port> <out.webm> [fps]
# Stop with SIGINT (gst-launch -e turns it into EOS so the file is finalized).
exec gst-launch-1.0 -q -e rfbsrc host="${VNC_HOST:-127.0.0.1}" port="$1" view-only=true shared=true incremental=false ${VNC_PASSWORD:+password=$VNC_PASSWORD} \
  ! videorate ! "video/x-raw,framerate=${3:-10}/1" ! videoconvert \
  ! vp8enc deadline=1 cpu-used=8 threads=2 target-bitrate=1000000 ! webmmux ! filesink location="$2"
