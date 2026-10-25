#!/usr/bin/env bash
# Builds the macOS installer (target/installer/DuesDesk-<version>.dmg) on a Mac.
# Requires JDK 25 (with jpackage) and Maven. Run on an Apple Silicon Mac for an arm64 build,
# or on an Intel Mac for x64; JavaFX natives match the machine that builds.
set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v jpackage >/dev/null 2>&1; then
  echo "jpackage was not found. Install JDK 25 and put its bin folder on PATH." >&2
  exit 1
fi

mvn -Pjpackage -DskipTests clean package
echo
echo "Installer written to: $(ls target/installer/*.dmg)"
