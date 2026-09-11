package com.luxera.companion.application.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.UriTemplate;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ApplicationEvent;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.ResourceView;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 处理器看得见的全部世界。
 *
 * <p>四件东西: <b>谁</b>({@code principal})、<b>要做什么</b>({@code spec} + {@code input})、
 * <b>对哪个资源</b>({@code target})、<b>能改什么</b>({@link ResourceAccess})。
 * 再多一件都不给 —— 处理器看不见数据库、看不见别的应用、看不见 HTTP。
 *
 * <p>事件走 {@link #emit}: 处理过程中收集, 由<em>网关</em>在事务提交之后投递。处理器
 * 自己发不出去, 也就不会出现"事务回滚了事件却已送达"这种最难查的 bug。
 */
public final class ActionHandlerContext {

    private final String applicationId;
    private final String version;
    private final ApplicationManifest manifest;
    private final ApplicationManifest.ActionDecl spec;
    private final ActionRequest request;
    private final InvocationContext principal;
    private final ResourceAccess resources;
    private final ObjectMapper objectMapper;
    private final List<ApplicationEvent> emitted = new ArrayList<>();

    public ActionHandlerContext(String applicationId,
                                String version,
                                ApplicationManifest manifest,
                                ApplicationManifest.ActionDecl spec,
                                ActionRequest request,
                                InvocationContext principal,
                                ResourceAccess resources,
                                ObjectMapper objectMapper) {
        this.applicationId = applicationId;
        this.version = version;
        this.manifest = manifest;
        this.spec = spec;
        this.request = request;
        this.principal = principal;
        this.resources = resources;
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────── 身份 ───────────────────────────

    public String applicationId() {
        return applicationId;
    }

    public String version() {
        return version;
    }

    public ApplicationManifest manifest() {
        return manifest;
    }

    public ApplicationManifest.ActionDecl spec() {
        return spec;
    }

    public String actionId() {
        return spec.id();
    }

    public InvocationContext principal() {
        return principal;
    }

    public String principalId() {
        return principal.principalId();
    }

    public String companionId() {
        return principal.companionId();
    }

    public String userId() {
        return principal.userId();
    }

    public String correlationId() {
        return principal.correlationId();
    }

    // ─────────────────────────── 输入 ───────────────────────────

    /** 目标资源 URI。 */
    public String target() {
        return request.target();
    }

    public JsonNode input() {
        JsonNode input = request.input();
        return input == null ? objectMapper.createObjectNode() : input;
    }

    public String inputText(String field) {
        JsonNode v = input().path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText(null);
    }

    public Integer inputInt(String field) {
        JsonNode v = input().path(field);
        return v.isNumber() ? v.asInt() : null;
    }

    /** 调用方声明的期望资源版本; {@code null} 表示"以我读到的为准"。 */
    public Long expectedVersion() {
        return request.expectedResourceVersion();
    }

    // ─────────────────────────── 资源 ───────────────────────────

    /** 目标资源的当前状态; 不存在返回 {@code null}。 */
    public ResourceView currentResource() {
        return resources.read(target());
    }

    /**
     * 按调用方给的 {@code expectedResourceVersion} 写目标资源。
     * 版本不匹配时抛 {@code StateConflictException}, 由网关翻成干净的 409。
     */
    public ResourceView write(JsonNode state) {
        return resources.write(target(), resourceType(), state, expectedVersion());
    }

    /** 本应用声明的资源类型; manifest 里没写就退回应用 id。 */
    public String resourceType() {
        return manifest.resources().stream()
                .filter(r -> matches(r.uriTemplate(), target()))
                .map(ApplicationManifest.ResourceDecl::type)
                .findFirst()
                .orElse(applicationId);
    }

    // ─────────────────────────── 事件 ───────────────────────────

    /** 记一条待投递的应用事件。真正的投递发生在事务提交之后。 */
    public void emit(String type, JsonNode data) {
        emitted.add(new ApplicationEvent(
                mintEventId(type, data), type, applicationId, target(), Instant.now(), data));
    }

    public void emit(ApplicationEvent event) {
        if (event != null) emitted.add(event);
    }

    public List<ApplicationEvent> emittedEvents() {
        return Collections.unmodifiableList(emitted);
    }

    /**
     * 按 manifest 的 {@code events[].idTemplate} 铸造确定性事件 id。
     * 模板里可用的占位符: {@code {uri}} 与 {@code {<data 字段名>}}。
     * 没声明模板时退回 {@code uri#type} —— 仍然确定性, 绝不退回随机 UUID。
     */
    private String mintEventId(String type, JsonNode data) {
        String template = manifest.events().stream()
                .filter(e -> e.type().equals(type))
                .map(ApplicationManifest.EventDecl::idTemplate)
                .filter(t -> t != null && !t.isBlank())
                .findFirst()
                .orElse(null);
        if (template == null) return target() + "#" + type;
        String out = template.replace("{uri}", target());
        if (data != null && data.isObject()) {
            for (var it = data.fields(); it.hasNext(); ) {
                var field = it.next();
                out = out.replace("{" + field.getKey() + "}", field.getValue().asText(""));
            }
        }
        return out;
    }

    /** manifest 的 {@code uriTemplate} 匹配 —— 见 {@link UriTemplate} 的类注释。 */
    static boolean matches(String template, String uri) {
        return UriTemplate.matches(template, uri);
    }
}
