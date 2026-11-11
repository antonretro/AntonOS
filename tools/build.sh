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
APK="/d/rl-build/out/app/outputs/apk/release/app-release.apk"
before=$(stat -c %Y "$APK" 2>/dev/null || echo 0)
log="$D/tmp/build-last.log"
if ! ./gradlew --console=plain -q -I "$D/build-on-d.gradle" -Dorg.gradle.jvmargs="-Xmx2g -Djava.io.tmpdir=$D/tmp" "${@:-assembleRelease}" > "$log" 2>&1; then
  grep -v "^w:" "$log" | tail -25; echo "BUILD FAILED (full log: $log)"; exit 1
fi
grep -E "^e:" "$log" | head -5
# a build that "succeeds" without a fresh APK is a failure too (an old APK once got installed three times)
after=$(stat -c %Y "$APK" 2>/dev/null || echo 0)
if [ "${*:-assembleRelease}" = "assembleRelease" ] && [ "$after" -le "$before" ]; then echo "BUILD FAILED: no new APK"; exit 1; fi
echo "BUILD OK  $(date -d @"$after" +%H:%M:%S)"
