#!/usr/bin/env bash
# 把 server-plugin 打成 .mcdr 直接推 25565 那台服务器 —— 不挂 GitHub、不上内网发布站、不走 CF。
# MCDR 装了 AutoPluginReloader，新文件落进 plugins/ 会自己重载；脚本等日志确认加载完才算成功，
# 内容没变（同一个 md5）时直接说「已经是这个版本」，不空等。
set -euo pipefail
cd "$(dirname "$0")/.."

VER=$(python3 -c "import json;print(json.load(open('server-plugin/mcdreforged.plugin.json'))['version'])")
PLUGINS=${RR_PLUGINS:-/mnt/plextor/mc/plugins}
LOG=${RR_MCDR_LOG:-/mnt/plextor/mc/logs/MCDR.log}
TARGET="$PLUGINS/railplanner_updater-$VER.mcdr"

./tools/build-server-plugin.sh > /dev/null
SRC="build/railplanner_updater-$VER.mcdr"
[ -f "$SRC" ] || { echo "没打出 $SRC" >&2; exit 1; }

same=0
if [ -f "$TARGET" ] && cmp -s "$SRC" "$TARGET"; then
  same=1
fi
before=$(grep -c "插件 railplanner_updater@$VER 已加载" "$LOG" 2>/dev/null || true)

cp "$SRC" "$TARGET"
# 同一个 id 只能有一个文件，旧版本留着 MCDR 会报重复
for f in "$PLUGINS"/railplanner_updater-*.mcdr; do
  [ -e "$f" ] || continue
  [ "$(basename "$f")" = "railplanner_updater-$VER.mcdr" ] || rm -f "$f"
done
echo "推到 $PLUGINS："
ls -l "$PLUGINS"/railplanner_updater-*.mcdr
if [ "$same" = "1" ]; then
  echo "（内容和服务器上那份一模一样，版本仍是 $VER）"
fi

for i in $(seq 1 30); do
  now=$(grep -c "插件 railplanner_updater@$VER 已加载" "$LOG" 2>/dev/null || true)
  if [ "$now" -gt "$before" ]; then
    echo "OK：MCDR 已加载 railplanner_updater@$VER"
    grep "RailPlanner\] 自动更新已加载" "$LOG" | tail -1
    exit 0
  fi
  sleep 1
done

if [ "$same" = "1" ] && [ "$before" -gt 0 ]; then
  echo "OK：服务器上已经是 railplanner_updater@$VER，内容没变，不需要重载"
  exit 0
fi

echo "!! 30 秒内没等到加载记录，看一下 $LOG" >&2
exit 1
