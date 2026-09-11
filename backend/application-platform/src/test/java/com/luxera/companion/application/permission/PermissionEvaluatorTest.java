package com.luxera.companion.application.permission;

import com.luxera.companion.application.domain.InstallationRecord;
import com.luxera.companion.application.domain.PermissionGrantRecord;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.ManifestRegistry;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.InstallationRepository;
import com.luxera.companion.application.repository.PermissionGrantRepository;
import com.luxera.companion.application.session.InstallationService;
import com.luxera.companion.contracts.application.AttentionPolicy;
import com.luxera.companion.contracts.application.PermissionLevel;
import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.contracts.application.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限 = <b>Principal × Installation grant × Capability × Action × Risk</b> —— 六条判定逐条。
 *
 * <p>用真实的安装与授权行, 不用 mock: 这个类的全部意义就是"这六个维度凑在一起时到底放不放行",
 * 而维度之间的顺序(没装 → 没授权 → 过期 → 级别不够 → 风险超上限 → 风险带)本身就是语义。
 * 把仓储换掉就等于把第一条抽走, 剩下的断言不再说明任何事。
 *
 * <p>风险带那几条用<em>构造出来的</em> {@code ActionDecl}: 内置 manifest 里没有 MEDIUM/HIGH 动作,
 * 而"中等风险要确认、高风险直接拒绝"是刻意保守的设计, 必须有测试钉住 —— 否则某天有人为了让某个
 * 动作跑通而把 HIGH 改成 LOW, 不会有任何东西阻止他。
 */
@ActiveProfiles("test")
@SpringBootTest
class PermissionEvaluatorTest {

    private static final String APP_ID = "com.luxera.tictactoe";

    @Autowired
    PermissionEvaluator evaluator;

    @Autowired
    InstallationService installationService;

    @Autowired
    InstallationRepository installations;

    @Autowired
    PermissionGrantRepository grants;

    @Autowired
    ManifestRegistry manifests;

    // ─────────────────────────── 六条判定 ───────────────────────────

    @Test
    void uninstalledPrincipalIsDeniedWithAStableCode() {
        PermissionDecision decision = evaluate("stranger-" + UUID.randomUUID(), action("game.make_move"));

        assertTrue(decision.denied());
        assertEquals("NOT_INSTALLED", decision.code());
    }

    @Test
    void suspendedInstallationIsDenied() {
        String principalId = installFresh();
        InstallationRecord row = installationOf(principalId);
        row.setStatus(InstallationRecord.STATUS_SUSPENDED);
        installations.saveAndFlush(row);

        assertEquals("INSTALLATION_INACTIVE", evaluate(principalId, action("game.make_move")).code());
    }

    /** 装了但没授权 → NOT_AUTHORIZED; 与"没装"是两种不同的拒绝, 客户端要能分辨。 */
    @Test
    void missingGrantIsDenied() {
        String principalId = installFresh();
        dropGrants(principalId);

        PermissionDecision decision = evaluate(principalId, action("game.make_move"));
        assertEquals("NOT_AUTHORIZED", decision.code());
        assertFalse(decision.allowed());
    }

    @Test
    void expiredGrantIsDenied() {
        String principalId = installFresh();
        PermissionGrantRecord grant = onlyGrant(principalId);
        grant.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        grants.saveAndFlush(grant);

        assertEquals("GRANT_EXPIRED", evaluate(principalId, action("game.make_move")).code());
    }

    @Test
    void grantLevelBelowTheActionIsDenied() {
        String principalId = installFresh();
        PermissionGrantRecord grant = onlyGrant(principalId);
        grant.setPermissionLevel(PermissionLevel.READ.name());   // 动作要 EXECUTE
        grants.saveAndFlush(grant);

        assertEquals("NOT_AUTHORIZED", evaluate(principalId, action("game.make_move")).code());
    }

    @Test
    void riskCeilingBelowTheActionIsDenied() {
        String principalId = installFresh();
        PermissionGrantRecord grant = onlyGrant(principalId);
        grant.setRiskCeiling(RiskLevel.NONE.name());   // 动作是 LOW
        grants.saveAndFlush(grant);

        assertEquals("RISK_TOO_HIGH", evaluate(principalId, action("game.make_move")).code());
    }

    // ─────────────────────────── 放行的两种情形 ───────────────────────────

    /** 读动作只要装了就能读: 它的风险由 manifest 声明为 NONE 是被校验过的, 再要一次授权只会让 UI 莫名其妙。 */
    @Test
    void readActionNeedsOnlyAnInstallation() {
        String principalId = installFresh();
        dropGrants(principalId);

        PermissionDecision decision = evaluate(principalId, action("game.state"));
        assertTrue(decision.allowed(), "装了就能读");
        assertEquals(PermissionDecision.ALLOW_CODE, decision.code());
    }

