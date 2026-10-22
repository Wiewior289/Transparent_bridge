#!/bin/bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
cd "$DIR"

echo "========================================================="
echo "🛠️ ROZPOCZĘCIE LOW-LEVEL KOMPILACJI C-SHARED DLA ANDROIDA"
echo "========================================================="

# 1. Przygotowanie czystej struktury jniLibs w projekcie
mkdir -p ../jniLibs/arm64-v8a

# 2. Definicja zmiennych dla Android NDK (Kompilator C/C++)
# Go potrzebuje zewnętrznego linkera C (CGO), aby wygenerować JNI .so
# Upewnij się, że masz zainstalowane Android NDK i ustawioną zmienną ANDROID_NDK_HOME w systemie!
if [ -z "$ANDROID_NDK_HOME" ]; then
    echo "❌ BŁĄD: Zmienna ANDROID_NDK_HOME nie jest ustawiona w systemie!"
    echo "Wskaż ścieżkę do NDK (np. export ANDROID_NDK_HOME=/path/to/android-sdk/ndk/25.x.x)"
    exit 1
fi

# Wykrywanie systemu operacyjnego hosta (Linux / macOS)
OS_NAME=$(uname | tr '[:upper:]' '[:lower:]')
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/${OS_NAME}-x86_64"

# Ustawiamy kompilator Clang z NDK dla architektury ARM64 (Android API 26+)
export CC="$TOOLCHAIN/bin/aarch64-linux-android26-clang"

# 3. Prawdziwa kompilacja Go do natywnej biblioteki JNI .so
export CGO_ENABLED=1
export GOOS=android
export GOARCH=arm64

echo "🚀 Kompilacja źródła bridge.go do libtsnetbridge.so (arm64-v8a)..."
go build -buildmode=c-shared -o ../jniLibs/arm64-v8a/libtsnetbridge.so bridge.go

echo "========================================================="
echo "✅ SUKCES! Plik libtsnetbridge.so wygenerowany prawidłowo."
echo "Ścieżka: app/src/main/jniLibs/arm64-v8a/libtsnetbridge.so"
echo "========================================================="
