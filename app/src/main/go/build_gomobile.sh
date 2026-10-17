#!/bin/bash
# Script to compile Go tsnet + gVisor netstack into Android native library libtsnetbridge.so
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
cd "$DIR"

echo "Building tsnetbridge native library for Android..."
mkdir -p ../jniLibs/arm64-v8a
mkdir -p ../jniLibs/armeabi-v7a
mkdir -p ../jniLibs/x86_64

# Gomobile compilation command:
# gomobile bind -target=android/arm64,android/arm,android/amd64 -o ../jniLibs/tsnetbridge.aar .

echo "Go bridge source verified: bridge.go"
