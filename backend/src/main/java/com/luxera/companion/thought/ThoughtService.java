package com.luxera.companion.thought;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ThoughtService {

    private final ThoughtRepository repo;
    private final ThoughtScorer scorer;

    public ThoughtService(ThoughtRepository repo, ThoughtScorer scorer) {
        this.repo = repo;
        this.scorer = scorer;
    }

    @Transactional
    public Thought create(String companionId, String content, String type, String triggerType, String triggerRef,
                          double importance, double emotionalWeight, double relationshipWeight,
                          double timeliness, double confidence) {
        Thought t = new Thought();
        t.setCompanionId(companionId);
        t.setContent(content);
        t.setType(type);
        t.setTriggerType(triggerType);
        t.setTriggerRef(triggerRef);
        t.setImportance(clamp(importance));
        t.setEmotionalWeight(clamp(emotionalWeight));
        t.setRelationshipWeight(clamp(relationshipWeight));
        t.setConfidence(clamp(confidence));
        t.setStrength(scorer.score(t.getImportance(), t.getEmotionalWeight(), t.getRelationshipWeight(),
                clamp(timeliness)));
        t.setExpiresAt(LocalDateTime.now().plusHours(72));
        t.setStatus("ACTIVE");
        return repo.save(t);
    }

    @Transactional(readOnly = true)
    public List<Thought> activeThoughts(String companionId) {
        return repo.findByCompanionIdAndStatusInOrderByStrengthDesc(companionId, List.of("ACTIVE", "SUPPRESSED"));
    }

    @Transactional
    public void suppress(String thoughtId) {
        repo.findById(thoughtId).ifPresent(t -> {
            t.setStatus("SUPPRESSED");
            t.setDecidedAt(LocalDateTime.now());
            repo.save(t);
        });
    }

    @Transactional
    public void act(String thoughtId) {
        repo.findById(thoughtId).ifPresent(t -> {
            t.setStatus("ACTED");
            t.setDecidedAt(LocalDateTime.now());
            repo.save(t);
        });
    }

    @Transactional
    public void resolve(String thoughtId) {
        repo.findById(thoughtId).ifPresent(t -> {
            t.setStatus("RESOLVED");
            t.setDecidedAt(LocalDateTime.now());
            repo.save(t);
        });
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
