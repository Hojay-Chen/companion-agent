package com.luxera.companion.application.session;

import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.application.domain.ApplicationVersionRecord;
import com.luxera.companion.application.domain.InstallationRecord;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.ApplicationSessionRepository;
import com.luxera.companion.application.repository.ApplicationVersionRepository;
import com.luxera.companion.application.repository.InstallationRepository;
import com.luxera.companion.contracts.application.PrincipalType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归属链 {@code Application → Installation → ApplicationSession → Resource} 的四条不变量,
 * <b>每条一个反例</b>。
 *
 * <p>反例是绕过 Domain Service 直接改库造出来的, 因为不变量防的正是"数据已经写歪了"这件事:
 * 校验如果在创建路径上就够用, 那它就不需要在 <em>每次使用时</em> 再跑一遍。这里模拟的是
 * "当初漏查了一次"之后会发生什么 —— 每一条都必须被拦住, 而不是一路通行到跨应用引用资源。
 *
 * <p>第 4 条(会话的 principal 必须等于安装的 principal)是四条里最要紧的: 没有它, Agent 的
 * 会话可以被真人拿去用, 于是"Agent 的操作"变成"真人的操作", 权限与审计同时失效。
 */
@ActiveProfiles("test")
@SpringBootTest
class ApplicationSessionOwnershipTest {

    private static final String APP_ID = "com.luxera.tictactoe";
    private static final String OTHER_APP = "com.luxera.other";

    @Autowired
    ApplicationSessionService sessionService;

    @Autowired
    InstallationService installationService;

    @Autowired
    ApplicationSessionRepository sessions;

    @Autowired
    InstallationRepository installations;

    @Autowired
    ApplicationVersionRepository versions;

    // ─────────────────────────── 开头: 正常路径 ───────────────────────────

    @Test
    void aFreshlyOpenedSessionSatisfiesAllFourInvariants() {
        String principalId = principalId();
        installationService.install(APP_ID, human(principalId), null);

        ApplicationSessionRecord session = sessionService.open(APP_ID, human(principalId));

        assertEquals(APP_ID, session.getApplicationId());
        assertNotNull(session.getVersionId());
        assertNotNull(session.getInstallationId());
        assertEquals(PrincipalType.HUMAN.name(), session.getPrincipalType());
        assertEquals(principalId, session.getPrincipalId());
        assertTrue(session.active());
        sessionService.verifyIntegrity(session);   // 不抛即通过
    }

    /** 数字人的会话同时记住 companionId 与 userId —— 事件路由要用它们找人。 */
    @Test
    void anAgentSessionKeepsBothTheCompanionAndTheUser() {
        String companionId = "dh-" + UUID.randomUUID();
        String userId = "user-" + UUID.randomUUID();
        ResolvedPrincipal agent = new ResolvedPrincipal(PrincipalType.AGENT, companionId, companionId,
                userId, null, UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_INTERNAL);
        installationService.install(APP_ID, agent, null);

        ApplicationSessionRecord session = sessionService.open(APP_ID, agent);

        assertEquals(companionId, session.getCompanionId());
        assertEquals(userId, session.getUserId());
        assertEquals(PrincipalType.AGENT.name(), session.getPrincipalType());
    }

    /** 没有安装就没有会话 —— 归属链的第二环不能跳过。 */
    @Test
    void openingASessionWithoutAnInstallationIsRefused() {
        SessionException e = assertThrows(SessionException.class,
                () -> sessionService.open(APP_ID, human(principalId())));
        assertEquals("NOT_INSTALLED", e.code());
    }

    // ─────────────────────────── 反例 1: 应用不一致 ───────────────────────────

    @Test
    void aSessionOfAnotherApplicationIsRefused() {
        String principalId = principalId();
        installationService.install(APP_ID, human(principalId), null);
        ApplicationSessionRecord session = sessionService.open(APP_ID, human(principalId));

        SessionException e = assertThrows(SessionException.class,
                () -> sessionService.requireUsable(session.getId(), OTHER_APP));

        assertEquals("SESSION_APPLICATION_MISMATCH", e.code());
    }

    // ─────────────────────────── 反例 2: 版本不属于该应用 ───────────────────────────

    @Test
    void aSessionPointingAtAnotherApplicationsVersionIsRefused() {
        String principalId = principalId();
        installationService.install(APP_ID, human(principalId), null);
        ApplicationSessionRecord session = sessionService.open(APP_ID, human(principalId));

        ApplicationVersionRecord foreign = versionOf(OTHER_APP);
        session.setVersionId(foreign.getId());
        sessions.saveAndFlush(session);

        SessionException e = assertThrows(SessionException.class,
                () -> sessionService.verifyIntegrity(session));
        assertEquals("SESSION_VERSION_MISMATCH", e.code());
    }

    @Test
    void aSessionPointingAtAMissingVersionIsRefused() {
        String principalId = principalId();
        installationService.install(APP_ID, human(principalId), null);
        ApplicationSessionRecord session = sessionService.open(APP_ID, human(principalId));

        session.setVersionId(UUID.randomUUID().toString());
        sessions.saveAndFlush(session);

        assertEquals("SESSION_VERSION_MISMATCH",
                assertThrows(SessionException.class, () -> sessionService.verifyIntegrity(session)).code());
    }

    // ─────────────────────────── 反例 3: 安装属于另一个应用 ───────────────────────────

