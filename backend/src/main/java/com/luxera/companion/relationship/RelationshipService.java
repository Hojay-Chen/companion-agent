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
}
