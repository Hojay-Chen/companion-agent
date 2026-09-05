#!/usr/bin/env bash
# 构建并启动 companion 后端 (Spring Boot 2.7 + JDK17, 端口 8081)
# V10 R1: 多模块构建, 可执行 jar 在 platform-core/target
set -euo pipefail
cd "$(dirname "$0")"

echo "==> 编译后端..."
mvn -q -DskipTests package

echo "==> 启动后端 (http://127.0.0.1:8081)"
exec java -jar platform-core/target/companion-platform-core-1.0.0.jar "$@"
