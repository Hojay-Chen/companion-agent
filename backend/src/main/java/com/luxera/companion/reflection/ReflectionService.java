package com.luxera.companion.reflection;

import com.luxera.companion.conversation.Message;
import com.luxera.companion.conversation.MessageRepository;
import com.luxera.companion.memory.Memory;
import com.luxera.companion.memory.MemoryService;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.usermodel.UserModelService;
import com.luxera.companion.usermodel.UserPattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 反思引擎(异步,不参与实时聊天): 每日汇总 → 记忆候选 / 用户模型候选 / 伴侣日记。
 * (设计文档 48-51 节)
 */
@Slf4j
@Service
public class ReflectionService {

    private final CompanionRepository companionRepo;
    private final MessageRepository messageRepo;
    private final MemoryService memoryService;
    private final UserModelService userModelService;
    private final ReflectionRecordRepository recordRepo;

    public ReflectionService(CompanionRepository companionRepo, MessageRepository messageRepo,
                             MemoryService memoryService, UserModelService userModelService,
                             ReflectionRecordRepository recordRepo) {
        this.companionRepo = companionRepo;
        this.messageRepo = messageRepo;
        this.memoryService = memoryService;
        this.userModelService = userModelService;
        this.recordRepo = recordRepo;
    }

    @Transactional
    public List<ReflectionRecord> runAllDaily() {
        List<ReflectionRecord> results = new ArrayList<>();
        for (Companion c : companionRepo.findAll()) {
            if (c.getDeletedAt() != null) continue;
            try {
                results.add(dailyReflect(c));
            } catch (Exception e) {
                log.warn("反思失败 companion={}: {}", c.getId(), e.getMessage());
            }
        }
        return results;
    }

    @Transactional
    public ReflectionRecord dailyReflect(Companion c) {
        LocalDate today = LocalDate.now();
        LocalDateTime startOfDay = today.atStartOfDay();
        String userId = c.getUserId();

        // 今日用户消息
        List<Message> todayMessages = messageRepo.findUserMessagesSince(c.getId(), startOfDay);
        long todayCount = todayMessages.size();

        // 近 7 天深夜活跃模式
        List<Message> weekMessages = messageRepo.findUserMessagesSince(c.getId(), LocalDateTime.now().minusDays(7));
        long lateCount = weekMessages.stream()
                .filter(m -> {
                    int h = m.getCreatedAt().getHour();
                    return h >= 23 || h < 2;
                }).count();
        boolean latePattern = lateCount >= 3;
        if (latePattern) {
            UserPattern p = new UserPattern();
            p.setPattern("user_often_works_late");
            p.setDescription("最近经常深夜(23点后)还在忙");
            p.setConfidence(0.72);
            p.setEvidenceCount((int) lateCount);
            p.setEvidence(List.of("最近7天有 " + lateCount + " 条深夜消息"));
            userModelService.savePattern(userId, c.getId(), p);
        }

        // 伴侣日记(episodic)
        if (todayCount > 0) {
            Memory diary = new Memory();
            diary.setUserId(userId);
            diary.setCompanionId(c.getId());
            diary.setType("episodic");
            diary.setContent("今天和用户聊了 " + todayCount + " 条消息"
                    + (latePattern ? ",用户又忙到很晚" : ",气氛还不错"));
            diary.setSummary("每日反思日记");
            diary.setImportance(0.4);
            diary.setOccurredAt(LocalDateTime.now());
            diary.setSourceType("reflection");
            diary.setSourceId("daily");
            memoryService.save(diary);
        }

        ReflectionRecord rec = new ReflectionRecord();
        rec.setUserId(userId);
        rec.setCompanionId(c.getId());
        rec.setType("daily");
        rec.setPeriod(today.toString());
        rec.setSummary((todayCount > 0 ? "你们今天聊了 " + todayCount + " 条消息。" : "今天没有聊天。")
                + (latePattern ? "注意到用户常在深夜活跃。" : ""));
        rec.setInsights(new ArrayList<>());
        rec.setUserModelCandidates(latePattern
                ? List.of(java.util.Map.of("pattern", "user_often_works_late", "confidence", 0.72))
                : new ArrayList<>());
        return recordRepo.save(rec);
    }
}
