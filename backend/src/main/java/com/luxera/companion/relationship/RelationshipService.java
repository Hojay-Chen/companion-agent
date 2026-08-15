package com.luxera.companion.relationship;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityNotFoundException;

@Service
public class RelationshipService {

    private final RelationshipRepository repo;

    public RelationshipService(RelationshipRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public Relationship require(String userId, String companionId) {
        return repo.findByUserIdAndCompanionId(userId, companionId)
                .orElseThrow(() -> new EntityNotFoundException("关系不存在"));
    }

    @Transactional(readOnly = true)
    public Relationship find(String userId, String companionId) {
        return repo.findByUserIdAndCompanionId(userId, companionId).orElse(null);
    }

    /** V4 Appraisal: 微调信任/亲密度(不越过边界) */
    @Transactional
    public void updateMetrics(String userId, String companionId, double trustDelta, double intimacyDelta) {
        repo.findByUserIdAndCompanionId(userId, companionId).ifPresent(r -> {
            r.setTrust(clamp(r.getTrust() + trustDelta));
            r.setIntimacy(clamp(r.getIntimacy() + intimacyDelta));
            repo.save(r);
        });
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
