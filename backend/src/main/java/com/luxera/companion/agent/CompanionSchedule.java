package com.luxera.companion.agent;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 日常时间表(模拟真人作息): 由 companionId 确定性派生,
 * 每个人作息略有差异(上班时间/睡觉时间不同)。
 * 忙碌时段不主动、休闲时段多主动; 并告诉"她此刻在做什么", 让回复更真实。
 */
@Component
public class CompanionSchedule {

    public enum Activity {
        SLEEP, MORNING, WORK_BUSY, LUNCH, WORK_AFTERNOON, EVENING, LEISURE, LATE_NIGHT
    }

    /** 一天的时段块(供 Life 模拟生成 LifeActivity) */
    public record TimeBlock(String type, String title, LocalTime start, LocalTime end) {}

    private record Schedule(int workStart, int workEnd, int sleepStart, int sleepEnd) {}

    /** 由 companionId 派生的确定性作息(同一人每次结果一致) */
    private Schedule scheduleFor(String companionId) {
        int h = Math.floorMod(companionId == null ? 0 : companionId.hashCode(), 1000);
        int workStart = 8 + h % 3;          // 8-10 点上班
        int workEnd = 17 + (h / 3) % 3;     // 17-19 点下班
        int sleepEnd = 6 + (h / 9) % 2;     // 6-7 点起床
        int sleepStart = 22 + (h / 27) % 2; // 22-23 点睡
        return new Schedule(workStart, workEnd, sleepStart, sleepEnd);
    }

    public Activity activityFor(String companionId, LocalDateTime now) {
        Schedule s = scheduleFor(companionId);
        int hour = now.getHour();
        boolean weekend = now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY;

        if (hour >= s.sleepStart || hour < s.sleepEnd) return Activity.SLEEP;

        if (weekend) {
            // 周末: 睡到自然醒, 全天休闲
            if (hour < 10) return Activity.MORNING;
            if (hour >= 22) return Activity.LATE_NIGHT;
            return Activity.LEISURE;
        }

        if (hour < s.workStart) return Activity.MORNING;
        if (hour >= s.workStart && hour < 12) return Activity.WORK_BUSY;
        if (hour >= 12 && hour < 14) return Activity.LUNCH;
        if (hour >= 14 && hour < s.workEnd) return Activity.WORK_AFTERNOON;
        if (hour < s.workEnd + 1) return Activity.EVENING;
        if (hour < 22) return Activity.LEISURE;
        return Activity.LATE_NIGHT;
    }

    /** 生成某一天的时段块序列(前一天的 SLEEP 到当天的 SLEEP) */
    public List<TimeBlock> dayBlocks(String companionId, LocalDate date) {
        Schedule s = scheduleFor(companionId);
        boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
        List<TimeBlock> blocks = new ArrayList<>();

        // 跨天睡眠: 前一天 sleepStart → 当天 sleepEnd
        blocks.add(new TimeBlock("SLEEP", "睡觉", LocalTime.of(0, 0), LocalTime.of(s.sleepEnd, 0)));

        if (weekend) {
            blocks.add(new TimeBlock("MORNING", "睡到自然醒", LocalTime.of(s.sleepEnd, 0), LocalTime.of(10, 0)));
            blocks.add(new TimeBlock("LEISURE", "悠闲地度过", LocalTime.of(10, 0), LocalTime.of(12, 30)));
            blocks.add(new TimeBlock("MEAL", "午餐", LocalTime.of(12, 30), LocalTime.of(13, 30)));
            blocks.add(new TimeBlock("LEISURE", "看书/散步/见朋友", LocalTime.of(13, 30), LocalTime.of(18, 0)));
            blocks.add(new TimeBlock("MEAL", "晚餐", LocalTime.of(18, 0), LocalTime.of(19, 0)));
            blocks.add(new TimeBlock("LEISURE", "晚间放松", LocalTime.of(19, 0), LocalTime.of(22, 0)));
            blocks.add(new TimeBlock("LATE_NIGHT", "准备休息", LocalTime.of(22, 0), LocalTime.of(s.sleepStart, 0)));
        } else {
            blocks.add(new TimeBlock("MORNING", "起床洗漱", LocalTime.of(s.sleepEnd, 0), LocalTime.of(s.workStart, 0)));
            blocks.add(new TimeBlock("WORK", "上班", LocalTime.of(s.workStart, 0), LocalTime.of(12, 0)));
            blocks.add(new TimeBlock("MEAL", "午餐", LocalTime.of(12, 0), LocalTime.of(13, 30)));
            blocks.add(new TimeBlock("WORK", "下午工作", LocalTime.of(13, 30), LocalTime.of(s.workEnd, 0)));
            blocks.add(new TimeBlock("COMMUTE", "下班路上", LocalTime.of(s.workEnd, 0), LocalTime.of(s.workEnd + 1, 0)));
            blocks.add(new TimeBlock("MEAL", "晚餐", LocalTime.of(s.workEnd + 1, 0), LocalTime.of(s.workEnd + 2, 0)));
            blocks.add(new TimeBlock("LEISURE", "晚间自由时间", LocalTime.of(s.workEnd + 2, 0), LocalTime.of(22, 0)));
            blocks.add(new TimeBlock("LATE_NIGHT", "准备休息", LocalTime.of(22, 0), LocalTime.of(s.sleepStart, 0)));
        }
        blocks.add(new TimeBlock("SLEEP", "睡觉", LocalTime.of(s.sleepStart, 0), LocalTime.of(23, 59)));
        return blocks;
    }

    /** 此刻她在做什么(注入 Prompt, 让回复带生活感) */
    public String describe(String companionId, String name, LocalDateTime now) {
        Activity a = activityFor(companionId, now);
        String base = switch (a) {
            case SLEEP -> "正在休息";
            case MORNING -> "刚起床,在收拾准备新的一天";
            case WORK_BUSY -> "正在忙工作";
            case LUNCH -> "在午休";
            case WORK_AFTERNOON -> "还在忙下午的工作";
            case EVENING -> "刚下班,在忙自己的事";
            case LEISURE -> "在悠闲地享受自己的时间";
            case LATE_NIGHT -> "准备休息了";
        };
        String week = switch (now.getDayOfWeek()) {
            case MONDAY -> "周一"; case TUESDAY -> "周二"; case WEDNESDAY -> "周三";
            case THURSDAY -> "周四"; case FRIDAY -> "周五"; case SATURDAY -> "周六"; case SUNDAY -> "周日";
        };
        return String.format("现在是%s %s,%s%s。", week,
                now.format(DateTimeFormatter.ofPattern("HH:mm")), name, base);
    }

    /** 主动消息倾向: 忙碌越低、休闲越高 */
    public double proactiveFactor(String companionId, LocalDateTime now) {
        return switch (activityFor(companionId, now)) {
            case SLEEP -> 0.0;
            case WORK_BUSY, WORK_AFTERNOON -> 0.35;
            case MORNING -> 0.6;
            case LATE_NIGHT -> 0.65;
            case LUNCH, EVENING -> 0.9;
            case LEISURE -> 1.2;
        };
    }
}
