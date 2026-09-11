package com.luxera.companion.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 聊天内建提醒(设计文档 56-58 节): 用户说"帮我记得…"时,
 * 用 LLM 解析提醒内容与时间并创建 Reminder,返回一句给 Prompt 的确认上下文。
 */
@Slf4j
@Component
public class ReminderPlanner {

    private static final String SYSTEM_TEMPLATE = """
            你是提醒解析器。判断用户是否想让伴侣帮忙提醒/记住某件事。
            今天是 %s,现在是 %s。请基于这个"今天"计算 remind_at。
            输出严格 JSON,不要输出其他内容:
            {
              "remind": true,
              "title": "提醒事项标题(简洁)",
              "content": "补充说明,可为空",
              "remind_at": "yyyy-MM-ddTHH:mm 或空字符串(未说时间)",
              "type": "user_set"
            }
            规则:
            - 用户明确说"提醒/记得/帮我记/别忘了"之类 → remind=true
            - 没提时间 → remind_at 为空字符串
            - 不是提醒请求 → remind=false,其余字段空
            """;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("M月d日 HH:mm");

    private final LlmRouter llm;
    private final ReminderService reminderService;

    public ReminderPlanner(LlmRouter llm, ReminderService reminderService) {
        this.llm = llm;
        this.reminderService = reminderService;
    }

    /** 尝试从用户消息创建提醒;成功返回供 Prompt 注入的确认上下文,否则 null */
    public String tryCreateFromMessage(String userId, String companionId, String userText) {
        if (!StringUtils.hasText(userText)) return null;
        try {
            String sys = String.format(SYSTEM_TEMPLATE, LocalDate.now(), LocalTime.now().withNano(0).withSecond(0));
            var res = llm.structured(StructuredRequest.builder()
                    .task("reminder-extraction")
                    .system(sys)
                    .user(userText)
                    .temperature(0.2)
                    .build());
            JsonNode root = res.getJson();
            if (!root.path("remind").asBoolean(false)) return null;
            String title = root.path("title").asText("");
            if (!StringUtils.hasText(title)) return null;

            LocalDateTime remindAt = parseTime(root.path("remind_at").asText(""));
            if (remindAt == null) remindAt = LocalDateTime.now().plusHours(1);

            Reminder r = reminderService.create(userId, companionId, "user_set", title,
                    root.path("content").asText(null), remindAt, null);
            return "你刚为用户创建了提醒:「" + r.getTitle() + "」,时间 " + FMT.format(r.getRemindAt())
                    + "。请在回复里自然地确认你已经记住了这件事。";
        } catch (Exception e) {
            log.debug("提醒解析失败: {}", e.getMessage());
            return null;
        }
    }

    private static LocalDateTime parseTime(String s) {
        if (!StringUtils.hasText(s)) return null;
        s = s.trim();
        LocalDateTime parsed = null;
        try {
            parsed = LocalDateTime.parse(s);   // ISO yyyy-MM-ddTHH:mm
        } catch (Exception ignored) {
        }
        if (parsed == null) {
            try {
                parsed = LocalDateTime.parse(s.replace(' ', 'T'));
            } catch (Exception ignored) {
            }
        }
        if (parsed == null) {
            try {
                parsed = LocalDate.now().atTime(LocalTime.parse(s));   // HH:mm
            } catch (Exception ignored) {
                return null;
            }
        }
        // 兜底: 解析出的时间在过去(如模型幻觉了错误年份) → 交给调用方用默认 +1h
        if (parsed.isBefore(LocalDateTime.now())) return null;
        return parsed;
    }
}
