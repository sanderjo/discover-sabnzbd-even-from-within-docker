#!/usr/bin/env bash
# Build discover-sabnzbd.jar from DiscoverSabnzbd.java in a JDK container
# (no local JDK needed). The jar runs on any Java 11+ runtime.
set -eu
cd "$(dirname "$0")"
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/src -w /src eclipse-temurin:21-jdk bash -c '
  set -e
  rm -rf build && mkdir build
  javac --release 11 -d build DiscoverSabnzbd.java
  jar --create --file discover-sabnzbd.jar --main-class DiscoverSabnzbd -C build .
  rm -rf build'
ls -l discover-sabnzbd.jar
