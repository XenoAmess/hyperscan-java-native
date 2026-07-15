#!/bin/bash

# Ensure to exit on all kinds of errors
set -xeu
set -o pipefail

VERSION="5.4.12"
SHA256="1ac4f3c038ac163973f107ac4423a6b246b181ffd97fdd371696b2517ec9b3ed"

detect_platform() {
  # use os-maven-plugin to detect platform
  local platform=$(mvn help:evaluate -Dexpression=os.detected.classifier -q -DforceStdout)
  # fix value for macosx: plugin outputs osx, but JavaCPP needs it to be macosx
  local fixOsName=${platform/osx/macosx}
  # fix value for arm64: plugin outputs aarch64, but JavaCPP needs it to be arm64
  echo ${fixOsName/aarch_64/arm64}
}

export DETECTED_PLATFORM=${DETECTED_PLATFORM:-$(detect_platform)}

cross_platform_nproc() {
  case $DETECTED_PLATFORM in
    macosx-x86_64|macosx-arm64) echo $(sysctl -n hw.logicalcpu) ;;
    linux-x86_64|linux-x86_64-avx2|linux-x86_64-baseline|linux-arm64|linux-arm64-baseline) echo $(nproc --all) ;;
    *) echo Unsupported Platform: $DETECTED_PLATFORM >&2 ; exit -1 ;;
  esac
}

cross_platform_check_sha() {
  local sha=$1
  local file=$2
  case $DETECTED_PLATFORM in
    macosx-x86_64|macosx-arm64) echo "$sha  $file" | shasum -a 256 -c ;;
    linux-x86_64|linux-x86_64-avx2|linux-x86_64-baseline|linux-arm64|linux-arm64-baseline) echo "$sha  $file" | sha256sum -c ;;
    *) echo Unsupported Platform: $DETECTED_PLATFORM >&2 ; exit -1 ;;
  esac
}

THREADS=$(cross_platform_nproc)

mkdir -p cppbuild/lib
mkdir -p cppbuild/bin
mkdir -p cppbuild/include/hs
cd cppbuild

curl -L -o vectorscan-$VERSION.tar.gz https://github.com/VectorCamp/vectorscan/archive/refs/tags/vectorscan/$VERSION.tar.gz
cross_platform_check_sha \
  $SHA256 \
  vectorscan-$VERSION.tar.gz
tar -xvf vectorscan-$VERSION.tar.gz
mv vectorscan-vectorscan-$VERSION vectorscan

curl -L -o boost_1_89_0.tar.gz https://archives.boost.io/release/1.89.0/source/boost_1_89_0.tar.gz
cross_platform_check_sha \
  9de758db755e8330a01d995b0a24d09798048400ac25c03fc5ea9be364b13c93 \
  boost_1_89_0.tar.gz
tar -zxf boost_1_89_0.tar.gz
mv boost_1_89_0/boost vectorscan/include/boost

curl -L -o ragel-6.10.tar.gz https://www.colm.net/files/ragel/ragel-6.10.tar.gz
cross_platform_check_sha \
  5f156edb65d20b856d638dd9ee2dfb43285914d9aa2b6ec779dac0270cd56c3f \
  ragel-6.10.tar.gz

tar -zxf ragel-6.10.tar.gz
cd ragel-6.10
./configure --prefix="$(pwd)/.."
make -j $THREADS
make install
cd ..

cd vectorscan

# Disable flakey sqlite detection - only needed to build auxillary tools anyways.
> cmake/sqlite3.cmake

# Compatibility patches for GCC 9 (devtoolset-9 in CentOS 7 toolchain):
# - vectorscan sets -march=x86-64-v2 when FAT_RUNTIME=off and AVX is disabled,
#   but GCC 9 does not recognize that alias. Use westmere instead.
# - GCC 9 supports -Wno-stringop-overflow but not -Wno-stringop-overread.
sed -i 's/-Wno-stringop-overread//' cmake/cflags-generic.cmake

