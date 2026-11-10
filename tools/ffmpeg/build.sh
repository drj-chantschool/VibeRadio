#!/usr/bin/env bash
# Builds libvrwma.so (FFmpeg's WMA decoders behind tools/ffmpeg/vrwma.c) for each Android ABI into
# app/src/main/jniLibs/<abi>/, where Gradle packs it into the APK. The app builds and runs without
# these files (WMA files are then skipped), so Gradle never calls this: CI and release.yml do.
#
#   tools/ffmpeg/build.sh                 all ABIs
#   tools/ffmpeg/build.sh arm64-v8a       just those named
#   tools/ffmpeg/build.sh host            a Linux build with a test program, to check the C core
#
# Needs git, make, and for Android an NDK (ANDROID_NDK_HOME, ANDROID_NDK_ROOT, ANDROID_NDK_LATEST_HOME,
# or the newest under $ANDROID_HOME/ndk). FFmpeg is built LGPL-only, statically linked, without
# assembly (nothing here is hot), and with only the four WMA decoders.
set -euo pipefail

FFMPEG_TAG="n7.1.1"
API=26

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
work="${VRWMA_WORK:-$here/build}"
src="${FFMPEG_SRC:-$work/ffmpeg-$FFMPEG_TAG}"
jni_libs="$root/app/src/main/jniLibs"

mkdir -p "$work"
if [ ! -f "$src/configure" ]; then
    rm -rf "$src"
    GIT_LFS_SKIP_SMUDGE=1 git clone --quiet --depth 1 --branch "$FFMPEG_TAG" https://github.com/FFmpeg/FFmpeg "$src"
fi

common=(
    --disable-everything --disable-programs --disable-doc --disable-debug
    --disable-avdevice --disable-avformat --disable-avfilter --disable-swscale
    --disable-swresample --disable-postproc --disable-network --disable-autodetect
    --disable-asm --enable-static --disable-shared --enable-pic --enable-small
    --enable-decoder=wmav1,wmav2,wmapro,wmalossless
)

jobs="$(nproc 2>/dev/null || echo 4)"

# Runs a command with its output kept in $log, shown (the tail) only if it fails.
log="$work/build.log"
quiet() {
    if ! "$@" >"$log" 2>&1; then
        echo "FAILED: $*" >&2
        tail -n 60 "$log" >&2
        exit 1
    fi
}

build_host() {
    local prefix="$work/host"
    rm -rf "$prefix" "$work/obj-host"
    mkdir -p "$work/obj-host"
    (cd "$work/obj-host" && quiet "$src/configure" --prefix="$prefix" "${common[@]}" && quiet make -j"$jobs" && quiet make install)
    cc -O2 -Wall -I"$prefix/include" "$here/vrwma.c" "$here/vrwma_test.c" -o "$work/vrwma_test" \
        "$prefix/lib/libavcodec.a" "$prefix/lib/libavutil.a" -lm -lpthread
    echo "built $work/vrwma_test"
}

ndk_root() {
    local c
    for c in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}" "${ANDROID_NDK_LATEST_HOME:-}"; do
        if [ -n "$c" ] && [ -d "$c/toolchains/llvm/prebuilt" ]; then echo "$c"; return; fi
    done
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -n "$sdk" ] && [ -d "$sdk/ndk" ]; then
        c="$(ls -d "$sdk"/ndk/*/ 2>/dev/null | sort -V | tail -1)"
        if [ -n "$c" ]; then echo "${c%/}"; return; fi
    fi
    echo "no Android NDK found (set ANDROID_NDK_HOME)" >&2
    exit 1
}

build_android() {
    local abi="$1" triple arch cpu
    case "$abi" in
        arm64-v8a) triple=aarch64-linux-android; arch=aarch64; cpu=armv8-a ;;
        armeabi-v7a) triple=armv7a-linux-androideabi; arch=arm; cpu=armv7-a ;;
        x86_64) triple=x86_64-linux-android; arch=x86_64; cpu=x86-64 ;;
        *) echo "unknown ABI $abi" >&2; exit 1 ;;
    esac
    local ndk tc prefix obj
    ndk="$(ndk_root)"
    tc="$(echo "$ndk"/toolchains/llvm/prebuilt/*)/bin"
    prefix="$work/$abi"
    obj="$work/obj-$abi"
    rm -rf "$prefix" "$obj"
    mkdir -p "$obj"
    echo "== FFmpeg $FFMPEG_TAG for $abi (NDK $ndk)"
    (
        cd "$obj"
        quiet "$src/configure" --prefix="$prefix" "${common[@]}" \
            --target-os=android --arch="$arch" --cpu="$cpu" --enable-cross-compile \
            --cc="$tc/${triple}${API}-clang" --cxx="$tc/${triple}${API}-clang++" \
            --ar="$tc/llvm-ar" --nm="$tc/llvm-nm" --ranlib="$tc/llvm-ranlib" --strip="$tc/llvm-strip" \
            --extra-cflags="-O2 -fPIC -ffunction-sections -fdata-sections"
        quiet make -j"$jobs"
        quiet make install
    )
    mkdir -p "$jni_libs/$abi"
    "$tc/${triple}${API}-clang" -shared -fPIC -O2 -DVRWMA_JNI -I"$prefix/include" "$here/vrwma.c" \
        -o "$jni_libs/$abi/libvrwma.so" \
        "$prefix/lib/libavcodec.a" "$prefix/lib/libavutil.a" \
        -lm -Wl,--no-undefined -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 -Wl,-z,noexecstack
    "$tc/llvm-strip" --strip-unneeded "$jni_libs/$abi/libvrwma.so"
    ls -l "$jni_libs/$abi/libvrwma.so"
}

if [ "$#" -eq 0 ]; then set -- arm64-v8a armeabi-v7a x86_64; fi
for abi in "$@"; do
    if [ "$abi" = host ]; then build_host; else build_android "$abi"; fi
done
