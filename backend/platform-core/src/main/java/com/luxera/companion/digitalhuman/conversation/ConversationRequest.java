package com.luxera.companion.digitalhuman.conversation;

import java.util.ArrayList;
import java.util.List;

/**
 * V10 §15.2 ConversationRequest: 一次文本生成的完整请求(Builder Pattern)。
 *
 * Prompt 分层(V10 §15.2/§19):
 * - Stable Prefix:     System Contract / Identity / Personality / Expression Rules / Output Contract
 *                     (内容和顺序固定, 版本化后可被 provider prefix cache 命中)
 * - Semi-Stable:       Relationship State / Life Summary
 * - Dynamic Suffix:    Current Activity / Current Mind / Relevant Memory / Recent Conversation / New Message
 *
 * 所有聊天文本必须经 ConversationRuntime 生产 —— 本请求是唯一输入形态。
 */
public class ConversationRequest {

    private final String companionId;
    private final String companionName;
    private final List<String> stablePrefix;
    private final List<String> semiStable;
    private final List<String> dynamicSuffix;
    private final String userPrompt;
    private final double temperature;
    private final int maxLength;
    private final String correctionHint;

    private ConversationRequest(Builder b) {
        this.companionId = b.companionId;
        this.companionName = b.companionName;
        this.stablePrefix = List.copyOf(b.stablePrefix);
        this.semiStable = List.copyOf(b.semiStable);
        this.dynamicSuffix = List.copyOf(b.dynamicSuffix);
        this.userPrompt = b.userPrompt;
        this.temperature = b.temperature;
        this.maxLength = b.maxLength;
        this.correctionHint = b.correctionHint;
    }

    public static Builder builder(String companionId, String companionName, String userPrompt) {
        return new Builder(companionId, companionName, userPrompt);
    }

    /** 带上验证失败原因的重试请求(重生成提示) */
    public ConversationRequest withCorrection(String issueDescription) {
        Builder b = new Builder(companionId, companionName, userPrompt);
        b.stablePrefix.addAll(stablePrefix);
        b.semiStable.addAll(semiStable);
        b.dynamicSuffix.addAll(dynamicSuffix);
        b.temperature = temperature;
        b.maxLength = maxLength;
        b.correctionHint = issueDescription;
        return b.build();
    }

    // ── 访问器 ──────────────────────────────

    public String companionId() { return companionId; }
    public String companionName() { return companionName; }
    public List<String> stablePrefix() { return stablePrefix; }
    public List<String> semiStable() { return semiStable; }
    public List<String> dynamicSuffix() { return dynamicSuffix; }
    public String userPrompt() { return userPrompt; }
    public double temperature() { return temperature; }
    public int maxLength() { return maxLength; }
    public String correctionHint() { return correctionHint; }

    public static class Builder {
        private final String companionId;
        private final String companionName;
        private final String userPrompt;
        private final List<String> stablePrefix = new ArrayList<>();
        private final List<String> semiStable = new ArrayList<>();
        private final List<String> dynamicSuffix = new ArrayList<>();
        private double temperature = 0.9;
        private int maxLength = 200;
        private String correctionHint;

        public Builder(String companionId, String companionName, String userPrompt) {
            this.companionId = companionId;
            this.companionName = companionName;
            this.userPrompt = userPrompt;
        }

        public Builder stable(String line) { stablePrefix.add(line); return this; }
        public Builder semiStable(String line) { semiStable.add(line); return this; }
        public Builder dynamic(String line) { dynamicSuffix.add(line); return this; }
        public Builder temperature(double t) { temperature = t; return this; }
        public Builder maxLength(int len) { maxLength = len; return this; }
        public Builder correctionHint(String hint) { correctionHint = hint; return this; }

        public ConversationRequest build() {
            return new ConversationRequest(this);
        }
    }
}
