#!/usr/bin/env bash
#
# Compila el núcleo Rust (nexanote-core) como librería nativa para Android y
# copia los .so resultantes a app/src/main/jniLibs/<abi>/.
#
# Requisitos:
#   - cargo + targets Android (rustup target add ...)
#   - NDK de Android. Se busca en (por orden): ANDROID_NDK_HOME, ANDROID_NDK_ROOT,
#     $ANDROID_HOME/ndk/<version>, $ANDROID_SDK_ROOT/ndk/<version>.
#
# Uso:  bash rust/build-android.sh [--debug]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JNI_LIBS="$REPO_ROOT/app/src/main/jniLibs"

PROFILE="release"
PROFILE_FLAG="--release"
if [[ "${1:-}" == "--debug" ]]; then
  PROFILE="debug"
  PROFILE_FLAG=""
fi

# --- Localizar el NDK ---------------------------------------------------------
find_ndk() {
  for c in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}"; do
    [[ -n "$c" && -d "$c" ]] && { echo "$c"; return; }
  done
  for sdk in "${ANDROID_NDK_LATEST_HOME:-}" "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
    [[ -n "$sdk" && -d "$sdk/ndk" ]] || continue
    local latest
    latest="$(ls -1 "$sdk/ndk" | sort -V | tail -n 1)"
    [[ -n "$latest" ]] && { echo "$sdk/ndk/$latest"; return; }
  done
  return 1
}

NDK="$(find_ndk || true)"
if [[ -z "$NDK" ]]; then
  echo "ERROR: no se encontró el NDK de Android. Define ANDROID_NDK_HOME." >&2
  exit 1
fi

HOST_TAG="linux-x86_64"
case "$(uname -s)" in
  Darwin) HOST_TAG="darwin-x86_64" ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
API=26

echo "NDK:       $NDK"
echo "Toolchain: $TOOLCHAIN"
echo "Perfil:    $PROFILE"

# rust target -> (abi de Android, prefijo del clang del NDK)
TARGETS=(
  "aarch64-linux-android:arm64-v8a:aarch64-linux-android"
  "armv7-linux-androideabi:armeabi-v7a:armv7a-linux-androideabi"
  "x86_64-linux-android:x86_64:x86_64-linux-android"
  "i686-linux-android:x86:i686-linux-android"
)

export AR="$TOOLCHAIN/llvm-ar"
export RANLIB="$TOOLCHAIN/llvm-ranlib"

for entry in "${TARGETS[@]}"; do
  IFS=":" read -r RUST_TARGET ABI CLANG_PREFIX <<< "$entry"
  CC_BIN="$TOOLCHAIN/${CLANG_PREFIX}${API}-clang"

  if [[ ! -x "$CC_BIN" ]]; then
    echo "ERROR: no existe el compilador $CC_BIN" >&2
    exit 1
  fi

  echo ""
  echo ">>> $RUST_TARGET ($ABI)"

  UPPER_TARGET="$(echo "$RUST_TARGET" | tr 'a-z-' 'A-Z_')"
  export "CC_${RUST_TARGET//-/_}=$CC_BIN"
  export "CXX_${RUST_TARGET//-/_}=${CC_BIN%-clang}-clang++"
  export "CARGO_TARGET_${UPPER_TARGET}_LINKER=$CC_BIN"

  ( cd "$SCRIPT_DIR" && cargo build -p nexanote-core --target "$RUST_TARGET" $PROFILE_FLAG )

  OUT="$SCRIPT_DIR/target/$RUST_TARGET/$PROFILE/libnexanote_core.so"
  mkdir -p "$JNI_LIBS/$ABI"
  cp "$OUT" "$JNI_LIBS/$ABI/libnexanote_core.so"
  echo "    -> $JNI_LIBS/$ABI/libnexanote_core.so"
done

echo ""
echo "OK: núcleo Rust compilado para todas las ABIs."
