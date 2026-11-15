#!/usr/bin/env bash
# 纯几何自检：不启游戏，验证圆曲线参数与像素化。
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/geomcheck
javac -d build/geomcheck src/client/java/com/phzt/railplanner/client/geom/*.java tools/GeomCheck.java
java -cp build/geomcheck GeomCheck
