package com.luxera.companion.memory;

import org.springframework.stereotype.Component;

import java.util.List;

/** 未接入向量库时的空实现(设计文档 32 节: 向量只是检索手段之一) */
@Component
public class NoopEmbeddingProvider implements EmbeddingProvider {
    @Override
    public List<String> searchSimilar(String userId, String companionId, String query, int topK) {
        return List.of();
    }
}
