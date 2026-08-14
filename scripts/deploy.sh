#!/usr/bin/env bash
# 部署 companion.luxera.top (需 root)
set -euo pipefail

BACKEND_JAR=/home/ubuntu/claude-workspace/companion-agent/backend/target/companion-platform-backend-1.0.0.jar
FRONTEND_DIST=/home/ubuntu/claude-workspace/companion-agent/frontend/dist
NGINX_SRC=/home/ubuntu/claude-workspace/infrastructure/nginx/sites/companion.conf

echo "==> 1. 前端静态产物 → /var/www/companion"
mkdir -p /var/www/companion
rm -rf /var/www/companion/*
cp -r "$FRONTEND_DIST"/* /var/www/companion/
chown -R www-data:www-data /var/www/companion

echo "==> 2. nginx 配置 → /etc/nginx/conf.d/"
cp "$NGINX_SRC" /etc/nginx/conf.d/companion.conf

echo "==> 3. /etc/hosts 本机解析 (幂等)"
grep -q 'companion.luxera.top' /etc/hosts \
  || echo '127.0.0.1 companion.luxera.top   # 伴侣平台' >> /etc/hosts

echo "==> 4. systemd 服务 luxera-companion-backend"
cat > /etc/systemd/system/luxera-companion-backend.service <<EOF
[Unit]
Description=Luxera Companion Platform Backend (Spring Boot)
After=network.target
Wants=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/home/ubuntu/claude-workspace/companion-agent/backend
ExecStart=/usr/bin/java -jar $BACKEND_JAR
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable luxera-companion-backend >/dev/null 2>&1 || true
systemctl restart luxera-companion-backend

echo "==> 5. nginx 校验 + 重载"
nginx -t
systemctl reload nginx

echo "==> 6. 等待后端就绪"
for i in $(seq 1 30); do
  if curl -sf http://127.0.0.1:8081/api/health >/dev/null 2>&1; then
    echo "后端 UP"
    break
  fi
  sleep 1
done
curl -s http://127.0.0.1:8081/api/health && echo

echo "✅ 部署完成: https://companion.luxera.top"
