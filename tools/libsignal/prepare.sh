#!/bin/zsh
# Prepara os .so da libsignal para download. Reproduzível: mesmo AAR + mesmo NDK → mesmos hashes.
# Uso: tools/libsignal/prepare.sh [versão]   → tools/libsignal/out/<versão>/libsignal_jni-<abi>.so.gz
# Publicar os .gz no release "libsignal-<versão>" do GitHub e em /static/libsignal-<versão>/ do servidor.
# A última linha impressa é o valor do buildConfigField LIBSIGNAL_SO (abi:sha256:bytes do .so descomprimido).
set -e
V=${1:-0.86.5}
P=${0:a:h:h:h}
NDK=${NDK:-/opt/homebrew/share/android-commandlinetools/ndk/27.1.12297006}
STRIP=$(echo $NDK/toolchains/llvm/prebuilt/*/bin/llvm-strip)
OUT=$P/tools/libsignal/out/$V
TMP=$(mktemp -d)
trap 'rm -rf $TMP' EXIT
mkdir -p $OUT
# O mesmo AAR da parte Java do APK (rode um build antes); o diretório do cache do Gradle é o SHA-1 do arquivo.
AAR=$(echo $HOME/.gradle/caches/modules-2/files-2.1/org.signal/libsignal-android/$V/*/libsignal-android-$V.aar(N[1]))
[[ -f $AAR ]] || { echo "AAR $V fora do cache do Gradle: rode tools/gbuild.sh :app:assembleDebug antes" >&2; exit 1; }
[[ $(shasum -a 1 $AAR | cut -d' ' -f1) == ${AAR:h:t} ]] || { echo "SHA-1 do AAR não bate com o cache: $AAR" >&2; exit 1; }
unzip -q $AAR 'jni/*/libsignal_jni.so' -d $TMP
CFG=()
for abi in arm64-v8a armeabi-v7a x86_64; do
  so=$TMP/jni/$abi/libsignal_jni.so
  $STRIP --strip-all $so
  sha=$(shasum -a 256 $so | cut -d' ' -f1)
  size=$(stat -f%z $so)
  gzip -9 -n -c $so > $OUT/libsignal_jni-$abi.so.gz
  echo "$abi  $size B  gz $(stat -f%z $OUT/libsignal_jni-$abi.so.gz) B  sha256 $sha"
  CFG+=($abi:$sha:$size)
done
echo ${(j:,:)CFG}
