#!/usr/bin/env sh
set -eu

GRADLE_VERSION="8.8"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BOOTSTRAP_DIR="$SCRIPT_DIR/.gradle-bootstrap"
GRADLE_HOME="$BOOTSTRAP_DIR/gradle-$GRADLE_VERSION"
GRADLE_BIN="$GRADLE_HOME/bin/gradle"
ZIP="$BOOTSTRAP_DIR/gradle-$GRADLE_VERSION-bin.zip"
URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"

if ! command -v java >/dev/null 2>&1; then
  echo "Java not found. Forge 1.20.1 requires JDK 17." >&2
  exit 1
fi

if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$BOOTSTRAP_DIR"
  echo "Downloading Gradle $GRADLE_VERSION..."
  if command -v curl >/dev/null 2>&1; then
    curl -fL "$URL" -o "$ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP" "$URL"
  else
    echo "curl or wget is required for first-run Gradle bootstrap." >&2
    exit 1
  fi
  unzip -q -o "$ZIP" -d "$BOOTSTRAP_DIR"
  rm -f "$ZIP"
fi

cd "$SCRIPT_DIR"
exec "$GRADLE_BIN" "$@"
