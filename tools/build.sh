#!/usr/bin/env bash
# Build Retro Launcher with Gradle's cache, the build output and temp files on D: (C: is nearly full).
#   bash tools/build.sh [gradle tasks...]   (default: assembleRelease)
# The APK lands in "D:\rl-build\out\app\outputs\apk\release\app-release.apk".
set -u
D="D:/rl-build"
cd "$(dirname "$0")/.."
free=$(df -m /c | awk 'NR==2{print $4}')
[ "${free:-0}" -lt 50 ] && { echo "STOP  drive C: has only ${free} MB free"; exit 1; }
export GRADLE_USER_HOME="$D/gradle-home" TMP="$D/tmp" TEMP="$D/tmp"
export GRADLE_OPTS="-Djava.io.tmpdir=$D/tmp"
./gradlew --console=plain -q -I "$D/build-on-d.gradle" -Dorg.gradle.jvmargs="-Xmx2g -Djava.io.tmpdir=$D/tmp" "${@:-assembleRelease}"
