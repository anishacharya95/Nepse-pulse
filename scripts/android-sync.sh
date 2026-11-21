#!/usr/bin/env bash
# Capacitor sync that always restores the NEPSE Pulse native branding.
#
# Capacitor regenerates parts of the Android project (including res/values
# themes) when the platform is updated. Running the branding generator after
# every sync keeps the launcher icon and splash screen from being replaced by
# Capacitor's default artwork.
#
# Usage: scripts/android-sync.sh [android] [res-dir]
set -euo pipefail

cd "$(dirname "$0")/.."
platform="${1:-android}"
res_dir="${2:-android/app/src/main/res}"

npx cap sync "$platform"
python3 scripts/generate_android_branding.py "$res_dir"
