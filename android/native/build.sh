#!/bin/sh
# Builds SpoX Engine's two small Linux helpers into the app's assets (needs clang + lld and
# gcc-aarch64-linux-gnu; the GitHub workflows install them).
#  spoxrun                 runs Soloist and answers the Linux calls Android blocks
#  libpipewire-0.3.so.0    stands in for PipeWire and hands Soloist's sound to Android
set -e
cd "$(dirname "$0")"
out=../app/src/main/assets/engine
mkdir -p "$out"
clang --target=aarch64-linux-gnu -fuse-ld=lld -static -nostdlib -fno-pic -O2 -ffreestanding \
  -fno-stack-protector -fno-builtin -o "$out/spoxrun" spoxrun.c
aarch64-linux-gnu-gcc -shared -fPIC -O2 -fvisibility=hidden -Wl,-soname,libpipewire-0.3.so.0 \
  -o "$out/libpipewire-0.3.so.0" pwshim.c -lpthread
ls -la "$out"
