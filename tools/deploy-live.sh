#!/usr/bin/env bash
# 发版之后手动把线上服务端（25565）拉到最新包：写部署请求文件 → MCDR 插件拉包/校验/换 jar →
# 广播倒计时 → 重启 → 本脚本读结果文件，并等服务端重新起来。
#
# 用法：
#   tools/deploy-live.sh                    # 用插件配置里的通道（现在是 debug）
#   tools/deploy-live.sh --channel release  # 指定通道
#   tools/deploy-live.sh --countdown 0      # 不广播，3 秒后重启
#   tools/deploy-live.sh --no-wait          # 只看拉包结果，不等服务端起回来
#
# 退出码：0 换包并重启成功 / 1 失败 / 3 本来就是最新（没换没重启）
set -euo pipefail

MCDR=${RR_MCDR_ROOT:-/mnt/plextor/mc}
MCDR_LOG=${RR_MCDR_LOG:-$MCDR/logs/MCDR.log}
SERVER_LOG=${RR_SERVER_LOG:-$MCDR/server/logs/latest.log}
REQ=$MCDR/railplanner_deploy.request
RES=$MCDR/railplanner_deploy.result
CHANNEL=""
COUNTDOWN=""
WAIT_RESULT=${RR_WAIT_RESULT:-120}
WAIT_BOOT=${RR_WAIT_BOOT:-300}
NO_WAIT=0

while [ $# -gt 0 ]; do
  case "$1" in
    --channel)   CHANNEL=${2:?}; shift 2 ;;
    --countdown) COUNTDOWN=${2:?}; shift 2 ;;
    --no-wait)   NO_WAIT=1; shift ;;
    -h|--help)   sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "不认识的参数：$1（--channel / --countdown / --no-wait）" >&2; exit 2 ;;
  esac
done

[ -d "$MCDR" ] || { echo "找不到 MCDR 根目录：$MCDR" >&2; exit 1; }
pgrep -f 'mcdreforged start' >/dev/null || { echo "MCDR 没在跑，先起它" >&2; exit 1; }
if [ -f "$REQ" ]; then
  echo "已经有一个没处理完的部署请求：$REQ（等它跑完或手动删掉）" >&2
  exit 1
fi
rm -f "$RES"

payload=$(python3 - "$CHANNEL" "$COUNTDOWN" <<'PY'
import json, sys, time
ch, cd = sys.argv[1], sys.argv[2]
p = {'requestedBy': 'tools/deploy-live.sh', 'at': time.strftime('%Y-%m-%d %H:%M:%S')}
if ch:
    p['channel'] = ch
if cd:
    p['countdown'] = int(cd)
print(json.dumps(p, ensure_ascii=False))
PY
)

inode_before=$(stat -c %i "$SERVER_LOG" 2>/dev/null || echo 0)
echo "== 写部署请求：$payload"
printf '%s\n' "$payload" > "$REQ"

for _ in $(seq 1 "$WAIT_RESULT"); do
  [ -f "$RES" ] && break
  sleep 1
done
if [ ! -f "$RES" ]; then
  echo "!! $WAIT_RESULT 秒内没有结果文件，去看 $MCDR_LOG" >&2
  exit 1
fi

echo "== 插件结果："
cat "$RES"; echo
status=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["status"])' "$RES")
jar=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("jar") or "")' "$RES")

if [ "$status" = "failed" ]; then
  echo "!! 部署失败" >&2
  exit 1
fi
if [ "$status" = "uptodate" ]; then
  echo "== 服务端已经是最新，没换包、没重启"
  exit 3
fi

if [ -n "$jar" ]; then
  echo "== 落地的包："
  ls -l --time-style=long-iso "$MCDR/server/mods/$jar" 2>/dev/null || true
  echo "sha256: $(sha256sum "$MCDR/server/mods/$jar" | cut -d' ' -f1)"
fi

if [ "$NO_WAIT" = "1" ]; then
  echo "== （--no-wait：不等服务端起回来）"
  exit 0
fi

echo "== 等服务端重启…"
for _ in $(seq 1 "$WAIT_BOOT"); do
  sleep 1
  now=$(stat -c %i "$SERVER_LOG" 2>/dev/null || echo 0)
  if [ "$now" != "$inode_before" ]; then
    line=$(grep -a "Rail Planner .* 已加载" "$SERVER_LOG" 2>/dev/null | tail -1 || true)
    if [ -n "$line" ]; then
      echo "== 服务端已重启：$line"
      grep -a "Done (" "$SERVER_LOG" | tail -1 || true
      exit 0
    fi
  fi
done
echo "!! $WAIT_BOOT 秒内没等到服务端起回来，去看 $SERVER_LOG" >&2
exit 1
