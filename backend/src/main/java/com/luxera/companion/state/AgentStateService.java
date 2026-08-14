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
