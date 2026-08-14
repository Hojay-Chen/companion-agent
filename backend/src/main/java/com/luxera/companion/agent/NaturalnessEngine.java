package com.luxera.companion.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自然度引擎: 检测并修复 AI 腔 / 模板化表达。
 * (设计文档 79-80 节)
 */
@Component
public class NaturalnessEngine {

    private static final List<String> AI_PHRASES = List.of(
            "作为AI", "作为一个AI", "作为一个人工智能", "作为智能助手", "作为语言模型",
            "我是AI", "我的训练数据", "在我的训练数据中", "作为助手", "作为一个助手",
            "我没有情感", "我不能感受", "请记住我是一个", "我是一个人工智能"
    );

    private static final Pattern EMOJI_PATTERN = Pattern.compile(
            "[\\uD83C\\uDF00-\\uD83D\\uDE4F\\uD83D\\uDE80-\\uD83D\\uDEFF\\uD83E\\uDD00-\\uD83E\\uDDFF]");

    private static final List<String> OVER_ADVICE = List.of(
            "首先你要", "你应该要", "建议你每天", "记住,健康", "成年人应该");

    /** 校验结果: 修复后的文本 + 问题列表 */
    public Result validate(String text) {
        List<String> issues = new ArrayList<>();
        String cleaned = text == null ? "" : text;

        for (String phrase : AI_PHRASES) {
            if (cleaned.contains(phrase)) {
                issues.add("AI套话: " + phrase);
                cleaned = cleaned.replace(phrase, "");
            }
        }
        // 残留英文 'AI' 单独出现
        cleaned = cleaned.replaceAll("(?i)as an AI", "").replaceAll("(?i)as an AI assistant", "");

        Matcher m = EMOJI_PATTERN.matcher(cleaned);
        int emoji = 0;
        while (m.find()) emoji++;
        if (emoji > 3) {
            issues.add("过度emoji: " + emoji + " 个");
        }

        if (cleaned.trim().length() > 500) {
            issues.add("回应过长");
        }

        for (String phrase : OVER_ADVICE) {
            if (cleaned.contains(phrase)) {
                issues.add("说教式建议: " + phrase);
            }
        }

        cleaned = cleaned.replaceAll("[\\n]{3,}", "\n\n").trim();
        return new Result(cleaned, issues);
    }

    public record Result(String cleaned, List<String> issues) {}
}
