package com.luxera.companion.application.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.application.audit.ActionAuditRecorder;
import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.application.domain.InstallationRecord;
import com.luxera.companion.application.event.LapEventPublisher;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.ManifestRegistry;
import com.luxera.companion.application.permission.PermissionDecision;
import com.luxera.companion.application.permission.PermissionEvaluator;
import com.luxera.companion.application.principal.PrincipalResolver;
import com.luxera.companion.application.principal.PrincipalResolvers;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.repository.InstallationRepository;
import com.luxera.companion.application.resource.ResourceStore;
import com.luxera.companion.application.resource.StateConflictException;
import com.luxera.companion.application.session.ApplicationSessionService;
import com.luxera.companion.application.session.InstallationService;
import com.luxera.companion.application.session.SessionException;
import com.luxera.companion.contracts.application.ActionError;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.application.CapabilityView;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.ResourceView;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * LAP v1: <b>真人、Agent、MCP 客户端共用的唯一入口。</b>
 *
 * <p>这个类里没有"如果调用方是 Agent 就……"的任何一个分支。差异全部落在
 * {@link ResolvedPrincipal} 上, 而它只影响两件事: 权限判定用谁的安装与授权、
 * 幂等键的作用域是谁。除此之外, 一条路径。
 *
 * <p>一次执行经过七道, 顺序是刻意的:
 *
 * <pre>
 *   1. 动作解析     (actionId, target) → 哪个应用的哪个动作; 多个候选且 target 消歧不出唯一 → 拒绝
 *   2. handler 存在 启动时已校验过, 这里是纵深防御
 *   3. 会话解析     显式 &gt; 已存在资源行 &gt; URI 模板里的 sessionId 段
 *   4. 归属校验     会话必须属于这个应用、这个 principal(四条不变量)
 *   5. 权限         Principal × Installation grant × Capability × Action × Risk
 *   6. 幂等         WRITE/EXECUTE 才要 key; READ 从不记录
 *   7. 执行         handler + 资源写入 + 终态回填, 同一个事务
 * </pre>
 *
 * <p><b>第 7 步"同一个事务"是整套崩溃恢复推理的地基。</b>业务写入与 {@code action_invocation}
 * 的终态一起提交, 于是"还停在 IN_PROGRESS"就等价于"业务没发生" —— 重试安全, 回收器也能有把握
 * 地下结论。拆成两个事务的话, 这句话立刻不成立。
 *
 * <p>第 5 步在第 6 步之前: 无权的请求不该占用一个幂等键。先占键再判权限的话, 一个被拒的请求
 * 会留下一条终态记录, 之后真正有权的调用带着同一个 key 来会被"重放"成拒绝。
 */
@Slf4j
@Service
public class ActionGateway implements ApplicationRuntimePort {

    private final ManifestRegistry manifests;
    private final ActionHandlerRegistry handlers;
    private final PendingActionRegistry pendingActions;
    private final ActionResolver resolver;
    private final ResourceStore resources;
    private final PermissionEvaluator permissions;
    private final IdempotencyService idempotency;
    private final ApplicationSessionService sessions;
    private final InstallationService installationService;
    private final InstallationRepository installations;
    private final LapEventPublisher events;
    private final ActionAuditRecorder audit;
    private final PrincipalResolvers principals;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;

