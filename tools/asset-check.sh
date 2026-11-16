#!/usr/bin/env bash
# 更新包自检：release 附件怎么挑、下到的 jar 里有没有主类。不启游戏。
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/assetcheck
javac -d build/assetcheck \
  src/client/java/com/phzt/railplanner/client/update/AssetNames.java \
  src/client/java/com/phzt/railplanner/client/update/JarCheck.java \
  tools/AssetCheck.java
java -cp build/assetcheck AssetCheck