    @Test
    void installedWithGrantIsAllowed() {
        PermissionDecision decision = evaluate(installFresh(), action("game.make_move"));

        assertTrue(decision.allowed());
        assertFalse(decision.confirmationRequired());
        assertEquals("ALLOW", decision.auditLabel());
        assertEquals(PermissionDecision.ALLOW_CODE, decision.code());
    }

    // ─────────────────────────── 风险带 ───────────────────────────

    @Test
    void mediumRiskRequiresConfirmationRatherThanBeingSilentlyExecuted() {
        String principalId = installFresh();
        raiseCeiling(principalId, RiskLevel.MEDIUM);

        PermissionDecision decision = evaluate(principalId, probe(RiskLevel.MEDIUM));

        assertFalse(decision.allowed(), "允许为 false —— 但原因不是拒绝");
        assertTrue(decision.confirmationRequired());
        assertEquals("REQUIRE_CONFIRMATION", decision.auditLabel());
        assertEquals("CONFIRM_REQUIRED", decision.code());
        assertFalse(decision.denied(), "待确认不是拒绝: 确认之后动作仍然可以执行");
    }

    @Test
    void highAndCriticalRiskAreRefusedOutright() {
        String principalId = installFresh();
        raiseCeiling(principalId, RiskLevel.CRITICAL);   // 先放开上限, 让拒绝确实来自风险带

        for (RiskLevel risk : new RiskLevel[]{RiskLevel.HIGH, RiskLevel.CRITICAL}) {
            PermissionDecision decision = evaluate(principalId, probe(risk));
            assertTrue(decision.denied(), risk + " 必须被拒绝");
            assertEquals("RISK_TOO_HIGH", decision.code());
        }
    }

    // ─────────────────────────── 身份与动作缺失 ───────────────────────────

    @Test
    void missingPrincipalIsDeniedBeforeAnythingElse() {
        assertEquals("PRINCIPAL_REQUIRED", evaluate(null, action("game.make_move")).code());

        ApplicationManifest manifest = published();
        assertEquals("PRINCIPAL_REQUIRED",
                evaluator.evaluate(manifest, manifest.action("game.make_move").orElseThrow(),
                        PrincipalType.HUMAN, "   ").code());
        assertEquals("PRINCIPAL_REQUIRED",
                evaluator.evaluate(manifest, manifest.action("game.make_move").orElseThrow(),
                        null, "someone").code());
    }

    @Test
    void missingActionIsDenied() {
        ApplicationManifest manifest = published();
        assertEquals("ACTION_NOT_FOUND",
                evaluator.evaluate(manifest, null, PrincipalType.HUMAN, "someone").code());
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private PermissionDecision evaluate(String principalId, ApplicationManifest.ActionDecl spec) {
        return evaluator.evaluate(published(), spec, PrincipalType.HUMAN, principalId);
    }

    /** 装一个全新的 principal 并返回它的 id —— 每个用例一个, 免得互相污染授权行。 */
    private String installFresh() {
        String principalId = "perm-" + UUID.randomUUID();
        installationService.install(APP_ID, human(principalId), null);
        return principalId;
    }

    private InstallationRecord installationOf(String principalId) {
        return installations
                .findByApplicationIdAndPrincipalTypeAndPrincipalId(APP_ID, PrincipalType.HUMAN.name(), principalId)
                .orElseThrow(() -> new AssertionError("安装应当存在: " + principalId));
    }

    private PermissionGrantRecord onlyGrant(String principalId) {
        var found = grants.findByInstallationId(installationOf(principalId).getId());
        assertEquals(1, found.size(), "安装默认给该能力一条授权");
        return found.get(0);
    }

    private void dropGrants(String principalId) {
        grants.deleteAll(grants.findByInstallationId(installationOf(principalId).getId()));
    }

    private void raiseCeiling(String principalId, RiskLevel ceiling) {
        PermissionGrantRecord grant = onlyGrant(principalId);
        grant.setRiskCeiling(ceiling.name());
        grants.saveAndFlush(grant);
    }

    private ApplicationManifest published() {
        return manifests.published(APP_ID).orElseThrow(() -> new AssertionError("内置应用应当已注册"));
    }

    private ApplicationManifest.ActionDecl action(String actionId) {
        return published().action(actionId).orElseThrow(() -> new AssertionError("manifest 里没有 " + actionId));
    }

    /** 风险带测试用的动作: 能力与内置应用一致, 于是默认授权覆盖它, 唯一变量只剩风险。 */
    private ApplicationManifest.ActionDecl probe(RiskLevel risk) {
        return new ApplicationManifest.ActionDecl("game.probe", "game.play", "探针", "风险带测试用",
                PermissionLevel.EXECUTE, risk, AttentionPolicy.AWARE, null, null);
    }

    private static ResolvedPrincipal human(String principalId) {
        return new ResolvedPrincipal(PrincipalType.HUMAN, principalId, null, principalId,
                null, UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_JWT);
    }
}
