#!/usr/bin/env bash
# 下载编译所需的 Xaero's World Map 26.3（Fabric）与其内嵌 XaeroLib。
# 这两个 jar 不进仓库（Xaero 是 All Rights Reserved，我们只在本机编译时引用）。
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p libs
W=libs/xaeroworldmap-fabric-26.3-1.47.2.jar
L=libs/xaerolib-fabric-26.3-1.8.5.jar
if [ ! -f "$W" ]; then
  curl -fL -o "$W" "https://cdn.modrinth.com/data/NcUtCpym/versions/VM8lNSSM/xaeroworldmap-fabric-26.3-1.47.2.jar"
fi
echo "1282302b6c92050cfab7c1db45cf3d92d9a331c5  $W" | sha1sum -c -
if [ ! -f "$L" ]; then
  unzip -o -j "$W" 'META-INF/jars/xaerolib-fabric-26.3-1.8.5.jar' -d libs >/dev/null
fi
mkdir -p run/mods
cp -f "$W" run/mods/
echo "ok: $(ls -l libs/*.jar | wc -l) jars in libs/"
