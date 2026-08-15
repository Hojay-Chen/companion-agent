package com.luxera.companion.runtime.agent.memory;

import java.util.List;

/**
 * Memory Agent 输出(V5 §24): 候选记忆的激活评分。
 * 代码只提供基础特征, LLM 判断语义相关性; 回退时用检索强度。
 */
public record MemoryRecallResult(
        List<MemoryActivation> activations,
        boolean fallback) {

    public record MemoryActivation(String memoryId, double activation, String reason) {
    }

    public static MemoryRecallResult empty() {
        return new MemoryRecallResult(List.of(), true);
    }
}
