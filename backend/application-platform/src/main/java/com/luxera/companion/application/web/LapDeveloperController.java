package com.luxera.companion.application.web;

import com.luxera.companion.application.domain.ApplicationStatus;
import com.luxera.companion.application.domain.ApplicationVersionRecord;
import com.luxera.companion.application.lifecycle.ApplicationLifecycleService;
import com.luxera.companion.application.lifecycle.ApplicationVersionService;
import com.luxera.companion.application.principal.PrincipalResolver;
import com.luxera.companion.application.principal.PrincipalResolvers;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.application.session.SessionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * LAP v1 §Developer API: <b>把应用推上架、把某一版的 manifest 写下去。</b>
 *
 * <p>它只有两件事, 因为本轮明确不做开发者门户前端 —— 这里给的是门户将来要调的那两个入口,
 * 不是一套临时脚本:
 * <ul>
 *   <li>{@code PATCH /applications/{id}/status} —— 推生命周期状态机;</li>
 *   <li>{@code PUT /applications/{id}/versions/{version}/manifest} —— 写一个版本的 manifest。</li>
 * </ul>
 *
 * <p><b>身份用服务密钥解析, 不是 JWT。</b> 这不是绕过鉴权, 而是说清楚"上架"这个动作属于
 * <em>平台/开发者</em>, 不属于任何一位使用者。开发者门户上线时, 它自己持服务身份, 再以其
 * 背后的自然人的名义记账 —— 那时候才需要区分具体是哪个开发者, 而 {@code developer} 表已经
 * 在那里等着了。真人拿 JWT 访问这两个端点会被
 * {@code ApplicationLifecycleService} 以 {@code LIFECYCLE_FORBIDDEN} 挡下(403)。
 *
 * <p><b>为什么写 manifest 也在这里, 而不是让它去碰内存注册表。</b> 见
 * {@code ApplicationVersionService} 的类注释: 注册表是部署的语义, 账本是数据的语义,
 * 一个 HTTP 请求只能改后者。
 */
@RestController
@RequestMapping("/api/v1")
public class LapDeveloperController {

    private final ApplicationLifecycleService lifecycle;
    private final ApplicationVersionService versions;
    private final PrincipalResolvers principals;

    public LapDeveloperController(ApplicationLifecycleService lifecycle,
                                  ApplicationVersionService versions,
                                  PrincipalResolvers principals) {
        this.lifecycle = lifecycle;
        this.versions = versions;
        this.principals = principals;
    }

    // ─────────────────────────── 生命周期 ───────────────────────────

    public record StatusRequest(String status) {}

    public record StatusResponse(String applicationId, String status, String previous) {}

    @PatchMapping("/applications/{applicationId}/status")
    public StatusResponse transition(@PathVariable String applicationId,
                                     @RequestBody StatusRequest request,
                                     @RequestHeader(value = "Authorization", required = false) String authorization,
                                     @RequestHeader(value = "X-Mcp-Principal", required = false) String mcpPrincipal,
                                     @RequestHeader(value = "X-Mcp-Service-Key", required = false) String serviceKey,
                                     @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        String previous = String.valueOf(lifecycle.statusOf(applicationId));
        ApplicationStatus target = parse(request == null ? null : request.status());
        var row = lifecycle.transition(applicationId, target,
                principal(authorization, mcpPrincipal, serviceKey, correlationId));
        return new StatusResponse(row.getId(), row.getStatus(), previous);
    }

    private static ApplicationStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new SessionException("INVALID_TRANSITION", "缺少目标状态", ActionStatus.INVALID_ARGUMENT);
        }
        try {
            return ApplicationStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new SessionException("INVALID_TRANSITION", "未知状态: " + raw,
                    ActionStatus.INVALID_ARGUMENT);
        }
    }

    // ─────────────────────────── manifest 写入 ───────────────────────────

    public record VersionResponse(String versionId, String applicationId, String version,
                                  String status, String runtimeType, String manifestHash) {

        static VersionResponse of(ApplicationVersionRecord row) {
            return new VersionResponse(row.getId(), row.getApplicationId(), row.getVersion(),
                    row.getStatus(), row.getRuntimeType(), row.getManifestHash());
        }
    }

    /** 请求体就是 manifest 原文 —— 不做一层包装, 免得"存下来的 JSON"与"发上来的 JSON"有两个形状。 */
    @PutMapping(value = "/applications/{applicationId}/versions/{version}/manifest")
    public VersionResponse writeManifest(@PathVariable String applicationId,
                                         @PathVariable String version,
                                         @RequestBody String manifestJson,
                                         @RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestHeader(value = "X-Mcp-Principal", required = false) String mcpPrincipal,
                                         @RequestHeader(value = "X-Mcp-Service-Key", required = false) String serviceKey,
                                         @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        principal(authorization, mcpPrincipal, serviceKey, correlationId);
        return VersionResponse.of(versions.saveManifest(applicationId, version, manifestJson));
    }

    @GetMapping("/applications/{applicationId}/versions")
    public List<VersionResponse> versions(@PathVariable String applicationId) {
        return versions.versionsOf(applicationId).stream().map(VersionResponse::of).toList();
    }

    // ─────────────────────────── 内部 ───────────────────────────

    /**
     * 身份从<em>请求里所有</em>可用的凭据一起解析, 而不是先判断"这是不是 MCP 请求"。
     *
     * <p>写成后者(有 MCP 头就 resolveMcp, 否则当真人)时, 一个两种凭据都没带的请求会落到
     * 某条分支的默认值上; 交给解析链则由它统一拒绝({@code UNIDENTIFIED_PRINCIPAL} → 401),
     * 这也是 REST 面其它端点走的那条路 —— 开发者面不该是唯一一个自己判断身份的控制器。
     *
     * <p>解析成功也不代表能改: 真人拿 JWT 会被 {@code ApplicationLifecycleService} 以
     * {@code LIFECYCLE_FORBIDDEN}(403)挡下 —— 上架不是使用者能决定的事。
     */
    private ResolvedPrincipal principal(String authorization, String mcpPrincipal,
                                        String serviceKey, String correlationId) {
        return principals.resolve(PrincipalResolver.PrincipalRequest.ofDeveloper(
                blankToNull(authorization), blankToNull(mcpPrincipal), blankToNull(serviceKey),
                correlationId == null || correlationId.isBlank()
                        ? UUID.randomUUID().toString()
                        : correlationId));
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }
}