    public ActionGateway(ManifestRegistry manifests,
                         ActionHandlerRegistry handlers,
                         PendingActionRegistry pendingActions,
                         ActionResolver resolver,
                         ResourceStore resources,
                         PermissionEvaluator permissions,
                         IdempotencyService idempotency,
                         ApplicationSessionService sessions,
                         InstallationService installationService,
                         InstallationRepository installations,
                         LapEventPublisher events,
                         ActionAuditRecorder audit,
                         PrincipalResolvers principals,
                         ObjectMapper objectMapper,
                         PlatformTransactionManager transactionManager) {
        this.manifests = manifests;
        this.handlers = handlers;
        this.pendingActions = pendingActions;
        this.resolver = resolver;
        this.resources = resources;
        this.permissions = permissions;
        this.idempotency = idempotency;
        this.sessions = sessions;
        this.installationService = installationService;
        this.installations = installations;
        this.events = events;
        this.audit = audit;
        this.principals = principals;
        this.objectMapper = objectMapper;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** 一次执行的结果: 响应, 以及它是不是幂等重放(调用方据此加 {@code Idempotent-Replay} 头)。 */
    public record ActionExecution(ActionResponse response, boolean replayed) {

        public static ActionExecution fresh(ActionResponse response) {
            return new ActionExecution(response, false);
        }
    }

    // ═══════════════════════════ 发现 ═══════════════════════════

    @Override
    public List<CapabilityView> capabilities() {
        return manifests.capabilityCatalogue().values().stream()
                .map(c -> new CapabilityView(c.id(), c.title(), c.description(), c.category()))
                .toList();
    }

    @Override
    public List<ApplicationView> applicationsFor(String capabilityId) {
        return manifests.byCapability(capabilityId).stream().map(ActionGateway::toView).toList();
    }

    /** 全部已发布应用 —— 真人 UI 的"应用商店"页用。 */
    public List<ApplicationView> applications() {
        return manifests.applications().stream().map(ActionGateway::toView).toList();
    }

    @Override
    public List<ActionSpec> actionsOf(String applicationId) {
        return manifests.published(applicationId)
                .map(m -> m.actions().stream().map(a -> toSpec(m, a)).toList())
                .orElse(List.of());
    }

    @Override
    public Optional<ResourceView> read(String resourceUri) {
        return resources.find(resourceUri).map(v -> withHint(v, resourceUri));
    }

    @Override
    public List<ActionSpec> pendingActions(String resourceUri, InvocationContext ctx) {
        if (!StringUtils.hasText(resourceUri) || ctx == null) {
            return List.of();
        }
        ApplicationManifest manifest = resolver.ownerOf(resourceUri).orElse(null);
        if (manifest == null) {
            return List.of();
        }
        // 没装这个应用的 principal 不该"想做点什么" —— 空列表, 不是错误。
        Optional<InstallationRecord> installation = installations
                .findByApplicationIdAndPrincipalTypeAndPrincipalId(
                        manifest.applicationId(),
                        ctx.principalType() == null ? null : ctx.principalType().name(),
                        ctx.principalId());
        if (installation.isEmpty() || !installation.get().active()) {
            return List.of();
        }
        PendingActionProvider provider = pendingActions
                .find(manifest.applicationId(), manifest.version()).orElse(null);
        if (provider == null) {
            return List.of();
        }
        ResourceView resource = read(resourceUri).orElse(null);
        List<String> ids = provider.pendingActionIds(resource, ctx);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<ActionSpec> out = new ArrayList<>();
        for (String actionId : ids) {
            manifest.action(actionId).ifPresent(action -> out.add(toSpec(manifest, action)));
        }
        return out;
    }

    // ═══════════════════════════ 执行 ═══════════════════════════

    /**
     * 进程内的"保证装过" —— 幂等, 走的是和 HTTP 安装<em>同一段</em>代码。
     *
     * <p>刻意不做成"没装就静默跳过": 装不上(应用没发布版本 / 身份不合法)应当让调用方知道,
     * 它才好决定是降级还是报错。静默跳过会让"提醒功能不工作"变成一个需要翻日志才能定位的现象。
     */
    @Override
    public void ensureInstalled(String applicationId, InvocationContext ctx) {
        ResolvedPrincipal principal = principals.resolveInternal(ctx);
        installationService.install(applicationId, principal, null);
    }

    /** {@link ApplicationRuntimePort} 的进程内形态: 身份来自 {@link InvocationContext}。 */
    @Override
    public ActionResponse execute(ActionRequest request, InvocationContext ctx) {
        ResolvedPrincipal principal;
        try {
            principal = principals.resolveInternal(ctx);
        } catch (PrincipalResolver.PrincipalException e) {
            return ActionResponse.failure(ActionStatus.DENIED, e.code(), e.getMessage());
        }
        if (request == null || !StringUtils.hasText(request.action())) {
            return ActionResponse.failure(ActionStatus.INVALID_ARGUMENT,
                    "INVALID_ARGUMENT", "缺少 action");
        }
        return execute(request, principal, deriveIdempotencyKey(request, principal)).response();
    }

    /**
     * 传输层(REST / MCP)用的入口: 幂等键来自请求头, 不是派生出来的。
     *
     * <p>两条路径必须分开, 不能合成"有头就用头、没有就派生": 一个没带
     * {@code Idempotency-Key} 的 HTTP 写请求是<em>客户端 bug</em>, 应当 400 报错;
     * 而进程内调用确实没有头可带, 派生一个确定性键是正确行为。合并的话, 前者会被静默
     * 兜底成一个派生键, 于是"忘了带幂等键"永远暴露不出来。
     */
    public ActionExecution execute(ActionRequest request, ResolvedPrincipal principal, String idempotencyKey) {
        if (request == null || !StringUtils.hasText(request.action())) {
            return ActionExecution.fresh(reject(null, principal, null, ActionStatus.INVALID_ARGUMENT,
                    "INVALID_ARGUMENT", "缺少 action"));
        }
        if (!StringUtils.hasText(request.target())) {
            return ActionExecution.fresh(reject(request.action(), principal, null,
                    ActionStatus.INVALID_ARGUMENT, "TARGET_REQUIRED", "缺少 target"));
        }

        ActionResolution resolution;
        try {
            resolution = resolver.resolve(request.action(), request.target()).orElse(null);
        } catch (AmbiguousActionException e) {
            return ActionExecution.fresh(reject(request.action(), principal, request.target(),
                    ActionStatus.INVALID_ARGUMENT, "AMBIGUOUS_ACTION",
                    "动作 " + e.actionId() + " 被多个应用声明 " + e.candidateApplicationIds()
                            + ", 请给出能消歧的 target"));
        }
        if (resolution == null) {
            return ActionExecution.fresh(reject(request.action(), principal, request.target(),
                    ActionStatus.NOT_FOUND, "ACTION_NOT_FOUND",
                    "没有已发布的应用声明动作 " + request.action()));
        }

        ActionHandler handler = handlers.find(resolution.handlerKey()).orElse(null);
        if (handler == null) {
            // 启动时 ManifestRegistrar 已经校验过一遍; 走到这里说明注册表被中途动过。
            return ActionExecution.fresh(reject(request.action(), principal, request.target(),
                    ActionStatus.FAILED, "ACTION_HANDLER_MISSING",
                    "动作没有处理器: " + resolution.handlerKey()));
        }

        String sessionId;
        try {
            sessionId = resolveSessionId(request.target(), resolution.manifest(), principal);
        } catch (SessionException e) {
            return ActionExecution.fresh(reject(request.action(), principal, request.target(),
                    e.status(), e.code(), e.getMessage()));
        }

        PermissionDecision decision = permissions.evaluate(
                resolution.manifest(), resolution.spec(), principal.type(), principal.principalId());
        ActionAuditRecorder.AuditEntry entry = auditEntry(request, resolution, principal)
                .withPermission(decision.auditLabel());
        if (!decision.allowed()) {
            audit.record(entry.withExecution(decision.confirmationRequired()
                            ? ActionStatus.REQUIRE_CONFIRMATION.name() : ActionStatus.DENIED.name(),
                    decision.message()));
            if (decision.confirmationRequired()) {
                return ActionExecution.fresh(ActionResponse.failure(
                        ActionStatus.REQUIRE_CONFIRMATION, decision.code(), decision.message()));
            }
            return ActionExecution.fresh(ActionResponse.failure(
                    ActionStatus.DENIED, decision.code(), decision.message()));
        }

        boolean needsKey = resolution.spec().requiresIdempotencyKey();
        if (needsKey && !StringUtils.hasText(idempotencyKey)) {
            audit.record(entry.withExecution(ActionStatus.IDEMPOTENCY_KEY_REQUIRED.name(),
                    "WRITE/EXECUTE 动作必须带 Idempotency-Key"));
            return ActionExecution.fresh(ActionResponse.failure(ActionStatus.IDEMPOTENCY_KEY_REQUIRED,
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "动作 " + resolution.actionId() + " 是 "
                            + resolution.spec().permission() + " 级别, 必须带 Idempotency-Key"));
        }

        IdempotencyService.Claim claim = null;
        if (needsKey) {
            String hash = IdempotencyService.requestHash(objectMapper, request);
            claim = idempotency.claim(idempotencyKey, principal, resolution, sessionId, hash);
            if (claim.replay() != null) {
                return new ActionExecution(claim.replay(), true);
            }
            if (claim.conflict() != null) {
                audit.record(entry.withExecution(claim.conflict().status().name(),
                        claim.conflict().error() == null ? null : claim.conflict().error().message()));
                return ActionExecution.fresh(claim.conflict());
            }
            entry = entry.withInvocation(claim.record().getId());
        }

        ActionResponse response = runInTransaction(request, resolution, principal, sessionId, handler, claim);
        audit.record(entry.withExecution(response.status().name(),
                response.error() == null ? null : response.error().message()));
        if (sessionId != null && response.isSuccess()) {
            try {
                sessions.touch(sessionId);
            } catch (Exception e) {
                log.debug("[ActionGateway] 会话活跃时间更新失败 {}: {}", sessionId, e.getMessage());
            }
        }
        return ActionExecution.fresh(response);
    }

    /**
     * 业务执行 + 资源写入 + 终态回填, <b>一个事务</b>。
     *
     * <p>事件投递注册在 {@code afterCommit} 上(见 {@link LapEventPublisher}), 所以"事务回滚了
     * 事件却已送达"这种最难查的 bug 在结构上不可能出现。
     *
     * <p>冲突与异常都<em>不</em>抛出到这个方法之外: 调用方要的是一个响应, 不是一个异常。
     * 但记录方式不同 —— 冲突/异常会让事务回滚, 所以终态只能用<em>独立事务</em>写。
     */
    private ActionResponse runInTransaction(ActionRequest request,
                                            ActionResolution resolution,
                                            ResolvedPrincipal principal,
                                            String sessionId,
                                            ActionHandler handler,
                                            IdempotencyService.Claim claim) {
        String invocationId = claim == null ? null : claim.record().getId();
        try {
            return transaction.execute(status -> {
                ActionHandlerContext ctx = new ActionHandlerContext(
                        resolution.applicationId(),
                        resolution.version(),
                        resolution.manifest(),
                        resolution.spec(),
                        request,
                        principal.toInvocationContext(),
                        resources.scoped(resolution.applicationId(), sessionId),
                        objectMapper);
                ActionOutcome outcome = handler.execute(ctx);
                ActionResponse response = toResponse(outcome, ctx, request.target(), resolution);
                if (invocationId != null) {
                    idempotency.completeInCurrentTransaction(invocationId, response);
                }
                events.publishAfterCommit(resolution.manifest(), ctx.emittedEvents());
                return response;
            });
        } catch (StateConflictException e) {
            if (invocationId != null) {
                idempotency.markFailedInNewTransaction(invocationId, "STATE_CONFLICT", e.getMessage());
            }
            return new ActionResponse(ActionStatus.STATE_CONFLICT, null,
                    conflictView(e), List.of(),
                    ActionError.of("STATE_CONFLICT",
                            "资源 " + e.uri() + " 已被改动(当前版本 " + e.currentVersion()
                                    + "), 请重读后重试"));
        } catch (Exception e) {
            log.warn("[ActionGateway] 动作执行异常 {} target={}: {}",
                    resolution.actionId(), request.target(), e.getMessage(), e);
            if (invocationId != null) {
                idempotency.markFailedInNewTransaction(invocationId, "ACTION_FAILED", e.getMessage());
            }
            return ActionResponse.failure(ActionStatus.FAILED, "ACTION_FAILED",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    // ═══════════════════════════ 内部 ═══════════════════════════

    /**
     * 会话解析, 三选一, 顺序即优先级:
     * <ol>
     *   <li>调用方显式给的 {@code sessionId}(DH 从事件载荷里拿到的就是它);</li>
     *   <li>目标资源行上的 {@code session_id}(除了"创建"之外的所有动作都走这条);</li>
     *   <li>目标 URI 里 {@code {sessionId}} 那一段(创建类动作唯一的来源)。</li>
     * </ol>
     */
    private String resolveSessionId(String target, ApplicationManifest manifest, ResolvedPrincipal principal) {
        String candidate = null;
        if (StringUtils.hasText(principal.sessionId())) {
            candidate = principal.sessionId();
        } else {
            ResourceView existing = resources.find(target).orElse(null);
            if (existing != null && StringUtils.hasText(existing.sessionId())) {
                candidate = existing.sessionId();
            } else {
                candidate = resolver.sessionIdIn(manifest, target).orElse(null);
            }
        }
        if (candidate == null) {
            return null;
        }
        ApplicationSessionRecord session = sessions.requireUsable(candidate, manifest.applicationId());
        return session.getId();
    }

    private ActionResponse toResponse(ActionOutcome outcome,
                                      ActionHandlerContext ctx,
                                      String target,
                                      ActionResolution resolution) {
        ResourceView resource = read(target).orElse(null);
        if (outcome.status() == ActionStatus.SUCCESS) {
            return ActionResponse.success(outcome.result(), resource, ctx.emittedEvents());
        }
        return new ActionResponse(outcome.status(), outcome.result(), resource,
                ctx.emittedEvents(),
                ActionError.of(outcome.errorCode() == null ? "FAILED" : outcome.errorCode(),
                        outcome.errorMessage()));
    }

    private ResourceView conflictView(StateConflictException e) {
        JsonNode state = null;
        if (e.currentStateJson() != null) {
            try {
                state = objectMapper.readTree(e.currentStateJson());
            } catch (Exception ignored) {
                state = null;
            }
        }
        return new ResourceView(e.uri(), e.resourceType(), e.applicationId(), e.sessionId(),
                state, e.currentVersion(), null, null);
    }

    private ActionResponse reject(String actionId, ResolvedPrincipal principal, String target,
                                  ActionStatus status, String code, String message) {
        if (actionId != null) {
            audit.record(ActionAuditRecorder.AuditEntry.of(actionId, null, target)
                    .withPrincipal(principal.typeName(), principal.principalId(),
                            principal.companionId(), principal.userId())
                    .withExecution(status.name(), message)
                    .withCorrelation(principal.correlationId()));
        }
        return ActionResponse.failure(status, code, message);
    }

    private ActionAuditRecorder.AuditEntry auditEntry(ActionRequest request,
                                                      ActionResolution resolution,
                                                      ResolvedPrincipal principal) {
        return ActionAuditRecorder.AuditEntry
                .of(resolution.actionId(), resolution.applicationId(), request.target())
                .withPrincipal(principal.typeName(), principal.principalId(),
                        principal.companionId(), principal.userId())
                .withCorrelation(principal.correlationId());
    }

    /**
     * 进程内调用的确定性幂等键: 同一次因果事件重放 → 同一个键 → 被幂等层短路。
     *
     * <p><b>{@code target} 必须参与其中, 不能只在没有 correlationId 时才用它。</b>一个
     * correlationId 描述的是"这一次因果", 而一次因果完全可能落在两个不同的资源上 —— 数字人
     * 收到一条 {@code reminder.due} 后既可能改这条提醒、也可能同时看它的收件箱。若键里只有
     * correlationId + actionId, 第二次调用会被当成第一次的重放, <em>安静地返回另一个资源的结果</em>。
     * 那种错误不会报错, 只会让调用方拿到一份不属于它的状态。
     *
     * <p>截断时补一个哈希尾巴: 直接 {@code substring} 会把 {@code target}(它恰好在末尾)整个切掉,
     * 于是键又退化成"只有 correlationId + actionId"。
     */
    static String deriveIdempotencyKey(ActionRequest request, ResolvedPrincipal principal) {
        String correlation = principal.correlationId();
        String seed = StringUtils.hasText(correlation)
                ? correlation + "@" + request.target()
                : request.target();
        String key = "inproc:" + principal.typeName() + ":" + principal.principalId()
                + ":" + request.action() + ":" + seed;
        return key.length() <= 200
                ? key
                : key.substring(0, 160) + ":" + Integer.toHexString(key.hashCode());
    }

    private ResourceView withHint(ResourceView view, String uri) {
        return resolver.ownerOf(uri)
                .flatMap(manifest -> manifest.resources().stream()
                        .filter(r -> com.luxera.companion.application.manifest.UriTemplate
                                .matches(r.uriTemplate(), uri))
                        .map(ApplicationManifest.ResourceDecl::agentHint)
                        .findFirst())
                .map(hint -> new ResourceView(view.uri(), view.resourceType(), view.applicationId(),
                        view.sessionId(), view.state(), view.version(), view.updatedAt(), hint))
                .orElse(view);
    }

    private static ApplicationView toView(ApplicationManifest manifest) {
        return new ApplicationView(
                manifest.applicationId(),
                manifest.version(),
                manifest.identity().name(),
                manifest.identity().description(),
                manifest.identity().category(),
                manifest.capabilities().stream()
                        .map(ApplicationManifest.CapabilityDecl::id).toList());
    }

    /** manifest 的声明 + agentHint 一起给出去 —— 这就是 Agent 看到的全部。 */
    static ActionSpec toSpec(ApplicationManifest manifest, ApplicationManifest.ActionDecl action) {
        return new ActionSpec(
                action.id(),
                manifest.applicationId(),
                action.capability(),
                action.description(),
                action.permission(),
                action.risk(),
                action.attention(),
                action.inputSchema(),
                action.agentHint());
    }
}
