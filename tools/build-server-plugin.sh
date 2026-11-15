#!/usr/bin/env bash
# 把 server-plugin/ 打成 MCDR 能吃的 .mcdr（就是个 zip）。本机没有 zip 命令，用 python 的 zipfile。
set -euo pipefail
cd "$(dirname "$0")/.."
VER=$(python3 -c "import json;print(json.load(open('server-plugin/mcdreforged.plugin.json'))['version'])")
OUT="build/railplanner_updater-$VER.mcdr"
mkdir -p build
rm -f "$OUT"
python3 - "$OUT" <<'PY'
import pathlib
import sys
import zipfile

out = sys.argv[1]
root = pathlib.Path('server-plugin')
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    z.write(root / 'mcdreforged.plugin.json', 'mcdreforged.plugin.json')
    for p in sorted((root / 'railplanner_updater').rglob('*')):
        if p.is_file() and '__pycache__' not in p.parts and not p.name.endswith('.pyc'):
            z.write(p, str(p.relative_to(root)))
print('built', out)
PY
python3 -c "import sys,zipfile;print(zipfile.ZipFile(sys.argv[1]).namelist())" "$OUT"
