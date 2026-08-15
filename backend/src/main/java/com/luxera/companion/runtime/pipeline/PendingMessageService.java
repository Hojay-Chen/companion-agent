package com.luxera.companion.runtime.pipeline;

import com.luxera.companion.conversation.Message;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 未回复消息服务(V5 §79/§81): "不回复"也是状态 —— 保存已读未回 + 下次复查时间。
 * 到点由 {@code PendingMessageReevaluationJob} 唤醒 Brain 重新评估。
 */
@Service
public class PendingMessageService {

    private final PendingMessageStateRepository repo;

    public PendingMessageService(PendingMessageStateRepository repo) {
        this.repo = repo;
    }

    /** 记录一条"已读但不回"的消息 */
    @Transactional
    public PendingMessageState defer(Message message, String companionId, String userId,
                                     String reason, LocalDateTime nextReviewAt) {
        // 已有同消息记录 → 更新复查时间
        Optional<PendingMessageState> existing = repo.findByMessageIdAndStatus(message.getId(), PendingMessageState.STATUS_PENDING);
        if (existing.isPresent()) {
            PendingMessageState e = existing.get();
            e.setNextReviewAt(nextReviewAt);
            e.setReason(reason);
            e.setReadAt(LocalDateTime.now());
            return repo.save(e);
        }
        PendingMessageState p = new PendingMessageState();
        p.setMessageId(message.getId());
        p.setCompanionId(companionId);
        p.setConversationId(message.getConversationId());
        p.setUserId(userId);
        p.setSenderText(message.getContent());
        p.setRead(true);
        p.setReadAt(LocalDateTime.now());
        p.setNextReviewAt(nextReviewAt);
        p.setReason(reason);
        return repo.save(p);
    }

    /** 到期的待复查消息(已读未回, 到复查点) */
    @Transactional(readOnly = true)
    public List<PendingMessageState> dueForReview(LocalDateTime now) {
        return repo.findByStatusAndNextReviewAtLessThanEqualOrderByNextReviewAtAsc(
                PendingMessageState.STATUS_PENDING, now);
    }

    @Transactional(readOnly = true)
    public List<PendingMessageState> pendingFor(String companionId) {
        return repo.findByCompanionIdAndStatus(companionId, PendingMessageState.STATUS_PENDING);
    }

    /** 标记已回复(消息回复后调用) */
    @Transactional
    public void markReplied(String messageId) {
        repo.findByMessageIdAndStatus(messageId, PendingMessageState.STATUS_PENDING).ifPresent(p -> {
            p.setReplied(true);
            p.setStatus(PendingMessageState.STATUS_REPLIED);
            repo.save(p);
        });
    }

    /** 标记过期(复查多次后仍决定不回 → 放下这件事, 符合"人偶尔忘记") */
    @Transactional
    public void markExpired(String messageId) {
        repo.findByMessageIdAndStatus(messageId, PendingMessageState.STATUS_PENDING).ifPresent(p -> {
            p.setStatus(PendingMessageState.STATUS_EXPIRED);
            repo.save(p);
        });
    }

    @Transactional(readOnly = true)
    public Optional<PendingMessageState> findByMessageId(String messageId) {
        return repo.findByMessageId(messageId);
    }
}
