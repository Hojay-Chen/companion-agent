package com.luxera.companion.application.manifest;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * LAP v1 §Manifest validation: 形状之外的<em>业务规则</em>。每一条都对应一次真实会踩的坑。
 *
 * <p>规则清单(错误码即定位):
 * <ul>
 *   <li>{@code MANIFEST_INVALID} —— identity/version/name 缺失, 或 capabilities/actions 为空。
 *       一个没有任何动作的"应用"在平台里毫无意义, 与其让它出现在发现链里, 不如发布失败。</li>
 *   <li>{@code INVALID_APPLICATION_ID} —— id 必须是反向域名形态(至少一个点)。</li>
 *   <li>{@code DUPLICATE_CAPABILITY} / {@code DUPLICATE_ACTION} —— 同名不是"后者覆盖前者",
 *       是作者写错了。</li>
 *   <li>{@code UNKNOWN_CAPABILITY} —— action 声明了一个没在 capabilities 里出现的能力。</li>
 *   <li>{@code PERMISSION_DECL_MISSING} / {@code PERMISSION_DECL_UNKNOWN_CAPABILITY} ——
 *       每个能力恰好一条权限声明。缺了就是"默认放行"的另一种说法, 不能接受。</li>
 *   <li>{@code INPUT_SCHEMA_REQUIRED} —— WRITE/EXECUTE 必须给 inputSchema。没有 schema 的写操作
 *       既没法给 LLM 约束, 也没法在网关入口做校验。</li>
 *   <li>{@code EVENT_ID_TEMPLATE_REQUIRED} —— {@code triggersAgent} 为真却没有 idTemplate。
 *       随机 id 会让数字人侧去重失效, 于是同一步行动被响应两次。</li>
 *   <li>{@code RESOURCE_URI_TEMPLATE_REQUIRED} —— resource 没有 uriTemplate, 网关就没法判断
 *       "这个 target 属于哪个应用"。</li>
 *   <li>{@code RUNTIME_TYPE_NOT_SUPPORTED} —— HOSTED 在本阶段明确不做, 显式报错而不是静默接受。</li>
 *   <li>{@code REMOTE_ENDPOINT_REQUIRED} —— REMOTE 却没有 {@code runtime.remote.baseUrl}。</li>
 * </ul>
 */
@Component
public class ManifestValidator {

    public void validate(ApplicationManifest manifest) {
        if (manifest == null) {
            throw ManifestException.of("MANIFEST_INVALID", "manifest 为空");
        }
        ApplicationManifest.Identity identity = manifest.identity();
        if (identity == null) {
            throw ManifestException.of("MANIFEST_INVALID", "缺少 identity section");
        }
        require(identity.id(), "MANIFEST_INVALID", "identity.id 必填");
        require(identity.name(), "MANIFEST_INVALID", "identity.name 必填");
        require(identity.version(), "MANIFEST_INVALID", "identity.version 必填");
        if (!identity.id().contains(".")) {
            throw ManifestException.of("INVALID_APPLICATION_ID",
                    "identity.id 必须是反向域名形态(如 com.luxera.tictactoe), 实际: " + identity.id());
        }
        if (manifest.capabilities().isEmpty()) {
            throw ManifestException.of("MANIFEST_INVALID", "capabilities 不能为空 —— 发现链的第一级就是能力");
        }
        if (manifest.actions().isEmpty()) {
            throw ManifestException.of("MANIFEST_INVALID", "actions 不能为空 —— 没有动作的应用无法被发现");
        }

        validateCapabilities(manifest);
        validateActions(manifest);
        validateResources(manifest);
        validateEvents(manifest);
        validatePermissions(manifest);
        validateRuntime(manifest);
    }

    private void validateCapabilities(ApplicationManifest m) {
        Set<String> seen = new HashSet<>();
        for (ApplicationManifest.CapabilityDecl c : m.capabilities()) {
            require(c.id(), "MANIFEST_INVALID", "capability.id 必填");
            if (!seen.add(c.id())) {
                throw ManifestException.of("DUPLICATE_CAPABILITY", "能力重复声明: " + c.id());
            }
        }
    }

