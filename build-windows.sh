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
    windows-x86_64|windows-x86_64-avx2|windows-x86_64-baseline) echo $(nproc --all) ;;
    *) echo Unsupported Platform: $DETECTED_PLATFORM >&2 ; exit -1 ;;
  esac
}

cross_platform_check_sha() {
  local sha=$1
  local file=$2
  case $DETECTED_PLATFORM in
    macosx-x86_64|macosx-arm64) echo "$sha  $file" | shasum -a 256 -c ;;
    linux-x86_64|linux-x86_64-avx2|linux-x86_64-baseline|linux-arm64|linux-arm64-baseline) echo "$sha  $file" | sha256sum -c ;;
    windows-x86_64|windows-x86_64-avx2|windows-x86_64-baseline) echo "$sha  $file" | sha256sum -c ;;
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
windows-x86_64|windows-x86_64-avx2|windows-x86_64-baseline)
  case $DETECTED_PLATFORM in
    windows-x86_64-baseline)
      ARCH_FLAGS=""
      BUILD_AVX2=OFF
      BUILD_AVX512=OFF
      BUILD_AVX512VBMI=OFF
      ;;
    windows-x86_64-avx2)
      ARCH_FLAGS="-arch:AVX2"
      BUILD_AVX2=ON
      BUILD_AVX512=OFF
      BUILD_AVX512VBMI=OFF
      ;;
    windows-x86_64)
      ARCH_FLAGS="-arch:AVX512"
      BUILD_AVX2=ON
      BUILD_AVX512=ON
      BUILD_AVX512VBMI=ON
      ;;
  esac

  cmake -G "Visual Studio 17 2022" -A x64 \
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$(pwd)/.." \
        -DCMAKE_INSTALL_LIBDIR="lib" \
        -DBUILD_SHARED_LIBS=on \
        -DBUILD_AVX2=$BUILD_AVX2 \
        -DBUILD_AVX512=$BUILD_AVX512 \
        -DBUILD_AVX512VBMI=$BUILD_AVX512VBMI \
        -DFAT_RUNTIME=off \
        -DCMAKE_C_FLAGS="$ARCH_FLAGS" \
        -DCMAKE_CXX_FLAGS="$ARCH_FLAGS" \
        .

  cmake --build . --config Release --target install -- -maxcpucount:$THREADS
  ;;
*)
  echo "Error: Arch \"$DETECTED_PLATFORM\" is not supported by build-windows.sh"
  exit 1
  ;;
esac

cd ../..

mvn -B -DskipTests -Dorg.bytedeco.javacpp.platform=$DETECTED_PLATFORM
