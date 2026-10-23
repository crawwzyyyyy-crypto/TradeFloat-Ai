#!/usr/bin/env sh
# Lightweight Gradle launcher for TradeFloat AI. Downloads Gradle 8.9 if absent.
set -eu
GRADLE_VERSION="8.9"
if [ -n "${GRADLE_USER_HOME:-}" ]; then
  CACHE_BASE="$GRADLE_USER_HOME"
else
  CACHE_BASE="$HOME/.gradle"
fi
DIST_DIR="$CACHE_BASE/tradefloat-distributions/gradle-$GRADLE_VERSION"
GRADLE_BIN="$DIST_DIR/gradle-$GRADLE_VERSION/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$DIST_DIR"
  ZIP="$DIST_DIR/gradle-$GRADLE_VERSION-bin.zip"
  URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  echo "Downloading Gradle $GRADLE_VERSION..."
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 "$URL" -o "$ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP" "$URL"
  else
    echo "ERROR: curl or wget is required to download Gradle." >&2
    exit 1
  fi
  command -v unzip >/dev/null 2>&1 || { echo "ERROR: unzip is required." >&2; exit 1; }
  unzip -q "$ZIP" -d "$DIST_DIR"
  rm -f "$ZIP"
fi
exec "$GRADLE_BIN" "$@"
