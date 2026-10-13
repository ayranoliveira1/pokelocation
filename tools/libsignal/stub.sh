#!/bin/zsh
# Gera o stub vazio libsignal_jni.so por ABI em app/src/main/jniLibs (spec §3.1).
# Native.loadLibrary("signal_jni") carrega o stub; os métodos JNI resolvem na lib real já carregada via System.load.
set -e
P=${0:a:h:h:h}
NDK=${NDK:-/opt/homebrew/share/android-commandlinetools/ndk/27.1.12297006}
BIN=$(echo $NDK/toolchains/llvm/prebuilt/*/bin)
SRC=$(mktemp -t stub).c
: > $SRC
for abi target in arm64-v8a aarch64-linux-android24 armeabi-v7a armv7a-linux-androideabi24 x86_64 x86_64-linux-android24 x86 i686-linux-android24; do
  mkdir -p $P/app/src/main/jniLibs/$abi
  $BIN/clang --target=$target -shared -nostdlib -fPIC -o $P/app/src/main/jniLibs/$abi/libsignal_jni.so $SRC \
    -Wl,-s -Wl,--build-id=none -Wl,-z,max-page-size=16384 -Wl,-soname,libsignal_jni.so
done
rm -f $SRC
ls -l $P/app/src/main/jniLibs/*/libsignal_jni.so