    private void validateActions(ApplicationManifest m) {
        Set<String> seen = new HashSet<>();
        for (ApplicationManifest.ActionDecl a : m.actions()) {
            require(a.id(), "MANIFEST_INVALID", "action.id 必填");
            if (!seen.add(a.id())) {
                throw ManifestException.of("DUPLICATE_ACTION", "动作重复声明: " + a.id());
            }
            require(a.capability(), "MANIFEST_INVALID", "动作 " + a.id() + " 缺少 capability");
            if (!m.declaresCapability(a.capability())) {
                throw ManifestException.of("UNKNOWN_CAPABILITY",
                        "动作 " + a.id() + " 声明了未定义的能力 " + a.capability());
            }
            if (a.permission() == null) {
                throw ManifestException.of("MANIFEST_INVALID", "动作 " + a.id() + " 缺少 permission");
            }
            if (a.risk() == null) {
                throw ManifestException.of("MANIFEST_INVALID", "动作 " + a.id() + " 缺少 risk");
            }
            if (a.requiresIdempotencyKey()
                    && (a.inputSchema() == null || !a.inputSchema().isObject())) {
                throw ManifestException.of("INPUT_SCHEMA_REQUIRED",
                        a.permission() + " 动作 " + a.id() + " 必须声明对象型 inputSchema");
            }
        }
    }

    private void validateResources(ApplicationManifest m) {
        for (ApplicationManifest.ResourceDecl r : m.resources()) {
            require(r.type(), "MANIFEST_INVALID", "resource.type 必填");
            require(r.uriTemplate(), "RESOURCE_URI_TEMPLATE_REQUIRED",
                    "资源 " + r.type() + " 必须声明 uriTemplate —— 网关靠它判断 target 属于哪个应用");
        }
    }

    private void validateEvents(ApplicationManifest m) {
        Set<String> seen = new HashSet<>();
        for (ApplicationManifest.EventDecl e : m.events()) {
            require(e.type(), "MANIFEST_INVALID", "event.type 必填");
            if (!seen.add(e.type())) {
                throw ManifestException.of("DUPLICATE_EVENT", "事件类型重复声明: " + e.type());
            }
            if (e.triggersAgent() && isBlank(e.idTemplate())) {
                throw ManifestException.of("EVENT_ID_TEMPLATE_REQUIRED",
                        "事件 " + e.type() + " 会唤起数字人, 必须声明 idTemplate —— "
                                + "随机 id 会让数字人侧的去重失效, 同一步行动被响应两次");
            }
        }
    }

    private void validatePermissions(ApplicationManifest m) {
        List<String> declared = new ArrayList<>();
        for (ApplicationManifest.PermissionDecl p : m.permissions()) {
            require(p.capability(), "MANIFEST_INVALID", "permission.capability 必填");
            if (p.level() == null) {
                throw ManifestException.of("MANIFEST_INVALID", "权限声明 " + p.capability() + " 缺少 level");
            }
            if (p.riskCeiling() == null) {
                throw ManifestException.of("MANIFEST_INVALID",
                        "权限声明 " + p.capability() + " 缺少 riskCeiling");
            }
            if (!m.declaresCapability(p.capability())) {
                throw ManifestException.of("PERMISSION_DECL_UNKNOWN_CAPABILITY",
                        "权限声明指向未定义的能力 " + p.capability());
            }
            declared.add(p.capability());
        }
        for (ApplicationManifest.CapabilityDecl c : m.capabilities()) {
            if (!declared.contains(c.id())) {
                throw ManifestException.of("PERMISSION_DECL_MISSING",
                        "能力 " + c.id() + " 没有权限声明 —— 缺声明不等于默认放行");
            }
        }
    }

    private void validateRuntime(ApplicationManifest m) {
        ApplicationManifest.RuntimeDecl runtime = m.runtime();
        if (runtime == null || runtime.type() == null) {
            throw ManifestException.of("MANIFEST_INVALID", "缺少 runtime.type");
        }
        switch (runtime.type()) {
            case HOSTED -> throw ManifestException.of("RUNTIME_TYPE_NOT_SUPPORTED",
                    "runtime.type=HOSTED 在本阶段不支持 —— 平台不替应用跑代码(JVM sandbox / WASM 已明确推迟)");
            case REMOTE -> {
                if (runtime.remote() == null || isBlank(runtime.remote().baseUrl())) {
                    throw ManifestException.of("REMOTE_ENDPOINT_REQUIRED",
                            "runtime.type=REMOTE 必须声明 runtime.remote.baseUrl");
                }
            }
            case NATIVE -> { /* 进程内, 无需额外声明 */ }
        }
    }

    private static void require(String value, String code, String message) {
        if (isBlank(value)) throw ManifestException.of(code, message);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
