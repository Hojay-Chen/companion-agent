package com.luxera.companion.agent;

import com.luxera.companion.persona.Persona;
import com.luxera.companion.persona.PersonaService;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 日常时间表(模拟真人作息)。
 * V7 增强: 除 companionId 确定性派生外, 读取 persona 的工作设定 ——
 * 若 persona 描述含"酒吧/夜班/晚班/上夜班"等夜班特征, 生成颠倒作息(夜猫子):
 * 白天睡觉、下午起床、傍晚到凌晨上班。这样"酒吧上夜班的她"不会一到晚上就睡觉。
 */
@Component
public class CompanionSchedule {

    public enum Activity {
        SLEEP, MORNING, WORK_BUSY, LUNCH, WORK_AFTERNOON, EVENING, LEISURE, LATE_NIGHT
    }

    /** 一天的时段块(供 Life 模拟生成 LifeActivity) */
    public record TimeBlock(String type, String title, LocalTime start, LocalTime end) {}

    /** 作息: workStart/workEnd 是上班时段; sleepStart/sleepEnd 是睡觉时段 */
    private record Schedule(int workStart, int workEnd, int sleepStart, int sleepEnd) {}

    private final PersonaService personaService;

    /** 夜班作息识别缓存: companionId → 是否夜班(短 TTL, 避免每次查库) */
    private final Map<String, Boolean> nightWorkerCache = new ConcurrentHashMap<>();
    private final Map<String, Long> nightWorkerCacheTs = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 10 * 60 * 1000;   // 10 分钟

    public CompanionSchedule(PersonaService personaService) {
        this.personaService = personaService;
    }

    /** 由 companionId 派生的确定性作息(同一人每次结果一致) */
    private Schedule scheduleFor(String companionId) {
        if (isNightWorker(companionId)) {
            // 酒吧/夜班作息: 4:30-12:30 睡觉, 18:00-02:00 上班(酒吧晚高峰)
            return new Schedule(18, 26, 4, 12);
        }
        int h = Math.floorMod(companionId == null ? 0 : companionId.hashCode(), 1000);
        int workStart = 8 + h % 3;          // 8-10 点上班
        int workEnd = 17 + (h / 3) % 3;     // 17-19 点下班
        int sleepEnd = 6 + (h / 9) % 2;     // 6-7 点起床
        int sleepStart = 22 + (h / 27) % 2; // 22-23 点睡
        return new Schedule(workStart, workEnd, sleepStart, sleepEnd);
    }

    /**
     * 判断该伴侣是否为"夜班工作者"(酒吧/夜班/晚班)。
     * 读取 persona 的 personality.summary + 身份描述, 命中关键词返回 true。
     */
    boolean isNightWorker(String companionId) {
        if (companionId == null) return false;
        long now = System.currentTimeMillis();
        Long cachedTs = nightWorkerCacheTs.get(companionId);
        if (cachedTs != null && now - cachedTs < CACHE_TTL_MS) {
            return Boolean.TRUE.equals(nightWorkerCache.get(companionId));
        }
        boolean night = detectNightWorker(companionId);
        nightWorkerCache.put(companionId, night);
        nightWorkerCacheTs.put(companionId, now);
        return night;
    }

    private boolean detectNightWorker(String companionId) {
        try {
            Persona persona = personaService.getActive(companionId);
            if (persona == null) return false;
            StringBuilder text = new StringBuilder();
            if (persona.getPersonality() != null && persona.getPersonality().getSummary() != null) {
                text.append(persona.getPersonality().getSummary()).append(' ');
            }
            if (persona.getLife() != null && persona.getLife().getBackground() != null) {
                text.append(persona.getLife().getBackground()).append(' ');
            }
            String s = text.toString();
            if (s.isBlank()) return false;
            // 夜班特征关键词
            return s.contains("酒吧") || s.contains("夜班") || s.contains("晚班")
                    || s.contains("上夜班") || s.contains("夜场") || s.contains("通宵")
                    || s.contains("夜间工作") || s.contains("KTV") || s.contains("夜店");
        } catch (Exception e) {
            return false;
        }
    }

    public Activity activityFor(String companionId, LocalDateTime now) {
        Schedule s = scheduleFor(companionId);
        int hour = now.getHour();
        boolean weekend = now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY;
        boolean night = isNightWorker(companionId);

        // 夜班作息: 白天睡觉, 下午到傍晚是"早晨/起床", 18 点后上班
        if (night) {
            if (hour >= 4 && hour < 12) return Activity.SLEEP;          // 凌晨4点-中午12点睡
            if (hour >= 12 && hour < 16) return Activity.MORNING;       // 中午起床收拾
            if (hour >= 16 && hour < 18) return Activity.LUNCH;         // 下午"午饭"
            if (hour >= 18 || hour < 2) return Activity.WORK_BUSY;      // 18-凌晨2点酒吧上班
            if (hour >= 2 && hour < 4) return Activity.EVENING;         // 下班收拾
            return Activity.LATE_NIGHT;
        }

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
        boolean night = isNightWorker(companionId);

        if (night) {
            // 夜班作息: 4-12 睡, 12-16 起床收拾, 16-18 晚饭, 18-02 上班, 02-04 下班收拾
            blocks.add(new TimeBlock("SLEEP", "睡觉", LocalTime.of(0, 0), LocalTime.of(12, 0)));
            blocks.add(new TimeBlock("MORNING", "起床洗漱", LocalTime.of(12, 0), LocalTime.of(16, 0)));
            blocks.add(new TimeBlock("MEAL", "早晚饭", LocalTime.of(16, 0), LocalTime.of(18, 0)));
            blocks.add(new TimeBlock("WORK", "酒吧上班", LocalTime.of(18, 0), LocalTime.of(23, 59)));
            blocks.add(new TimeBlock("SLEEP", "睡觉", LocalTime.of(0, 0), LocalTime.of(4, 0)));
            return blocks;
        }

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
        boolean night = isNightWorker(companionId);
        String base;
        if (night) {
            base = switch (a) {
                case SLEEP -> "在睡觉(白天补觉)";
                case MORNING -> "刚起床,在收拾准备去上班";
                case LUNCH -> "在吃今天的第一顿饭";
                case WORK_BUSY -> "正在酒吧上班,店里正忙";
                case EVENING -> "刚下班,在收拾准备回家";
                case LATE_NIGHT -> "准备休息了";
                default -> "在忙自己的事";
            };
        } else {
            base = switch (a) {
                case SLEEP -> "正在休息";
                case MORNING -> "刚起床,在收拾准备新的一天";
                case WORK_BUSY -> "正在忙工作";
                case LUNCH -> "在午休";
                case WORK_AFTERNOON -> "还在忙下午的工作";
                case EVENING -> "刚下班,在忙自己的事";
                case LEISURE -> "在悠闲地享受自己的时间";
                case LATE_NIGHT -> "准备休息了";
            };
        }
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
