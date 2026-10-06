#!/usr/bin/env bash
# Build Local Dream ET Server shell (Copyright (C) 2026 etc)
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"; cd "$HERE"
mkdir -p build build-win

# Linux shell (the real inference engine bin/sd-server comes from stable-diffusion.cpp, MIT)
gcc -O2 -pthread -o build/etserver src/etserver.c

# Windows shell (needs llvm-mingw / x86_64-w64-mingw32; see win/mingw-toolchain.cmake)
TC="${TC:-$HOME/tools/llvm-mingw-20260922-ucrt-ubuntu-22.04-x86_64}"
if [ -x "$TC/bin/x86_64-w64-mingw32-gcc" ]; then
  "$TC/bin/x86_64-w64-mingw32-windres" win/etserver.rc -O coff -o build-win/etserver.res
  "$TC/bin/x86_64-w64-mingw32-gcc" -O2 -D_WIN32_WINNT=0x0601 \
    -o build-win/etserver.exe src/etserver.c build-win/etserver.res \
    -Wl,-subsystem,console -lws2_32 -ladvapi32 -lshell32 -static -s
  echo "Built build/etserver and build-win/etserver.exe"
else
  echo "Linux shell built. Install llvm-mingw to build the Windows shell."
fi
