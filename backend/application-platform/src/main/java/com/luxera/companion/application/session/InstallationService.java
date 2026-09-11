package com.luxera.companion.application.session;

import com.luxera.companion.application.domain.ApplicationVersionRecord;
import com.luxera.companion.application.domain.InstallationRecord;
import com.luxera.companion.application.domain.PermissionGrantRecord;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.ManifestRegistry;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.ApplicationVersionRepository;
import com.luxera.companion.application.repository.InstallationRepository;
import com.luxera.companion.application.repository.PermissionGrantRepository;
import com.luxera.companion.contracts.application.PermissionLevel;
import com.luxera.companion.contracts.application.RiskLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * LAP v1: 安装 —— 归属链的第二环, 也是权限模型的第一维。
 *
 * <p>没有安装就没有会话, 没有会话就没有资源, 没有授权就调不动动作。
 * {@code Application → Installation → ApplicationSession → Resource} 这条链上, 本类是
 * 唯一能创建第二环的地方。
 *
 * <p><b>安装钉住版本。</b>{@code application_version_id} 在安装时定下来, 之后应用发新版本
 * 不影响这次安装 —— 升级是显式动作, 不是"你昨天装的井字棋今天变成了五子棋"。于是
 * {@code action_invocation} 里指着的那个版本永远能解释得通。
 *
 * <p><b>默认授权 = 这个应用声明的全部能力。</b>安装的语义就是"我允许这个应用使用它自己声明的
 * 能力"; 每一项的级别与风险上限取该能力下动作的最高值, 再逐个动作过风险带
 * (MEDIUM 要确认, HIGH/CRITICAL 拒绝)。所以"安装了就能干任何事"并不成立 —— 安装只是
 * 必要条件, 风险带仍然独立把关。
 */
@Slf4j
@Service
public class InstallationService {

    private final ManifestRegistry manifests;
    private final ApplicationVersionRepository versions;
    private final InstallationRepository installations;
    private final PermissionGrantRepository grants;

    public InstallationService(ManifestRegistry manifests,
                               ApplicationVersionRepository versions,
                               InstallationRepository installations,
                               PermissionGrantRepository grants) {
        this.manifests = manifests;
        this.versions = versions;
        this.installations = installations;
        this.grants = grants;
    }

    /** 安装结果 —— 顺带把会话一起开出来, 因为调用方下一步总是要一个会话。 */
    public record InstallResult(String installationId,
                                String sessionId,
                                String applicationId,
                                String version,
                                List<String> capabilities) {
    }

    /**
     * 幂等: 同一个 principal 重复安装同一个应用返回同一条安装, 只补齐缺失的授权。
     */
    @Transactional
    public InstallationRecord install(String applicationId, ResolvedPrincipal principal,
                                     List<String> requestedCapabilities) {
        ApplicationManifest manifest = manifests.published(applicationId).orElseThrow(() ->
                new SessionException("UNKNOWN_APPLICATION", "没有已发布的应用 " + applicationId));
        ApplicationVersionRecord version = versions
                .findByApplicationIdAndVersion(applicationId, manifest.version())
                .orElseThrow(() -> new SessionException("VERSION_NOT_PUBLISHED",
                        "应用 " + applicationId + " v" + manifest.version() + " 没有已发布版本行"));

        List<String> wanted = requestedCapabilities == null || requestedCapabilities.isEmpty()
                ? manifest.capabilities().stream().map(ApplicationManifest.CapabilityDecl::id).toList()
                : List.copyOf(requestedCapabilities);
        for (String capabilityId : wanted) {
            if (!manifest.declaresCapability(capabilityId)) {
                throw new SessionException("UNKNOWN_CAPABILITY",
                        "应用 " + applicationId + " 没有声明能力 " + capabilityId);
            }
        }

        InstallationRecord installation = installations
                .findByApplicationIdAndPrincipalTypeAndPrincipalId(
                        applicationId, principal.typeName(), principal.principalId())
                .orElseGet(InstallationRecord::new);
        installation.setApplicationId(applicationId);
        installation.setApplicationVersionId(version.getId());
        installation.setPrincipalType(principal.typeName());
        installation.setPrincipalId(principal.principalId());
        installation.setStatus(InstallationRecord.STATUS_ACTIVE);
        installations.save(installation);

        grantMissing(installation, manifest, wanted);
        log.info("[Installation] {} {} 安装 {} v{} (grant {} 项)",
                principal.typeName(), principal.principalId(), applicationId, manifest.version(), wanted);
        return installation;
    }

    @Transactional
    public void uninstall(String applicationId, ResolvedPrincipal principal) {
        installations.findByApplicationIdAndPrincipalTypeAndPrincipalId(
                        applicationId, principal.typeName(), principal.principalId())
                .ifPresent(row -> {
                    row.setStatus(InstallationRecord.STATUS_UNINSTALLED);
                    installations.save(row);
                    grants.deleteByInstallationId(row.getId());
                });
    }

    public InstallationRecord requireInstallation(String applicationId, ResolvedPrincipal principal) {
        return installations.findByApplicationIdAndPrincipalTypeAndPrincipalId(
                        applicationId, principal.typeName(), principal.principalId())
                .orElseThrow(() -> new SessionException("NOT_INSTALLED",
                        principal.typeName() + " " + principal.principalId()
                                + " 没有安装 " + applicationId));
    }

    /** 只补齐还没有的授权 —— 重复安装不该覆盖调用方手工调过的级别。 */
    private void grantMissing(InstallationRecord installation,
                              ApplicationManifest manifest,
                              List<String> capabilityIds) {
        List<PermissionGrantRecord> existing = grants.findByInstallationId(installation.getId());
        for (String capabilityId : capabilityIds) {
            boolean present = existing.stream()
                    .anyMatch(g -> capabilityId.equals(g.getCapabilityId()) && g.getActionId() == null);
            if (present) {
                continue;
            }
            PermissionGrantRecord grant = new PermissionGrantRecord();
            grant.setInstallationId(installation.getId());
            grant.setCapabilityId(capabilityId);
            grant.setPermissionLevel(highestLevel(manifest, capabilityId).name());
            grant.setRiskCeiling(highestRisk(manifest, capabilityId).name());
            grants.save(grant);
        }
    }

    /** 该能力下动作要求的最高权限级别 —— 授权不能低于它任何一项。 */
    static PermissionLevel highestLevel(ApplicationManifest manifest, String capabilityId) {
        PermissionLevel highest = PermissionLevel.READ;
        for (ApplicationManifest.ActionDecl action : actionsOf(manifest, capabilityId)) {
            if (rank(action.permission()) > rank(highest)) {
                highest = action.permission();
            }
        }
        return highest;
    }

    static RiskLevel highestRisk(ApplicationManifest manifest, String capabilityId) {
        RiskLevel highest = RiskLevel.NONE;
        for (ApplicationManifest.ActionDecl action : actionsOf(manifest, capabilityId)) {
            if (rank(action.risk()) > rank(highest)) {
                highest = action.risk();
            }
        }
        return highest;
    }

    private static List<ApplicationManifest.ActionDecl> actionsOf(ApplicationManifest manifest, String capabilityId) {
        List<ApplicationManifest.ActionDecl> out = new ArrayList<>();
        for (ApplicationManifest.ActionDecl action : manifest.actions()) {
            if (capabilityId.equals(action.capability())) {
                out.add(action);
            }
        }
        return out;
    }

    private static int rank(PermissionLevel level) {
        return level == null ? -1 : level.ordinal();
    }

    private static int rank(RiskLevel level) {
        return level == null ? -1 : level.ordinal();
    }
}
