package com.luxera.companion.agent;

import org.springframework.stereotype.Component;

/**
 * 感知引擎(MVP 启发式): 识别意图/情绪/话题。
 * (设计文档 39 节: Message → Perception → Intent/Emotion Detection)
 */
@Component
public class PerceptionEngine {

    public Perception perceive(String text) {
        if (text == null) text = "";
        return new Perception(intent(text), emotion(text), topic(text));
    }

    private String intent(String t) {
        if (containsAny(t, "你好", "嗨", "哈喽", "在吗", "hi", "hello", "早上好", "晚上好", "早安", "嗨喽")) return "greeting";
        if (containsAny(t, "不是", "其实", "错了", "更正", "才不是", "不对", "我没说")) return "correction";
        if (containsAny(t, "晚安", "睡了", "要睡了", "先休息", "早点睡")) return "say_goodnight";
        if (containsAny(t, "提醒", "记得", "帮我", "安排", "日程", "日历", "闹钟", "记一下")) return "request_tool";
        if (containsAny(t, "？", "?", "吗", "为什么", "怎么", "能不能", "可不可以", "是不是", "什么", "几点", "谁")) return "question";
        if (containsAny(t, "好累", "好烦", "压力", "加班", "崩溃", "难过", "不开心", "委屈", "失眠", "焦虑", "好难", "好累啊", "累死", "emo", "破防")) return "share_upset";
        if (containsAny(t, "开心", "太好了", "成功", "通过", "晋升", "涨薪", "赢了", "高兴", "兴奋", "好消息")) return "share_joy";
        return "chat";
    }

    private String emotion(String t) {
        if (containsAny(t, "好累", "累死", "加班", "失眠", "熬夜", "没睡好")) return "tired";
        if (containsAny(t, "难过", "崩溃", "委屈", "破防", "哭", "伤心", "想哭")) return "sad";
        if (containsAny(t, "焦虑", "压力", "紧张", "害怕", "担心")) return "anxious";
        if (containsAny(t, "生气", "气死", "烦死", "讨厌", "无语", "受不了")) return "angry";
        if (containsAny(t, "开心", "太好了", "兴奋", "高兴", "哈哈", "嘿嘿", "超棒")) return "happy";
        return "neutral";
    }

    private String topic(String t) {
        if (containsAny(t, "工作", "上班", "同事", "老板", "项目", "加班", "开会", "出差", "辞职", "面试")) return "work";
        if (containsAny(t, "学习", "考试", "论文", "上课", "考研", "作业", "复习", "学校")) return "study";
        if (containsAny(t, "睡", "吃", "身体", "感冒", "头疼", "疼", "累", "锻炼", "跑步", "健身")) return "health";
        if (containsAny(t, "你", "我们", "关系", "喜欢", "想念", "想你", "见面")) return "relationship";
        if (containsAny(t, "电影", "歌", "书", "游戏", "剧", "番", "音乐", "美食", "旅行")) return "life";
        return "daily";
    }

    private static boolean containsAny(String s, String... keys) {
        for (String k : keys) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    /** 感知结果 */
    public record Perception(String intent, String emotion, String topic) {}
}
