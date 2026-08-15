package com.luxera.companion.state;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AgentStateService {

    private final AgentStateRepository repo;

    public AgentStateService(AgentStateRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public AgentState getOrCreate(String companionId) {
        return repo.findByCompanionId(companionId).orElseGet(() -> {
            AgentState s = new AgentState();
            s.setCompanionId(companionId);
            return repo.save(s);
        });
    }

    @Transactional
    public AgentState get(String companionId) {
        return repo.findByCompanionId(companionId).orElse(null);
    }

    /** 每次对话后状态微演化(人格不变,状态可变;设计文档 36-37 节) */
    @Transactional
    public void onMessage(String companionId, String emotion) {
        AgentState s = getOrCreate(companionId);
        s.setEnergy(clamp(s.getEnergy() - 0.004));
        s.setStress(clamp(s.getStress() + 0.001));
        s.setSocialEnergy(clamp(s.getSocialEnergy() - 0.002));
        s.setEmotionalCloseness(clamp(s.getEmotionalCloseness() + 0.0009));
        s.setCuriosity(clamp(s.getCuriosity() + 0.001));
        if (emotion != null) {
            s.setMood(moodFor(emotion));
        }
        repo.save(s);
    }

    @Transactional
    public void applyEvent(String companionId, String eventType, double intensity) {
        AgentState s = getOrCreate(companionId);
        switch (eventType) {
            case "negative" -> {
                s.setStress(clamp(s.getStress() + intensity));
                s.setEnergy(clamp(s.getEnergy() - intensity * 0.6));
                s.setMood("低落的");
            }
            case "positive" -> {
                s.setEnergy(clamp(s.getEnergy() + intensity * 0.5));
                s.setStress(clamp(s.getStress() - intensity * 0.3));
                s.setMood("轻快的");
            }
            default -> { }
        }
        repo.save(s);
    }

    /** V4 Appraisal: 消息改变内部状态(hurt/anger 累积, warmth 增进亲密度/平复情绪) */
    @Transactional
    public void applyAppraisal(String companionId, double hurt, double anger, double warmth) {
        AgentState s = getOrCreate(companionId);
        if (hurt > 0) {
            s.setHurt(clamp(s.getHurt() + hurt));
            s.setMood("有点受伤");
        }
        if (anger > 0) {
            s.setAnger(clamp(s.getAnger() + anger));
            s.setStress(clamp(s.getStress() + anger * 0.3));
            s.setMood("有点生气");
        }
        if (warmth > 0) {
            s.setHurt(clamp(s.getHurt() - warmth * 0.5));
            s.setAnger(clamp(s.getAnger() - warmth * 0.5));
            s.setEmotionalCloseness(clamp(s.getEmotionalCloseness() + warmth * 0.02));
            if (s.getHurt() < 0.2 && s.getAnger() < 0.2) {
                s.setMood("平静的");
            }
        }
        repo.save(s);
    }

    /** V4: hurt/anger 随时间衰减(状态愈合) */
    @Transactional
    public void decayNegative(String companionId, double rate) {
        repo.findByCompanionId(companionId).ifPresent(s -> {
            s.setHurt(clamp(s.getHurt() - rate));
            s.setAnger(clamp(s.getAnger() - rate));
            repo.save(s);
        });
    }

    /** V4: 全部伴侣的负面情绪衰减(定时任务调用, 负面情绪会随时间自然愈合) */
    @Transactional
    public void decayAllNegative(double rate) {
        for (AgentState s : repo.findAll()) {
            boolean changed = false;
            if (s.getHurt() > 0.001) {
                s.setHurt(clamp(s.getHurt() - rate));
                changed = true;
            }
            if (s.getAnger() > 0.001) {
                s.setAnger(clamp(s.getAnger() - rate));
                changed = true;
            }
            if (changed) {
                if (s.getHurt() < 0.15 && s.getAnger() < 0.15 && !"平静的".equals(s.getMood())) {
                    s.setMood("平静的");
                }
                repo.save(s);
            }
        }
    }

    private static String moodFor(String emotion) {
        switch (emotion == null ? "" : emotion) {
            case "sad": return "有点心疼";
            case "happy":
            case "excited": return "轻快的";
            case "anxious": return "有点担心";
            case "angry": return "有些气不过";
            case "tired": return "安静的";
            default: return "平静的";
        }
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
