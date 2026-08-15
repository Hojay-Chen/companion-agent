package com.luxera.companion.thought;

import com.luxera.companion.openloop.OpenLoop;
import com.luxera.companion.openloop.OpenLoopService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 想法引擎(设计文档 V2.0 §6): Trigger → Candidate → Scoring → Active → Decision。
 * 大部分内部事件最终什么都不发生 —— 这正是为了模拟真人。
 */
@Component
public class ThoughtEngine {

    private static final Pattern RESOLUTION_PATTERN = Pattern.compile(
            "(?:明天|后天|下周|今晚|过几天)[^。！？!?]{0,10}(?:面试|考试|开会|体检|复查|交(?:稿|方案|差)|见(?:客户|面)|搬(?:家|office)|出(?:差|国))"
                    + "|等(?:消息|结果|通知)"
                    + "|(?:面试|考试)(?:结果|出来|怎么样)",
            Pattern.CASE_INSENSITIVE);

    private final ThoughtService thoughtService;
    private final OpenLoopService openLoopService;

    public ThoughtEngine(ThoughtService thoughtService, OpenLoopService openLoopService) {
        this.thoughtService = thoughtService;
        this.openLoopService = openLoopService;
    }

    /** 从一条用户消息触发候选想法(Level 2, 规则为主) */
    @Transactional
    public Thought maybeFromConversation(String companionId, String userText) {
        if (userText == null) return null;
        Matcher m = RESOLUTION_PATTERN.matcher(userText);
        if (m.find()) {
            // 用户提到有"待办/待结果"的事 → CURIOSITY + 转 OpenLoop
            String title = extractTitle(userText);
            java.time.LocalDateTime expected = expectedResolution(userText);
            OpenLoop loop = openLoopService.create(companionId, "USER_EVENT", title,
                    userText, 0.8, 0.7, expected);
            Thought t = thoughtService.create(companionId,
                    "不知道他" + title + "怎么样了",
                    "CURIOSITY", "OPEN_LOOP", loop != null ? loop.getId() : null,
                    0.85, 0.6, 0.85, 0.95, 0.8);
            return t;
        }
        return null;
    }

    /** 推断预期解决时间: "明天"→明晚18点, "后天"→后晚18点, 否则当晚会 */
    private static java.time.LocalDateTime expectedResolution(String text) {
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDateTime base = java.time.LocalDateTime.now().plusHours(6);
        if (text.contains("明天")) return today.plusDays(1).atTime(18, 0);
        if (text.contains("后天")) return today.plusDays(2).atTime(18, 0);
        return base;
    }

    /** 从一条记忆关联触发想法(如"他之前好像挺喜欢这里") */
    @Transactional
    public Thought maybeAssociation(String companionId, String memoryContent) {
        if (memoryContent == null || memoryContent.isBlank()) return null;
        if (memoryContent.length() > 4 && memoryContent.length() < 40) {
            Thought t = thoughtService.create(companionId,
                    "想起:" + memoryContent, "ASSOCIATION", "MEMORY", null,
                    0.4, 0.4, 0.6, 0.5, 0.6);
            return t;
        }
        return null;
    }

    private static String extractTitle(String text) {
        for (String kw : new String[]{"面试", "考试", "会议", "开会", "体检", "复查", "见面", "旅行", "交稿", "交方案", "结果", "通知"}) {
            if (text.contains(kw)) return "他要去" + kw;
        }
        String trimmed = text.length() > 24 ? text.substring(0, 24) : text;
        return trimmed;
    }
}
