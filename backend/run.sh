#!/usr/bin/env bash
# 构建并启动 companion 后端 (Spring Boot 2.7 + JDK17, 端口 8081)
#
# 六个 Maven 模块 —— contracts / platform-kernel / chat-platform /
# digital-human-platform / application-platform / bootstrap-app, 可执行 jar 由
# bootstrap-app 组装(它是唯一同时看得见三方的模块)。
set -euo pipefail
cd "$(dirname "$0")"

JAR=bootstrap-app/target/companion-platform-bootstrap-1.0.0.jar

echo "==> 编译打包..."
mvn -q -DskipTests package

test -f "$JAR" || { echo "打包失败: $JAR 不存在" >&2; exit 1; }

echo "==> 启动后端 (http://127.0.0.1:8081)"
exec java -jar "$JAR" "$@"
