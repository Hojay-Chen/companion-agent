#!/usr/bin/env bash
# 配置 SiliconFlow Embedding key(激活 pgvector 向量检索)
# 用法: sudo bash scripts/setup_embedding.sh <你的key>
#   key 写入 /etc/companion/.env(不入 git), 然后重启后端
set -euo pipefail

if [ $# -lt 1 ]; then
  echo "用法: sudo bash scripts/setup_embedding.sh <EMBEDDING_API_KEY>"
  echo "可选项: EMBEDDING_BASE_URL / EMBEDDING_MODEL / EMBEDDING_DIMENSION"
  exit 1
fi

KEY="$1"
BASE="${EMBEDDING_BASE_URL:-https://api.siliconflow.cn/v1}"
MODEL="${EMBEDDING_MODEL:-BAAI/bge-large-zh-v1.5}"
DIM="${EMBEDDING_DIMENSION:-1024}"

echo "==> 写入 /etc/companion/.env"
mkdir -p /etc/companion
touch /etc/companion/.env
grep -q '^EMBEDDING_API_KEY=' /etc/companion/.env \
  && sed -i "s|^EMBEDDING_API_KEY=.*|EMBEDDING_API_KEY=$KEY|" /etc/companion/.env \
  || echo "EMBEDDING_API_KEY=$KEY" >> /etc/companion/.env
grep -q '^EMBEDDING_BASE_URL=' /etc/companion/.env \
  && sed -i "s|^EMBEDDING_BASE_URL=.*|EMBEDDING_BASE_URL=$BASE|" /etc/companion/.env \
  || echo "EMBEDDING_BASE_URL=$BASE" >> /etc/companion/.env
grep -q '^EMBEDDING_MODEL=' /etc/companion/.env \
  && sed -i "s|^EMBEDDING_MODEL=.*|EMBEDDING_MODEL=$MODEL|" /etc/companion/.env \
  || echo "EMBEDDING_MODEL=$MODEL" >> /etc/companion/.env
grep -q '^EMBEDDING_DIMENSION=' /etc/companion/.env \
  && sed -i "s|^EMBEDDING_DIMENSION=.*|EMBEDDING_DIMENSION=$DIM|" /etc/companion/.env \
  || echo "EMBEDDING_DIMENSION=$DIM" >> /etc/companion/.env
chmod 640 /etc/companion/.env

echo "==> 重启后端"
systemctl restart luxera-companion-backend
sleep 12

echo "==> 验证网关"
curl -s -m 5 http://127.0.0.1:8081/api/health >/dev/null && echo "后端 UP"
echo "==> 用 key 实测一次 embedding 接口"
curl -s -m 15 "$BASE/embeddings" \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d "{\"model\":\"$MODEL\",\"input\":\"测试向量\"}" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);v=d['data'][0]['embedding'];print('  embedding 维度:',len(v),'(应为 $DIM)')" 2>&1 \
  || echo "  (接口探测失败, 请检查 key/base-url)"

echo "✅ 配置完成。发一条消息后, 记忆会自动写入向量, 检索自动升级为语义匹配。"
echo "   验证: PGPASSWORD=shared-secret psql -h 127.0.0.1 -U admin -d companion -c \"SELECT count(*) FROM memories WHERE embedding IS NOT NULL;\""
