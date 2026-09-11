package com.luxera.companion.application.web;

import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.application.domain.InstallationRecord;
import com.luxera.companion.application.domain.PermissionGrantRecord;
import com.luxera.companion.application.domain.SubscriptionRecord;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.ManifestRegistry;
import com.luxera.companion.application.principal.PrincipalResolvers;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.PermissionGrantRepository;
import com.luxera.companion.application.session.ApplicationSessionService;
import com.luxera.companion.application.session.InstallationService;
import com.luxera.companion.application.session.SubscriptionService;
import com.luxera.companion.contracts.application.SubscriptionRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * LAP v1: 归属链上的三件事 —— <b>安装 / 会话 / 订阅</b>。
 *
 * <p>它们是三个不同的声明, 混在一个接口里会让每一件都说不清:
 * <ul>
 *   <li><b>安装</b>说"这个应用我可以用了"(Principal × Application, 长期);</li>
 *   <li><b>会话</b>说"这一次是这一场"(Application × Principal, 有始有终);</li>
 *   <li><b>订阅</b>说"这一类资源上的这一类事件我要"(会话之内, 可选)。</li>
 * </ul>
 *
 * <p>安装顺带开会话, 因为调用方拿到安装之后的下一步总是要一个会话, 而多一次往返只是多一次
 * 出错的机会。但会话不是安装的一部分 —— 它可以有多个, 也可以被单独结束。
 */
@RestController
@RequestMapping("/api/v1")
public class LapInstallationController {

    private final InstallationService installations;
    private final ApplicationSessionService sessions;
    private final SubscriptionService subscriptions;
    private final ManifestRegistry manifests;
    private final PermissionGrantRepository grants;
    private final PrincipalResolvers principals;

    public LapInstallationController(InstallationService installations,
                                     ApplicationSessionService sessions,
                                     SubscriptionService subscriptions,
                                     ManifestRegistry manifests,
                                     PermissionGrantRepository grants,
                                     PrincipalResolvers principals) {
        this.installations = installations;
        this.sessions = sessions;
        this.subscriptions = subscriptions;
        this.manifests = manifests;
        this.grants = grants;
        this.principals = principals;
    }

    // ─────────────────────────── 安装 ───────────────────────────

    /** 请求体: {@code {"capabilities":["game.play"]}}; 省略即"这个应用声明的全部能力"。 */
    public record InstallRequest(List<String> capabilities) {}

    /**
     * 安装结果。{@code sessionId} 顺带给出 —— 它是后续所有动作的 {@code target} 里的那一段。
     */
    public record InstallResponse(String installationId,
                                  String sessionId,
                                  String applicationId,
                                  String version,
                                  String principalType,
                                  String principalId,
                                  List<String> capabilities) {}

    @PostMapping("/applications/{applicationId}/install")
    public InstallResponse install(@PathVariable String applicationId,
                                   @RequestHeader(value = "Authorization", required = false) String authorization,
                                   @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
                                   @RequestBody(required = false) InstallRequest request) {
        ResolvedPrincipal principal = principal(authorization, correlationId);
        InstallationRecord installation = installations.install(
                applicationId, principal, request == null ? null : request.capabilities());
        ApplicationSessionRecord session = sessions.open(applicationId, principal);
        // version 取已发布 manifest 的那一版 —— 安装钉住的正是它, 于是响应对得上会话里的 versionId。
        String version = manifests.published(applicationId)
                .map(ApplicationManifest::version).orElse(null);
        return new InstallResponse(
                installation.getId(),
                session.getId(),
                applicationId,
                version,
                installation.getPrincipalType(),
                installation.getPrincipalId(),
                requestedCapabilities(installation));
    }

    /** 回显这次真正授了哪些能力 —— 省略请求体时的默认值不在控制器里猜, 从授权表读回来。 */
    private List<String> requestedCapabilities(InstallationRecord installation) {
        return grants.findByInstallationId(installation.getId()).stream()
                .filter(g -> g.getActionId() == null && g.getCapabilityId() != null)
                .map(PermissionGrantRecord::getCapabilityId)
                .distinct()
                .toList();
    }

    @DeleteMapping("/applications/{applicationId}/install")
    public ResponseEntity<Void> uninstall(@PathVariable String applicationId,
                                          @RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        installations.uninstall(applicationId, principal(authorization, correlationId));
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────── 会话 ───────────────────────────

    public record SessionRequest(String applicationId) {}

    public record SessionResponse(String sessionId,
                                  String applicationId,
                                  String versionId,
                                  String installationId,
                                  String principalType,
                                  String principalId,
                                  String companionId,
                                  String userId,
                                  String status) {

        static SessionResponse of(ApplicationSessionRecord row) {
            return new SessionResponse(row.getId(), row.getApplicationId(), row.getVersionId(),
                    row.getInstallationId(), row.getPrincipalType(), row.getPrincipalId(),
                    row.getCompanionId(), row.getUserId(), row.getStatus());
        }
    }

    @PostMapping("/sessions")
    public SessionResponse openSession(@RequestBody SessionRequest request,
                                       @RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return SessionResponse.of(
                sessions.open(request.applicationId(), principal(authorization, correlationId)));
    }

    /** 当前 principal 的活跃会话; 给 {@code companionId} 就只列这个数字人的。 */
    @GetMapping("/sessions")
    public List<SessionResponse> sessions(@RequestParam(required = false) String companionId) {
        List<ApplicationSessionRecord> rows = companionId == null || companionId.isBlank()
                ? List.of()
                : sessions.ofCompanion(companionId);
        return rows.stream().map(SessionResponse::of).toList();
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> endSession(@PathVariable String sessionId) {
        sessions.end(sessionId);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────── 订阅 ───────────────────────────

    public record SubscribeRequest(String sessionId,
                                   String resourceUriPattern,
                                   List<String> eventTypes,
                                   String deliveryMode,
                                   Instant expiresAt) {}

    public record SubscriptionResponse(String subscriptionId,
                                       String sessionId,
                                       String resourceUriPattern,
                                       List<String> eventTypes,
                                       String deliveryMode,
                                       String status) {

        static SubscriptionResponse of(SubscriptionRecord row) {
            return new SubscriptionResponse(row.getId(), row.getSessionId(),
                    row.getResourceUriPattern(),
                    row.getEventTypes() == null || row.getEventTypes().isBlank()
                            ? List.of() : List.of(row.getEventTypes().split(",")),
                    row.getDeliveryMode(), row.getStatus());
        }
    }

    @PostMapping("/subscriptions")
    public SubscriptionResponse subscribe(@RequestBody SubscribeRequest request,
                                          @RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        ResolvedPrincipal principal = principal(authorization, correlationId);
        SubscriptionRecord row = subscriptions.subscribe(principal, request.sessionId(),
                new SubscriptionRequest(request.resourceUriPattern(), request.eventTypes(),
                        request.deliveryMode(), request.expiresAt()));
        return SubscriptionResponse.of(row);
    }

    @GetMapping("/subscriptions")
    public List<SubscriptionResponse> subscriptions(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return subscriptions.of(principal(authorization, correlationId)).stream()
                .map(SubscriptionResponse::of).toList();
    }

    @DeleteMapping("/subscriptions/{subscriptionId}")
    public ResponseEntity<Void> revoke(@PathVariable String subscriptionId,
                                       @RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        subscriptions.revoke(subscriptionId, principal(authorization, correlationId));
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private ResolvedPrincipal principal(String authorization, String correlationId) {
        return principals.resolveHeader(authorization,
                correlationId == null || correlationId.isBlank()
                        ? UUID.randomUUID().toString()
                        : correlationId);
    }
}
