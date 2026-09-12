package com.luxera.companion.application.session;

import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.application.domain.ApplicationVersionRecord;
import com.luxera.companion.application.domain.InstallationRecord;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.ApplicationSessionRepository;
import com.luxera.companion.application.repository.ApplicationVersionRepository;
import com.luxera.companion.application.repository.InstallationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * LAP v1: <b>平台级的应用会话</b> —— {@code Application → Installation → ApplicationSession
 * → Resource} 这条链的第三环。
 *
 * <p>"平台级"是刻意的: 会话由平台创建与回收, 应用不管理自己的会话表。加一个新应用不必再想
 * 一遍"会话怎么存、怎么清"。
 *
 * <p><b>四条归属不变量在这里兑现</b>(方案原文明确要求"必须由 Domain Service 强制拒绝"):
 *
 * <pre>
 *   1. 会话的 applicationId == 请求要操作的应用
 *   2. 会话的 versionId 属于该 applicationId, 且该版本行存在
 *   3. 会话的 installationId 指向的安装, 其 applicationId 一致
 *   4. 会话的 principal == 安装的 principal
 * </pre>
 *
 * <p>写在 {@link #verifyIntegrity} <em>一个</em>方法里而不是散在四个调用点: 校验的意义在于
 * 每条路径都问同一组问题。散着写的话, 第五条不变量加进来时总会漏掉一处, 而漏掉的那一处
 * 就是跨应用引用资源的入口。
 *
 * <p>第 4 条尤其要紧: 没有它, Agent 的会话可以被真人拿去用, 于是"Agent 的操作"变成"真人的
 * 操作", 审计与权限全部失去意义。
 */
@Slf4j
@Service
public class ApplicationSessionService {

    private final ApplicationSessionRepository sessions;
    private final InstallationRepository installations;
    private final ApplicationVersionRepository versions;
    private final InstallationService installationService;

    public ApplicationSessionService(ApplicationSessionRepository sessions,
                                     InstallationRepository installations,
                                     ApplicationVersionRepository versions,
                                     InstallationService installationService) {
        this.sessions = sessions;
        this.installations = installations;
        this.versions = versions;
        this.installationService = installationService;
    }

    /** 打开一个会话。调用方必须已经安装; 没有安装就没有会话。 */
    @Transactional
    public ApplicationSessionRecord open(String applicationId, ResolvedPrincipal principal) {
        InstallationRecord installation = installationService.requireInstallation(applicationId, principal);
        ApplicationSessionRecord session = new ApplicationSessionRecord();
        session.setApplicationId(applicationId);
        session.setVersionId(installation.getApplicationVersionId());
        session.setInstallationId(installation.getId());
        session.setPrincipalType(principal.typeName());
        session.setPrincipalId(principal.principalId());
        session.setCompanionId(principal.companionId());
        session.setUserId(principal.userId());
        session.setStatus(ApplicationSessionRecord.STATUS_ACTIVE);
        session.setCorrelationId(principal.correlationId());
        session.setLastActiveAt(LocalDateTime.now());
        ApplicationSessionRecord saved = sessions.save(session);

        verifyIntegrity(saved);
        log.info("[ApplicationSession] 开启 {} principal={}:{} (安装 {})",
                applicationId, principal.typeName(), principal.principalId(), installation.getId());
        return saved;
    }

    /**
     * 找到并校验一个会话: 存在、未结束、归属自洽、且属于这个应用。
     *
     * <p><b>刻意不校验"调用方是不是会话的属主"。</b>那不是归属不变量, 而是对多参与方场景的
     * 误读: 一盘棋的会话由先落座的那个人开启, 但对手(另一个 principal)当然要能在同一个会话的
     * 资源上行动 —— 这正是 LAP 存在的理由之一。调用方<em>有没有资格</em>行动由权限层回答
     * ({@code PermissionEvaluator} 会检查它自己那份安装与授权, 没有就 {@code NOT_INSTALLED})。
     *
     * <p>会话记录与它自己那份安装之间的 principal 一致性仍然强制(第 4 条不变量) ——
     * 那说的是"这条会话是不是写歪了", 与"谁在用它"是两件事。
     *
     * @throws SessionException 任何一条不满足
     */
    public ApplicationSessionRecord requireUsable(String sessionId, String applicationId) {
        ApplicationSessionRecord session = sessions.findById(sessionId).orElseThrow(() ->
                new SessionException("UNKNOWN_SESSION", "会话不存在: " + sessionId));
        if (!session.active()) {
            throw new SessionException("SESSION_ENDED", "会话已结束: " + sessionId);
        }
        verifyIntegrity(session);
        if (applicationId != null && !applicationId.equals(session.getApplicationId())) {
            throw new SessionException("SESSION_APPLICATION_MISMATCH",
                    "会话 " + sessionId + " 属于应用 " + session.getApplicationId()
                            + ", 不是 " + applicationId);
        }
        return session;
    }

    /**
     * 四条归属不变量的唯一实现。创建时与每次使用时都跑 —— 一次写歪的数据不该因为"当时没查"
     * 而在之后一路通行。
     */
    public void verifyIntegrity(ApplicationSessionRecord session) {
        InstallationRecord installation = installations.findById(session.getInstallationId())
                .orElseThrow(() -> new SessionException("SESSION_INSTALLATION_MISMATCH",
                        "会话 " + session.getId() + " 指向的安装不存在"));
        if (!installation.getApplicationId().equals(session.getApplicationId())) {
            throw new SessionException("SESSION_INSTALLATION_MISMATCH",
                    "会话的应用 " + session.getApplicationId() + " 与安装的 "
                            + installation.getApplicationId() + " 不一致");
        }
        if (!installation.getPrincipalType().equals(session.getPrincipalType())
                || !installation.getPrincipalId().equals(session.getPrincipalId())) {
            throw new SessionException("SESSION_PRINCIPAL_MISMATCH",
                    "会话的 principal 与安装的 principal 不一致");
        }
        ApplicationVersionRecord version = versions.findById(session.getVersionId())
                .orElseThrow(() -> new SessionException("SESSION_VERSION_MISMATCH",
                        "会话 " + session.getId() + " 指向的版本不存在"));
        if (!version.getApplicationId().equals(session.getApplicationId())) {
            throw new SessionException("SESSION_VERSION_MISMATCH",
                    "版本 " + version.getId() + " 属于 " + version.getApplicationId()
                            + ", 不是 " + session.getApplicationId());
        }
    }

    @Transactional
    public void touch(String sessionId) {
        sessions.findById(sessionId).ifPresent(row -> row.setLastActiveAt(LocalDateTime.now()));
    }

    @Transactional
    public void end(String sessionId) {
        sessions.findById(sessionId).ifPresent(row -> {
            row.setStatus(ApplicationSessionRecord.STATUS_ENDED);
            sessions.save(row);
        });
    }

    /**
     * 结束空闲太久的会话, 返回条数。由 {@link SessionReaperJob} 定期调用。
     *
     * <p>只动 {@code ACTIVE} 的: 已经结束的会话若被反复 save, 会不断刷新
     * {@code last_active_at} 之外的字段并让 JPA 产生无谓的 UPDATE。
     */
    @Transactional
    public int reapIdle(long idleHours) {
        if (idleHours <= 0) {
            return 0;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusHours(idleHours);
        List<ApplicationSessionRecord> idle =
                sessions.findByStatusAndLastActiveAtBefore(ApplicationSessionRecord.STATUS_ACTIVE, cutoff);
        for (ApplicationSessionRecord row : idle) {
            row.setStatus(ApplicationSessionRecord.STATUS_ENDED);
            sessions.save(row);
        }
        return idle.size();
    }

    public List<ApplicationSessionRecord> ofCompanion(String companionId) {
        return sessions.findByCompanionIdAndStatus(companionId, ApplicationSessionRecord.STATUS_ACTIVE);
    }

    public List<ApplicationSessionRecord> ofInstallation(String installationId) {
        return sessions.findByInstallationId(installationId);
    }
}
