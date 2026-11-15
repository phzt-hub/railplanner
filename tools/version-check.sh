#!/usr/bin/env bash
# 版本号比较自检：不启游戏，只编译 update/Version 与 VersionCheck。
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/versioncheck
javac -d build/versioncheck src/client/java/com/phzt/railplanner/client/update/Version.java tools/VersionCheck.java
java -cp build/versioncheck VersionCheck
