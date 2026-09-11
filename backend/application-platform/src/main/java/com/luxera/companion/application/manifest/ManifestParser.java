package com.luxera.companion.application.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.application.AttentionPolicy;
import com.luxera.companion.contracts.application.PermissionLevel;
import com.luxera.companion.contracts.application.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * JSON → {@link ApplicationManifest}。**只做形状**, 不做业务规则 ——
 * "WRITE 必须有 inputSchema""triggersAgent 必须给 idTemplate"这些属于
 * {@link ManifestValidator}, 分开放是为了让"解析不了"和"解析了但不合法"给出不同的错误码。
 *
 * <p>这里唯一一条强规则是 **section 白名单**: 顶层出现七个之外的名字直接
 * {@code UNKNOWN_SECTION}。理由见 {@link ApplicationManifest} 的类注释 ——
 * 静默忽略一个平台还不认识的 section, 等于让作者以为它生效了。
 */
@Component
public class ManifestParser {

    private final ObjectMapper objectMapper;

    public ManifestParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ApplicationManifest parse(String json) {
        if (json == null || json.isBlank()) {
            throw ManifestException.of("MANIFEST_EMPTY", "manifest 为空");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw ManifestException.of("MANIFEST_PARSE_ERROR", "manifest 不是合法 JSON: " + e.getMessage());
        }
        if (!root.isObject()) {
            throw ManifestException.of("MANIFEST_PARSE_ERROR", "manifest 顶层必须是对象");
        }
        rejectUnknownSections(root);
        return new ApplicationManifest(
                identity(root.path("identity")),
                capabilities(root.path("capabilities")),
                actions(root.path("actions")),
                resources(root.path("resources")),
                events(root.path("events")),
                permissions(root.path("permissions")),
                runtime(root.path("runtime")));
    }

    private static void rejectUnknownSections(JsonNode root) {
        List<String> unknown = new ArrayList<>();
        for (Iterator<String> it = root.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            if (!ApplicationManifest.SECTIONS.contains(name)) unknown.add(name);
        }
        if (!unknown.isEmpty()) {
            throw ManifestException.of("UNKNOWN_SECTION",
                    "manifest 含未知 section " + unknown + "; 合法 section 恰好是 " + ApplicationManifest.SECTIONS);
        }
    }

    // ─────────────────────────── 各 section ───────────────────────────

    private static ApplicationManifest.Identity identity(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) return null;
        return new ApplicationManifest.Identity(
                text(n, "id"), text(n, "name"), text(n, "version"),
                text(n, "description"), text(n, "category"));
    }

    private static List<ApplicationManifest.CapabilityDecl> capabilities(JsonNode n) {
        List<ApplicationManifest.CapabilityDecl> out = new ArrayList<>();
        for (JsonNode c : array(n)) {
            out.add(new ApplicationManifest.CapabilityDecl(
                    text(c, "id"), text(c, "title"), text(c, "description"), text(c, "category")));
        }
        return out;
    }

    private static List<ApplicationManifest.ActionDecl> actions(JsonNode n) {
        List<ApplicationManifest.ActionDecl> out = new ArrayList<>();
        for (JsonNode a : array(n)) {
            out.add(new ApplicationManifest.ActionDecl(
                    text(a, "id"),
                    text(a, "capability"),
                    text(a, "title"),
                    text(a, "description"),
                    enumOf(a, "permission", PermissionLevel.class),
                    enumOf(a, "risk", RiskLevel.class),
                    enumOfDefault(a, "attention", AttentionPolicy.class, AttentionPolicy.AWARE),
                    a.hasNonNull("inputSchema") ? a.get("inputSchema") : null,
                    text(a, "agentHint")));
        }
        return out;
    }

    private static List<ApplicationManifest.ResourceDecl> resources(JsonNode n) {
        List<ApplicationManifest.ResourceDecl> out = new ArrayList<>();
        for (JsonNode r : array(n)) {
            out.add(new ApplicationManifest.ResourceDecl(
                    text(r, "type"), text(r, "uriTemplate"),
                    enumOfDefault(r, "backing", ApplicationManifest.Backing.class,
                            ApplicationManifest.Backing.RESOURCE_STORE),
                    text(r, "agentHint")));
        }
        return out;
    }

    private static List<ApplicationManifest.EventDecl> events(JsonNode n) {
        List<ApplicationManifest.EventDecl> out = new ArrayList<>();
        for (JsonNode e : array(n)) {
            out.add(new ApplicationManifest.EventDecl(
                    text(e, "type"), text(e, "title"),
                    e.path("triggersAgent").asBoolean(false),
                    text(e, "idTemplate")));
        }
        return out;
    }

    private static List<ApplicationManifest.PermissionDecl> permissions(JsonNode n) {
        List<ApplicationManifest.PermissionDecl> out = new ArrayList<>();
        for (JsonNode p : array(n)) {
            out.add(new ApplicationManifest.PermissionDecl(
                    text(p, "capability"),
                    enumOf(p, "level", PermissionLevel.class),
                    enumOf(p, "riskCeiling", RiskLevel.class)));
        }
        return out;
    }

    private static ApplicationManifest.RuntimeDecl runtime(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) return null;   // validator 会拒绝(runtime 必填)
        RuntimeType type = enumOf(n, "type", RuntimeType.class);
        JsonNode remote = n.path("remote");
        ApplicationManifest.RemoteDecl remoteDecl = (remote.isMissingNode() || remote.isNull())
                ? null
                : new ApplicationManifest.RemoteDecl(text(remote, "baseUrl"), text(remote, "authRef"));
        return new ApplicationManifest.RuntimeDecl(type, remoteDecl);
    }

    // ─────────────────────────── 小工具 ───────────────────────────

    private static Iterable<JsonNode> array(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) return List.of();
        if (!n.isArray()) {
            throw ManifestException.of("MANIFEST_PARSE_ERROR", "期望数组, 实际是 " + n.getNodeType());
        }
        return n;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText(null);
    }

    private static <E extends Enum<E>> E enumOf(JsonNode n, String field, Class<E> type) {
        String raw = text(n, field);
        if (raw == null || raw.isBlank()) return null;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ManifestException.of("MANIFEST_PARSE_ERROR",
                    field + "=" + raw + " 不是合法的 " + type.getSimpleName());
        }
    }

    private static <E extends Enum<E>> E enumOfDefault(JsonNode n, String field, Class<E> type, E fallback) {
        E v = enumOf(n, field, type);
        return v == null ? fallback : v;
    }
}