    @Test
    void aSessionPointingAtAnotherApplicationsInstallationIsRefused() {
        String principalId = principalId();
        installationService.install(APP_ID, human(principalId), null);
        ApplicationSessionRecord session = sessionService.open(APP_ID, human(principalId));

        InstallationRecord foreign = new InstallationRecord();
        foreign.setApplicationId(OTHER_APP);
        foreign.setApplicationVersionId(versionOf(OTHER_APP).getId());
        foreign.setPrincipalType(session.getPrincipalType());
        foreign.setPrincipalId(session.getPrincipalId());
        foreign.setStatus(InstallationRecord.STATUS_ACTIVE);

        session.setInstallationId(installations.saveAndFlush(foreign).getId());
        sessions.saveAndFlush(session);

        SessionException e = assertThrows(SessionException.class,
                () -> sessionService.verifyIntegrity(session));
        assertEquals("SESSION_INSTALLATION_MISMATCH", e.code());
    }

    @Test
    void aSessionPointingAtAMissingInstallationIsRefused() {
        String principalId = principalId();
        installationService.install(APP_ID, human(principalId), null);
        ApplicationSessionRecord session = sessionService.open(APP_ID, human(principalId));

        session.setInstallationId(UUID.randomUUID().toString());
        sessions.saveAndFlush(session);

        assertEquals("SESSION_INSTALLATION_MISMATCH",
                assertThrows(SessionException.class, () -> sessionService.verifyIntegrity(session)).code());
    }

    // ─────────────────────────── 反例 4: principal 不一致 ───────────────────────────

    /**
     * 会话被换上了另一个 principal —— 这正是"Agent 的会话被真人拿去用"的形状。
     * 注意这里安装本身是自洽的, 不一致只存在于会话与安装之间。
     */
    @Test
    void aSessionWhosePrincipalDiffersFromItsInstallationIsRefused() {
        String alice = principalId();
        String bob = principalId();
        installationService.install(APP_ID, human(alice), null);
        installationService.install(APP_ID, human(bob), null);
        ApplicationSessionRecord session = sessionService.open(APP_ID, human(alice));

        session.setPrincipalId(bob);
        sessions.saveAndFlush(session);

        SessionException e = assertThrows(SessionException.class,
                () -> sessionService.verifyIntegrity(session));
        assertEquals("SESSION_PRINCIPAL_MISMATCH", e.code());
    }

    /** 类型换了也算不一致: 同一个 id 在 HUMAN 与 AGENT 名下是两个人格。 */
    @Test
    void aSessionWhosePrincipalTypeDiffersFromItsInstallationIsRefused() {
        String principalId = principalId();
        installationService.install(APP_ID, human(principalId), null);
        ApplicationSessionRecord session = sessionService.open(APP_ID, human(principalId));

        session.setPrincipalType(PrincipalType.AGENT.name());
        sessions.saveAndFlush(session);

        assertEquals("SESSION_PRINCIPAL_MISMATCH",
                assertThrows(SessionException.class, () -> sessionService.verifyIntegrity(session)).code());
    }

    // ─────────────────────────── 使用期校验 ───────────────────────────

    @Test
    void anUnknownSessionIsRefused() {
        assertEquals("UNKNOWN_SESSION",
                assertThrows(SessionException.class,
                        () -> sessionService.requireUsable(UUID.randomUUID().toString(), APP_ID)).code());
    }

    /** 结束时清得掉, 结束之后用不了。 */
    @Test
    void anEndedSessionIsRefused() {
        String principalId = principalId();
        installationService.install(APP_ID, human(principalId), null);
        ApplicationSessionRecord session = sessionService.open(APP_ID, human(principalId));

        sessionService.end(session.getId());

        assertEquals("SESSION_ENDED",
                assertThrows(SessionException.class,
                        () -> sessionService.requireUsable(session.getId(), APP_ID)).code());
        assertFalse(sessions.findById(session.getId()).orElseThrow().active(), "状态要真的落库");
        assertEquals(1, sessionService.ofInstallation(session.getInstallationId()).size(),
                "结束的会话仍然查得到 —— 审计要看得见它存在过");
    }

    /** 传 null 表示"不校验应用" —— 调用方只在已经知道是哪个应用时才做这道额外检查。 */
    @Test
    void requireUsableToleratesANullApplicationId() {
        String principalId = principalId();
        installationService.install(APP_ID, human(principalId), null);
        ApplicationSessionRecord session = sessionService.open(APP_ID, human(principalId));

        assertEquals(session.getId(), sessionService.requireUsable(session.getId(), null).getId());
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    /**
     * 造一条别的应用的版本行出来, 好让"版本不属于本应用"是一条真的断言而不是"行不存在"。
     * 先查后建, 因为测试库不在用例之间回滚 —— 第二次跑这个类时那行已经在了。
     */
    private ApplicationVersionRecord versionOf(String applicationId) {
        return versions.findByApplicationIdAndVersion(applicationId, "9.9.9").orElseGet(() -> {
            ApplicationVersionRecord row = new ApplicationVersionRecord();
            row.setApplicationId(applicationId);
            row.setVersion("9.9.9");
            row.setManifestJson("{}");
            row.setManifestHash("deadbeef");
            row.setRuntimeType("NATIVE");
            return versions.saveAndFlush(row);
        });
    }

    private static String principalId() {
        return "own-" + UUID.randomUUID();
    }

    private static ResolvedPrincipal human(String principalId) {
        return new ResolvedPrincipal(PrincipalType.HUMAN, principalId, null, principalId,
                null, UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_JWT);
    }
}