case $DETECTED_PLATFORM in
linux-x86_64|linux-x86_64-avx2|linux-x86_64-baseline)
  # Determine SIMD tier for this linux-x86_64 variant.
  # See docs/architecture/linux-x86_64-multi-variant.md
  case $DETECTED_PLATFORM in
    linux-x86_64-baseline)
      MARCH="westmere"
      BUILD_AVX2=OFF
      BUILD_AVX512=OFF
      BUILD_AVX512VBMI=OFF
      # x86-64-v2 alias is not understood by GCC 9; replace with westmere.
      sed -i 's/set(X86_ARCH "x86-64-v2")/set(X86_ARCH "westmere")/' cmake/cflags-x86.cmake
      ;;
    linux-x86_64-avx2)
      MARCH="haswell"
      BUILD_AVX2=ON
      BUILD_AVX512=OFF
      BUILD_AVX512VBMI=OFF
      ;;
    linux-x86_64)
      MARCH="skylake-avx512"
      BUILD_AVX2=ON
      BUILD_AVX512=ON
      BUILD_AVX512VBMI=ON
      ;;
  esac

  CC=clang CXX=clang++ \
  cmake -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$(pwd)/.." \
        -DCMAKE_INSTALL_LIBDIR="lib" \
        -DPCRE_SOURCE="." \
        -DFAT_RUNTIME=off \
        -DBUILD_SHARED_LIBS=on \
        -DBUILD_AVX2=$BUILD_AVX2 \
        -DBUILD_AVX512=$BUILD_AVX512 \
        -DBUILD_AVX512VBMI=$BUILD_AVX512VBMI \
        -DBUILD_BENCHMARKS=false \
        -DBUILD_EXAMPLES=false \
        -DBUILD_TOOLS=false \
        -DBUILD_TESTING=OFF \
        -DCMAKE_C_FLAGS="-march=$MARCH -funroll-loops -fomit-frame-pointer -flto=thin" \
        -DCMAKE_CXX_FLAGS="-march=$MARCH -funroll-loops -fomit-frame-pointer -flto=thin" \
        -DCMAKE_EXE_LINKER_FLAGS="-fuse-ld=lld" \
        -DCMAKE_SHARED_LINKER_FLAGS="-fuse-ld=lld" \
        .
  sed -i "/int dummy;/s/int dummy;/int dummy{}/;" unit/gtest/gtest-all.cc 2>/dev/null || true
  make -j $THREADS install/strip
  ;;
linux-arm64|linux-arm64-baseline)
  # Determine SIMD tier for this linux-arm64 variant.
  # See docs/architecture/linux-arm64-multi-variant.md
  case $DETECTED_PLATFORM in
    linux-arm64-baseline)
      MARCH="armv8-a"
      BUILD_SVE=OFF
      BUILD_SVE2=OFF
      FAT_RUNTIME=off
      ;;
    linux-arm64)
      MARCH="armv9-a"
      BUILD_SVE=ON
      BUILD_SVE2=ON
      FAT_RUNTIME=off
      ;;
  esac

  # The X86 sed is a no-op on ARM but kept for build script uniformity.
  sed -i 's/set(X86_ARCH "x86-64-v2")/set(X86_ARCH "westmere")/' cmake/cflags-x86.cmake
  CC="clang" CXX="clang++" cmake \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$(pwd)/.." \
        -DCMAKE_INSTALL_LIBDIR="lib" \
        -DPCRE_SOURCE="." \
        -DFAT_RUNTIME=$FAT_RUNTIME \
        -DBUILD_SHARED_LIBS=on \
        -DBUILD_SVE=$BUILD_SVE \
        -DBUILD_SVE2=$BUILD_SVE2 \
        -DCMAKE_C_FLAGS="-march=$MARCH" \
        -DCMAKE_CXX_FLAGS="-march=$MARCH" \
        -DBUILD_BENCHMARKS=false \
        -DBUILD_EXAMPLES=false \
        .
  sed -i "/int dummy;/s/int dummy;/int dummy{}/;" unit/gtest/gtest-all.cc 2>/dev/null || true
  make -j $THREADS install/strip
  ;;
macosx-x86_64|macosx-arm64)
  sed -i 's/set(X86_ARCH "x86-64-v2")/set(X86_ARCH "westmere")/' cmake/cflags-x86.cmake
  export MACOSX_DEPLOYMENT_TARGET=12
  cmake -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$(pwd)/.." -DCMAKE_INSTALL_LIBDIR="lib" -DARCH_OPT_FLAGS='-Wno-error' -DPCRE_SOURCE="." -DBUILD_SHARED_LIBS=on . -DFAT_RUNTIME=off -DBUILD_BENCHMARKS=false
  sed -i "/int dummy;/s/int dummy;/int dummy{}/;" unit/gtest/gtest-all.cc 2>/dev/null || true
  make -j $THREADS install/strip
  ;;
*)
  echo "Error: Arch \"$DETECTED_PLATFORM\" is not supported"
  ;;
esac

cd ../..

mvn -B -DskipTests -Dorg.bytedeco.javacpp.platform=$DETECTED_PLATFORM