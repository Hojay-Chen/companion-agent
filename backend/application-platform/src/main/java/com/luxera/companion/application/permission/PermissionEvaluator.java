package com.luxera.companion.application.permission;

import com.luxera.companion.application.domain.InstallationRecord;
import com.luxera.companion.application.domain.PermissionGrantRecord;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.repository.InstallationRepository;
import com.luxera.companion.application.repository.PermissionGrantRepository;
import com.luxera.companion.contracts.application.PrincipalType;
import com.luxera.companion.contracts.application.RiskLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * LAP v1: <b>权限 = Principal × Installation grant × Capability × Action × Risk。</b>
 *
 * <p>五个维度缺一不可, 而且顺序是有意义的 —— 从"你装了没有"开始, 因为那是最根本的一问:
 * 没装就是没装, 后面谈授权没有意义。
 *
 * <pre>
 *   1. Installation 存在且 ACTIVE?              否 → NOT_INSTALLED / INSTALLATION_INACTIVE
 *   2. 有覆盖这个动作的 grant?                  否 → NOT_AUTHORIZED
 *   3. grant 没过期?                            否 → GRANT_EXPIRED
 *   4. grant 的级别 ≥ 动作要求的级别?            否 → NOT_AUTHORIZED
 *   5. 动作风险 ≤ grant 的风险上限?              否 → RISK_TOO_HIGH
 *   6. 动作风险本身允许无人值守执行?             中 → REQUIRE_CONFIRMATION; 高 → RISK_TOO_HIGH
 * </pre>
 *
 * <p><b>第 6 步是刻意的保守</b>: {@code MEDIUM} 要求显式确认, {@code HIGH}/{@code CRITICAL}
 * 直接拒绝。不是因为做不到, 而是因为在一个真人+Agent 共处的系统里, "Agent 自主执行了一个高
 * 风险动作"是目前还没有合适的兜底机制去承接的形态。等有了, 再放开。
 *
 * <p>这个类<em>不</em>碰 {@code contracts} 的 {@code PermissionLevel}/{@code RiskLevel} 定义,
 * 只是按枚举声明顺序比较 —— 顺序即严重度, 改顺序就是改语义, 所以枚举里没有任何 {@code UNKNOWN}
 * 之类的填充值。
 */
@Slf4j
@Service
public class PermissionEvaluator {

    private final InstallationRepository installations;
    private final PermissionGrantRepository grants;

    public PermissionEvaluator(InstallationRepository installations, PermissionGrantRepository grants) {
        this.installations = installations;
        this.grants = grants;
    }

    public PermissionDecision evaluate(ApplicationManifest manifest,
                                       ApplicationManifest.ActionDecl spec,
                                       PrincipalType principalType,
                                       String principalId) {
        if (principalType == null || principalId == null || principalId.isBlank()) {
            return PermissionDecision.deny("PRINCIPAL_REQUIRED", "调用方身份缺失");
        }
        if (spec == null) {
            return PermissionDecision.deny("ACTION_NOT_FOUND", "动作不存在");
        }

        InstallationRecord installation = installations
                .findByApplicationIdAndPrincipalTypeAndPrincipalId(
                        manifest.applicationId(), principalType.name(), principalId)
                .orElse(null);
        if (installation == null) {
            return PermissionDecision.deny("NOT_INSTALLED",
                    principalType + " " + principalId + " 没有安装 " + manifest.applicationId());
        }
        if (!installation.active()) {
            return PermissionDecision.deny("INSTALLATION_INACTIVE",
                    "安装已 " + installation.getStatus());
        }

        if (spec.isRead()) {
            // 读动作只要装了就能读: 它的风险由 manifest 声明为 NONE 是被校验过的,
            // 而"读一眼自己装的应用的状态"再要一次授权只会让 UI 变得莫名其妙。
            return PermissionDecision.allow();
        }

        List<PermissionGrantRecord> covering = grants.findByInstallationId(installation.getId()).stream()
                .filter(g -> g.covers(spec.id(), spec.capability()))
                .toList();
        if (covering.isEmpty()) {
            return PermissionDecision.deny("NOT_AUTHORIZED",
                    "安装没有授予动作 " + spec.id());
        }

        List<PermissionGrantRecord> live = covering.stream().filter(g -> !g.expired()).toList();
        if (live.isEmpty()) {
            return PermissionDecision.deny("GRANT_EXPIRED",
                    "动作 " + spec.id() + " 的授权已过期");
        }

        boolean levelOk = live.stream().anyMatch(g -> levelRank(g.getPermissionLevel()) >= levelRank(spec.permission().name()));
        if (!levelOk) {
            return PermissionDecision.deny("NOT_AUTHORIZED",
                    "授权级别低于动作 " + spec.id() + " 要求的 " + spec.permission());
        }

        boolean ceilingOk = live.stream().anyMatch(g -> riskRank(g.getRiskCeiling()) >= riskRank(spec.risk().name()));
        if (!ceilingOk) {
            return PermissionDecision.deny("RISK_TOO_HIGH",
                    "动作风险 " + spec.risk() + " 超过授权上限");
        }

        return riskBand(spec.risk());
    }

    private PermissionDecision riskBand(RiskLevel risk) {
        if (risk == null) {
            return PermissionDecision.allow();
        }
        return switch (risk) {
            case NONE, LOW -> PermissionDecision.allow();
            case MEDIUM -> PermissionDecision.confirm("CONFIRM_REQUIRED",
                    "中等风险动作需要显式确认");
            case HIGH, CRITICAL -> PermissionDecision.deny("RISK_TOO_HIGH",
                    "高风险动作 (" + risk + ") 不允许自主执行");
        };
    }

    /** READ(0) < WRITE(1) < EXECUTE(2) —— 枚举声明顺序即权限强弱。 */
    private static int levelRank(String permissionLevel) {
        return switch (permissionLevel) {
            case "READ" -> 0;
            case "WRITE" -> 1;
            case "EXECUTE" -> 2;
            default -> -1;
        };
    }

    /** NONE(0) < LOW(1) < MEDIUM(2) < HIGH(3) < CRITICAL(4)。 */
    private static int riskRank(String riskLevel) {
        return switch (riskLevel) {
            case "NONE" -> 0;
            case "LOW" -> 1;
            case "MEDIUM" -> 2;
            case "HIGH" -> 3;
            case "CRITICAL" -> 4;
            default -> -1;
        };
    }
}
