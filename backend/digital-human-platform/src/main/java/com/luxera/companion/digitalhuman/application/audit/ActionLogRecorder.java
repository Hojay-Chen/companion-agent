package com.luxera.companion.digitalhuman.application.audit;

import com.luxera.companion.digitalhuman.application.runtime.ActionRuntime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * V10 §14/LAP §48 审计日志写入器(失败不阻断主流程)。
 */
@Slf4j
@Component
public class ActionLogRecorder {

    private final ActionLogRepository repository;

    public ActionLogRecorder(ActionLogRepository repository) {
        this.repository = repository;
    }

    /** 记录一次动作执行(含权限决策与结果) */
    public void record(String actionId, ActionRuntime.ActionContext ctx,
                       String idempotencyKey, String executionStatus, String errorMessage) {
        try {
            ActionLogRecord r = new ActionLogRecord();
            r.setActionId(actionId);
            r.setAppCode(ctx.appCode());
            r.setCompanionId(ctx.companionId());
            r.setUserId(ctx.userId());
            r.setSessionId(ctx.sessionId());
            r.setIdempotencyKey(idempotencyKey);
            r.setPermissionDecision(executionStatus);
            r.setExecutionStatus(executionStatus);
            r.setErrorMessage(truncate(errorMessage, 500));
            r.setCorrelationId(ctx.correlationId());
            repository.save(r);
        } catch (Exception e) {
            log.warn("[ActionLog] 审计日志写入失败 {}: {}", actionId, e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() > max ? s.substring(0, max) : s);
    }
}