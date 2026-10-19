#!/usr/bin/env python3
"""Guest-side spike helper: force a modeset on /dev/fbN (FBIOPUT_VSCREENINFO with FB_ACTIVATE_FORCE),
then unblank it. Lets a secondary bochs-drm head scan out without a desktop session."""
import fcntl, sys, os
FBIOGET_VSCREENINFO, FBIOPUT_VSCREENINFO, FBIOBLANK = 0x4600, 0x4601, 0x4611
fd = os.open(sys.argv[1], os.O_RDWR)
buf = bytearray(160)                       # struct fb_var_screeninfo is 160 bytes
fcntl.ioctl(fd, FBIOGET_VSCREENINFO, buf)
act = 24 + 4*4 + 4*3 * 4                   # offset of 'activate' (after xres..grayscale + 4 bitfields)
# fb_var_screeninfo: 8 u32 (xres..bits_per_pixel, grayscale) = 32 bytes, 4 fb_bitfield (12 bytes each) = 48 -> 80, nonstd @80, activate @84
act = 84
buf[act:act+4] = (0x80).to_bytes(4, "little")   # FB_ACTIVATE_FORCE | FB_ACTIVATE_NOW(0)
fcntl.ioctl(fd, FBIOPUT_VSCREENINFO, buf)
try: fcntl.ioctl(fd, FBIOBLANK, 0)
except OSError as e: print("unblank:", e)
print("activated", sys.argv[1], int.from_bytes(buf[0:4], "little"), "x", int.from_bytes(buf[4:8], "little"))
