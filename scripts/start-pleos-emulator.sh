#!/bin/sh
set -eu

ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
exec "$ANDROID_HOME/emulator/emulator" -avd Pleos_Connect_v2_1 -no-snapshot -no-snapshot-save "$@"
