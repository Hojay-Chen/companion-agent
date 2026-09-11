package com.luxera.companion.application.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.contracts.application.AttentionPolicy;
import com.luxera.companion.contracts.application.PermissionLevel;
import com.luxera.companion.contracts.application.RiskLevel;

import java.util.List;
import java.util.Optional;

/**
 * LAP v1 §Manifest: 一份应用的完整声明。**它是 LAP 的中枢** —— 平台靠它发现应用、
 * 校验动作、生成 LLM 上下文, 应用靠它把"我是什么、我能做什么、怎么读我"一次说清。
 *
 * <p><b>Manifest 属于 {@code ApplicationVersion}, 不属于 Application。</b> 于是
 * "同一个应用的两个版本"在数据模型里表达得出来, 发布后冻结的是<em>那一版</em>的 manifest,
 * 而 {@code action_invocation} 指向的历史版本永远可解释。
 *
 * <p><b>恰好七个 section:</b> {@code identity} / {@code capabilities} / {@code actions} /
 * {@code resources} / {@code events} / {@code permissions} / {@code runtime}。
 * 解析器对未知 section 直接报错 —— 多一个 section 就意味着多一套平台还不认识的语义,
 * 静默忽略它比报错危险得多。
 *
 * <p><b>这里绝不出现按消费者分的 endpoint</b>({@code agentEndpoint} / {@code humanEndpoint} /
 * {@code mcpEndpoint})。真人和 Agent 走的是<em>同一条</em> {@code actions:execute};
 * REMOTE 应用有且只有一个规范 endpoint, 落在 {@code runtime.remote}。
 */
public record ApplicationManifest(
        Identity identity,
        List<CapabilityDecl> capabilities,
        List<ActionDecl> actions,
        List<ResourceDecl> resources,
        List<EventDecl> events,
        List<PermissionDecl> permissions,
        RuntimeDecl runtime
) {

    /** 解析器保证的七个合法 section 名。 */
    public static final List<String> SECTIONS = List.of(
            "identity", "capabilities", "actions", "resources", "events", "permissions", "runtime");

    public ApplicationManifest {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        actions = actions == null ? List.of() : List.copyOf(actions);
        resources = resources == null ? List.of() : List.copyOf(resources);
        events = events == null ? List.of() : List.copyOf(events);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }

    public String applicationId() {
        return identity == null ? null : identity.id();
    }

    public String version() {
        return identity == null ? null : identity.version();
    }

    public Optional<ActionDecl> action(String actionId) {
        return actions.stream().filter(a -> a.id().equals(actionId)).findFirst();
    }

    public Optional<ResourceDecl> resourceOfType(String resourceType) {
        return resources.stream().filter(r -> r.type().equals(resourceType)).findFirst();
    }

    /** 该动作所属能力是否为 manifest 声明的能力之一。 */
    public boolean declaresCapability(String capabilityId) {
        return capabilities.stream().anyMatch(c -> c.id().equals(capabilityId));
    }

    // ─────────────────────────── 七个 section ───────────────────────────

    /** {@code identity} —— 应用是谁。id 是稳定标识, version 让 manifest 与版本绑定。 */
    public record Identity(String id, String name, String version, String description, String category) {}

    /** {@code capabilities} —— 粗粒度能力({@code game.play})。发现链的第一级。 */
    public record CapabilityDecl(String id, String title, String description, String category) {}

    /**
     * {@code actions} —— 细粒度动作({@code game.make_move})。
     *
     * <p>权限/风险/注意力都挂在动作上: 能力是粗的(给人挑), 动作是细的(给权限和 LLM 用)。
     * {@code agentHint} 是应用作者写给 LLM 的"怎么做", 它取代了原先硬编码在
     * {@code AgentRuntime} 里的走子策略 —— 加第二个游戏不必再改数字人。
     */
    public record ActionDecl(
            String id,
            String capability,
            String title,
            String description,
            PermissionLevel permission,
            RiskLevel risk,
            AttentionPolicy attention,
            JsonNode inputSchema,
            String agentHint
    ) {
        /** WRITE / EXECUTE 必须幂等键; READ 从不记录 invocation。 */
        public boolean requiresIdempotencyKey() {
            return permission == PermissionLevel.WRITE || permission == PermissionLevel.EXECUTE;
        }

        public boolean isRead() {
            return permission == PermissionLevel.READ;
        }
    }

    /**
     * {@code resources} —— 统一读模型。{@code uriTemplate} 决定什么样的 target 属于本应用。
     *
     * <p>{@code backing} 决定状态存在哪: {@link Backing#RESOURCE_STORE} 就是那条 resource
     * 记录本身(棋局); {@link Backing#APP_OWNED} 说明应用自己有真表, 读的时候由
     * {@code ResourceProjector} 投影出来(提醒需要 {@code WHERE due_at <= now()} 这类索引查询,
     * JSON 列做不到)。两种都在这儿声明, 读接口对调用方完全一样。
     */
    public record ResourceDecl(String type, String uriTemplate, Backing backing, String agentHint) {}

    public enum Backing {
        /** 状态就是 {@code resource.state_json}。 */
        RESOURCE_STORE,
        /** 状态在应用自己的表里, 读时投影。 */
        APP_OWNED
    }

    /**
     * {@code events} —— 应用会发出什么事件。
     *
     * <p><b>两级闸门的第一级在类型上</b>: {@code triggersAgent} 说"这类事件<em>可以</em>
     * 唤起数字人", 实例级的 {@code data.agentTrigger} 才说"<em>这一次</em>该不该"。
     * 取与。
     *
     * <p>{@code triggersAgent} 为真时 {@code idTemplate} <b>强制必填</b>: 事件 id 必须是
     * (资源, 类型, 关键参数) 的确定函数。用随机 UUID 的话, 重试会让数字人对着同一步行动两次。
     */
    public record EventDecl(String type, String title, boolean triggersAgent, String idTemplate) {}

    /**
     * {@code permissions} —— 应用声明的授权上限。真正的决策是
     * {@code Principal × Installation grant × Capability × Action × Risk}, 这里给的是
     * "本应用最多允许到什么程度"; 缺一条能力声明就直接拒绝发布, 免得出现"忘了写权限"
     * 被当成"默认放行"。
     */
    public record PermissionDecl(String capability, PermissionLevel level, RiskLevel riskCeiling) {}

    /** {@code runtime} —— 跑在哪。REMOTE 才有 {@code remote}。 */
    public record RuntimeDecl(RuntimeType type, RemoteDecl remote) {
        public static RuntimeDecl nativeRuntime() {
            return new RuntimeDecl(RuntimeType.NATIVE, null);
        }
    }

    /**
     * REMOTE 的唯一规范 endpoint。{@code authRef} 是**名字**(从平台配置里解析),
     * manifest 里永远不出现密钥 —— manifest 会进数据库、进日志、进导出包。
     */
    public record RemoteDecl(String baseUrl, String authRef) {}
}
