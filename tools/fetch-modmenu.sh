#!/usr/bin/env bash
# 下载编译所需的 Mod Menu 21.0.1（可选依赖，只是让设置页出现在模组列表里）。
# jar 不进仓库，只在本机编译时引用；没装 Mod Menu 的玩家照样能玩，设置页用 O 键打开。
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p libs
M=libs/modmenu-21.0.1.jar
if [ ! -f "$M" ]; then
  curl -fL -o "$M" "https://maven.terraformersmc.com/releases/com/terraformersmc/modmenu/21.0.1/modmenu-21.0.1.jar"
fi
echo "884ab621735264187ccbfc87017cf55bb54d47ee  $M" | sha1sum -c -
mkdir -p run/mods
cp -f "$M" run/mods/
echo "ok: mod menu ready"
