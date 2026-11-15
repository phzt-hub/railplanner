#!/usr/bin/env bash
# 纵断面自检：钉点、限坡、直线兜底，不启游戏。
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/verticalcheck
javac -d build/verticalcheck src/client/java/com/phzt/railplanner/client/geom/VerticalProfile.java tools/VerticalCheck.java
java -cp build/verticalcheck VerticalCheck
