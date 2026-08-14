package com.luxera.companion.llm;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class StructuredRequest {
    private final String system;
    private final String user;
    /** 任务类型标识,用于 Mock 网关返回合理 JSON: persona-compile | memory-extraction | user-model-extraction | daily-reflection | conversation-summary */
    private final String task;
    private final String schemaHint;
    private final Double temperature;
}
