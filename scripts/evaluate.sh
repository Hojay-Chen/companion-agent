#!/usr/bin/env bash
# Luxera Companion V2.0 — Human-likeness 真实感评测(设计文档 §45)
# 对一段伴侣回复, 按 10 个维度 1-5 分评估。
# 用法: echo "回复文本" | bash scripts/evaluate.sh
set -euo pipefail

echo "════════ Human-likeness Score 评测 ════════"
echo "维度          | 1分(机械)                    | 5分(真人感)"
echo "─────────────┼──────────────────────────────┼──────────────────────────"
RUBRIC=(
"Continuity        | 每句独立无承接             | 延续之前话题, 有来有往"
"Consistency       | 人格/语气漂移              | 人格语气前后一致"
"Initiative        | 被动应答                   | 会主动追问/推进话题"
"ContextualRel     | 答非所问                   | 紧密贴合当下语境"
"EmotionalCohere   | 情绪不对应                 | 情绪与内容匹配"
"SelfConsistency   | 不像同一个人               | 像同一个人"
"RelationshipCohere| 忽视你们的关系历史         | 体现关系阶段与过往"
"MemoryNatural     | 生硬复述记忆               | 自然引用记忆"
"TemporalCohere    | 无视时间/作息              | 体现当前时间与生活"
"Imperfection      | 过度完美(全知全能)         | 会记不清/不知道(有原因)"
)
for r in "${RUBRIC[@]}"; do
  printf "%-18s |  %s\n" "${r%%|*}" "${r#*|}"
done
echo ""
echo "输入回复后逐项打分(1-5):"
echo "──────────────────────────────────────────"
INPUT=$(cat || echo "(无输入, 请手动评估)")

python3 - "$INPUT" <<'PY'
import sys, re
text = sys.argv[1] if len(sys.argv) > 1 else ""
dims = ["Continuity","Consistency","Initiative","ContextualRelevance","EmotionalCoherence",
        "SelfConsistency","RelationshipCoherence","MemoryNaturalness","TemporalCoherence","Imperfection"]
print(f"回复: {text[:120]}{'…' if len(text)>120 else ''}")
print("评分表(1-5, 人工/LLM 判断):")
for i, d in enumerate(dims, 1):
    print(f"  {i}. {d}: ____/5")
print("总分: ____/50  Human-likeness Score: ____/5")
PY
