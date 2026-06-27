#!/bin/bash

# Build native Windows libraries from Intel Hyperscan source.
# Runs on Git Bash / MSYS2 / WSL, primarily intended for GitHub Actions windows-latest.

set -xeu
set -o pipefail

VERSION="5.4.2"
SHA256="32b0f24b3113bbc46b6bfaa05cf7cf45840b6b59333d078cc1f624e4c40b2b99"
BOOST_SHA256="9de758db755e8330a01d995b0a24d09798048400ac25c03fc5ea9be364b13c93"

detect_platform() {
  local platform=$(mvn help:evaluate -Dexpression=os.detected.classifier -q -DforceStdout)
  local fixOsName=${platform/osx/macosx}
  echo ${fixOsName/aarch_64/arm64}
}

export DETECTED_PLATFORM=${DETECTED_PLATFORM:-$(detect_platform)}

cross_platform_nproc() {
  case $DETECTED_PLATFORM in
    macosx-x86_64|macosx-arm64) echo $(sysctl -n hw.logicalcpu) ;;
    linux-x86_64|linux-x86_64-avx2|linux-x86_64-baseline|linux-arm64|linux-arm64-baseline) echo $(nproc --all) ;;
    windows-x86_64|windows-x86_64-baseline) echo $(nproc --all) ;;
    *) echo Unsupported Platform: $DETECTED_PLATFORM >&2 ; exit -1 ;;
  esac
}

cross_platform_check_sha() {
  local sha=$1
  local file=$2
  case $DETECTED_PLATFORM in
    macosx-x86_64|macosx-arm64) echo "$sha  $file" | shasum -a 256 -c ;;
    linux-x86_64|linux-x86_64-avx2|linux-x86_64-baseline|linux-arm64|linux-arm64-baseline) echo "$sha  $file" | sha256sum -c ;;
    windows-x86_64|windows-x86_64-baseline) echo "$sha  $file" | sha256sum -c ;;
    *) echo Unsupported Platform: $DETECTED_PLATFORM >&2 ; exit -1 ;;
  esac
}

THREADS=$(cross_platform_nproc)

mkdir -p cppbuild/lib
mkdir -p cppbuild/bin
mkdir -p cppbuild/include/hs
cd cppbuild

curl -L -o hyperscan-${VERSION}.tar.gz https://github.com/intel/hyperscan/archive/refs/tags/v${VERSION}.tar.gz
cross_platform_check_sha \
  $SHA256 \
  hyperscan-${VERSION}.tar.gz
tar -xvf hyperscan-${VERSION}.tar.gz
mv hyperscan-${VERSION} hyperscan

curl -L -o boost_1_89_0.tar.gz https://archives.boost.io/release/1.89.0/source/boost_1_89_0.tar.gz
cross_platform_check_sha \
  $BOOST_SHA256 \
  boost_1_89_0.tar.gz
tar -zxf boost_1_89_0.tar.gz
mv boost_1_89_0/boost hyperscan/include/boost

cd hyperscan

# Disable flakey sqlite detection - only needed to build auxillary tools anyways.
> cmake/sqlite3.cmake

case $DETECTED_PLATFORM in
windows-x86_64|windows-x86_64-baseline)
  # The upstream Intel Hyperscan CMakeLists always adds unit/tools/chimera,
  # which require PCRE. We only need the runtime/compile libraries, so remove
  # those optional subdirectories.
  sed -i '/add_subdirectory(unit)/d' CMakeLists.txt
  sed -i '/add_subdirectory(tools)/d' CMakeLists.txt
  sed -i '/add_subdirectory(chimera)/d' CMakeLists.txt

  # Intel Hyperscan 5.4.2 does not support AVX-512 on Windows (its MSVC path
  # in cmake/arch.cmake does not set SKYLAKE_FLAG). Build two tiers only:
  # baseline (SSE4.2/NEON-class) and AVX2. The AVX2 build is published under
  # the plain windows-x86_64 classifier.
  case $DETECTED_PLATFORM in
    windows-x86_64-baseline)
      ARCH_FLAGS=""
      BUILD_AVX2=OFF
      BUILD_AVX512=OFF
      BUILD_AVX512VBMI=OFF
      ;;
    windows-x86_64)
      ARCH_FLAGS="-arch:AVX2"
      BUILD_AVX2=ON
      BUILD_AVX512=OFF
      BUILD_AVX512VBMI=OFF
      ;;
  esac

  cmake -G "Visual Studio 18 2026" -A x64 \
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$(pwd)/.." \
        -DCMAKE_INSTALL_LIBDIR="lib" \
        -DBUILD_SHARED_LIBS=on \
        -DBUILD_AVX2=$BUILD_AVX2 \
        -DBUILD_AVX512=$BUILD_AVX512 \
        -DBUILD_AVX512VBMI=$BUILD_AVX512VBMI \
        -DFAT_RUNTIME=off \
        -DPYTHON_EXECUTABLE="$(command -v python)" \
        -DCMAKE_C_FLAGS="$ARCH_FLAGS" \
        -DCMAKE_CXX_FLAGS="$ARCH_FLAGS /EHsc" \
        .

  cmake --build . --config Release --target install -- -maxcpucount:$THREADS

  # CMake installs import libraries into lib/ but the runtime DLLs into bin/.
  # JavaCPP's copyLibs step looks in linkPath (lib/) for .dll files, so copy
  # the DLLs alongside the import libraries.
  cp "$(pwd)/../bin/"*.dll "$(pwd)/../lib/" 2>/dev/null || true
  ;;
*)
  echo "Error: Arch \"$DETECTED_PLATFORM\" is not supported by build-windows.sh"
  exit 1
  ;;
esac

cd ../..

# JavaCPP only ships built-in properties for plain windows-x86_64. For the
# baseline tier we still build with the standard windows-x86_64 compiler settings
# (so cl.exe and .dll are used) and then repackage the native artifacts under the
# windows-x86_64-baseline classifier.
JAVACPP_PLATFORM="$DETECTED_PLATFORM"
CLASSIFIER="$DETECTED_PLATFORM"
case "$DETECTED_PLATFORM" in
  windows-x86_64-baseline)
    JAVACPP_PLATFORM="windows-x86_64"
    ;;
esac

mvn -B -DskipTests -Dorg.bytedeco.javacpp.platform="$JAVACPP_PLATFORM"

VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)

# Repackage baseline native directory so the runtime Loader can find it under
# the windows-x86_64-baseline platform.
if [ "$CLASSIFIER" != "$JAVACPP_PLATFORM" ]; then
  STAGING_DIR="target/staging-deploy/com/xenoamess/hyperscan/native/${VERSION}"
  BASE_JAR="${STAGING_DIR}/native-${VERSION}-${JAVACPP_PLATFORM}.jar"
  TARGET_JAR="${STAGING_DIR}/native-${VERSION}-${CLASSIFIER}.jar"
  if [ -f "$BASE_JAR" ]; then
    TMPDIR=$(mktemp -d)
    unzip -q -o "$BASE_JAR" -d "$TMPDIR"
    mv "${TMPDIR}/com/gliwka/hyperscan/jni/${JAVACPP_PLATFORM}" "${TMPDIR}/com/gliwka/hyperscan/jni/${CLASSIFIER}"
    (cd "$TMPDIR" && zip -r "$TARGET_JAR" .)
    rm -rf "$TMPDIR"
    rm -f "$BASE_JAR"
  fi
fi
