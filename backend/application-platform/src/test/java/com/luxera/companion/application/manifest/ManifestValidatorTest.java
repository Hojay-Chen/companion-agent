package com.luxera.companion.application.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * manifest 的七条 section 与它们的业务规则。
 *
 * <p>这些用例看着琐碎, 但每一条都对应一次线上会踩的坑 —— 尤其是
 * {@code EVENT_ID_TEMPLATE_REQUIRED}: 随机事件 id 会让数字人侧去重失效, 于是它对同一步棋
 * 落两次子, 而"看起来一切正常"。把这条规则钉在发布前, 是唯一便宜的时机。
 */
class ManifestValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ManifestParser parser = new ManifestParser(mapper);
    private final ManifestValidator validator = new ManifestValidator();

    // ─────────────────────────── 形状 ───────────────────────────

    @Test
    void validManifestPasses() {
        ApplicationManifest m = validate(manifest("com.luxera.demo", CAP_GAME, ACT_MOVE,
                RES_GAME, "[]", PERM_GAME, RT_NATIVE));
        assertEquals("com.luxera.demo", m.applicationId());
        assertEquals("1.0.0", m.version());
        assertEquals(1, m.actions().size());
        assertTrue(m.action("game.make_move").orElseThrow().requiresIdempotencyKey());
    }

    @Test
    void unknownSectionIsRejected() {
        String json = """
                {"identity":{"id":"com.luxera.demo","name":"T","version":"1.0.0"},
                 "capabilities":%s,"actions":%s,"resources":%s,"events":[],
                 "permissions":%s,"runtime":%s,
                 "agentEndpoint":"/api/v1/agent"}
                """.formatted(CAP_GAME, ACT_MOVE, RES_GAME, PERM_GAME, RT_NATIVE);
        assertCode("UNKNOWN_SECTION", json);
    }

    @Test
    void identityWithoutReverseDnsIdIsRejected() {
        assertCode("INVALID_APPLICATION_ID",
                manifest("tictactoe", CAP_GAME, ACT_MOVE, RES_GAME, "[]", PERM_GAME, RT_NATIVE));
    }

    @Test
    void applicationWithNoActionsIsRejected() {
        assertCode("MANIFEST_INVALID",
                manifest("com.luxera.demo", CAP_GAME, "[]", RES_GAME, "[]", PERM_GAME, RT_NATIVE));
    }

    @Test
    void duplicateActionIsRejected() {
        String two = "[" + ACT_MOVE.substring(1, ACT_MOVE.length() - 1) + ","
                + ACT_MOVE.substring(1, ACT_MOVE.length() - 1) + "]";
        assertCode("DUPLICATE_ACTION",
                manifest("com.luxera.demo", CAP_GAME, two, RES_GAME, "[]", PERM_GAME, RT_NATIVE));
    }

    // ─────────────────────────── 规则 ───────────────────────────

    @Test
    void actionDeclaringAnUndeclaredCapabilityIsRejected() {
        String action = "[{\"id\":\"game.make_move\",\"capability\":\"game.unknown\","
                + "\"permission\":\"WRITE\",\"risk\":\"LOW\",\"inputSchema\":{\"type\":\"object\"}}]";
        assertCode("UNKNOWN_CAPABILITY",
                manifest("com.luxera.demo", CAP_GAME, action, RES_GAME, "[]", PERM_GAME, RT_NATIVE));
    }

    @Test
    void writeActionWithoutInputSchemaIsRejected() {
        String action = "[{\"id\":\"game.make_move\",\"capability\":\"game.play\","
                + "\"permission\":\"WRITE\",\"risk\":\"LOW\"}]";
        assertCode("INPUT_SCHEMA_REQUIRED",
                manifest("com.luxera.demo", CAP_GAME, action, RES_GAME, "[]", PERM_GAME, RT_NATIVE));
    }

    @Test
    void readActionNeedsNoInputSchema() {
        String action = "[{\"id\":\"game.state\",\"capability\":\"game.play\","
                + "\"permission\":\"READ\",\"risk\":\"NONE\"}]";
        assertDoesNotThrow(() -> validate(
                manifest("com.luxera.demo", CAP_GAME, action, RES_GAME, "[]", PERM_GAME, RT_NATIVE)));
    }

    @Test
    void capabilityWithoutPermissionDeclarationIsRejected() {
        assertCode("PERMISSION_DECL_MISSING",
                manifest("com.luxera.demo", CAP_GAME, ACT_MOVE, RES_GAME, "[]", "[]", RT_NATIVE));
    }

    @Test
    void resourceWithoutUriTemplateIsRejected() {
        String res = "[{\"type\":\"game.session\"}]";
        assertCode("RESOURCE_URI_TEMPLATE_REQUIRED",
                manifest("com.luxera.demo", CAP_GAME, ACT_MOVE, res, "[]", PERM_GAME, RT_NATIVE));
    }

    @Test
    void triggersAgentEventWithoutIdTemplateIsRejected() {
        String events = "[{\"type\":\"game.move\",\"triggersAgent\":true}]";
        assertCode("EVENT_ID_TEMPLATE_REQUIRED",
                manifest("com.luxera.demo", CAP_GAME, ACT_MOVE, RES_GAME, events, PERM_GAME, RT_NATIVE));
    }

    @Test
    void triggersAgentEventWithIdTemplateIsAccepted() {
        String events = "[{\"type\":\"game.move\",\"triggersAgent\":true,"
                + "\"idTemplate\":\"{uri}#MOVE-{position}\"}]";
        ApplicationManifest m = validate(
                manifest("com.luxera.demo", CAP_GAME, ACT_MOVE, RES_GAME, events, PERM_GAME, RT_NATIVE));
        assertTrue(m.events().get(0).triggersAgent());
    }

    // ─────────────────────────── runtime ───────────────────────────

    @Test
    void hostedRuntimeIsRejectedWithItsOwnCode() {
        assertCode("RUNTIME_TYPE_NOT_SUPPORTED",
                manifest("com.luxera.demo", CAP_GAME, ACT_MOVE, RES_GAME, "[]", PERM_GAME,
                        "{\"type\":\"HOSTED\"}"));
    }

    @Test
    void remoteRuntimeWithoutEndpointIsRejected() {
        assertCode("REMOTE_ENDPOINT_REQUIRED",
                manifest("com.luxera.demo", CAP_GAME, ACT_MOVE, RES_GAME, "[]", PERM_GAME,
                        "{\"type\":\"REMOTE\"}"));
    }

    @Test
    void remoteRuntimeWithEndpointIsAccepted() {
        assertDoesNotThrow(() -> validate(
                manifest("com.luxera.demo", CAP_GAME, ACT_MOVE, RES_GAME, "[]", PERM_GAME,
                        "{\"type\":\"REMOTE\",\"remote\":{\"baseUrl\":\"https://app.example.com\","
                                + "\"authRef\":\"demo-app\"}}")));
    }

    // ─────────────────────────── 真正发货的那份 ───────────────────────────

    /**
     * 井字棋自己的 manifest 必须能通过校验 —— 否则服务根本起不来
     * ({@code ManifestRegistrar} 在启动时跑同一套规则)。这条用例的价值是:
     * 改 JSON 打错一个字, 在这里就报出来, 而不是等到启动失败。
     */
    @Test
    void theShippedTicTacToeManifestIsValid() throws Exception {
        String json = StreamUtils.copyToString(
                new ClassPathResource("applications/tictactoe/1.0.0/application-manifest.json")
                        .getInputStream(), StandardCharsets.UTF_8);
        ApplicationManifest m = validate(json);
        assertEquals("com.luxera.tictactoe", m.applicationId());
        assertTrue(m.action("game.make_move").isPresent(), "manifest 必须声明 game.make_move");
        assertTrue(m.declaresCapability("game.play"));
        assertTrue(m.runtime().type() == RuntimeType.NATIVE);
        assertTrue(m.resources().get(0).backing() == ApplicationManifest.Backing.RESOURCE_STORE);
        // 策略文本必须住在 manifest 里, 不能回流 DH —— R7 的终局验收靠这一条
        assertNotNull(m.action("game.make_move").orElseThrow().agentHint(),
                "走子策略属于 manifest 的 agentHint, 不属于数字人");
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static final String CAP_GAME =
            "[{\"id\":\"game.play\",\"title\":\"对弈\",\"description\":\"回合制对弈\",\"category\":\"game\"}]";

    private static final String ACT_MOVE =
            "[{\"id\":\"game.make_move\",\"capability\":\"game.play\",\"title\":\"落子\","
                    + "\"description\":\"放一颗子\",\"permission\":\"WRITE\",\"risk\":\"LOW\","
                    + "\"inputSchema\":{\"type\":\"object\"}}]";

    private static final String RES_GAME =
            "[{\"type\":\"game.session\",\"uriTemplate\":\"game://session/{sessionId}\"}]";

    private static final String PERM_GAME =
            "[{\"capability\":\"game.play\",\"level\":\"WRITE\",\"riskCeiling\":\"LOW\"}]";

    private static final String RT_NATIVE = "{\"type\":\"NATIVE\"}";

    private static String manifest(String id, String capabilities, String actions,
                                   String resources, String events, String permissions, String runtime) {
        return """
                {"identity":{"id":"%s","name":"演示应用","version":"1.0.0",
                            "description":"用例","category":"game"},
                 "capabilities":%s,
                 "actions":%s,
                 "resources":%s,
                 "events":%s,
                 "permissions":%s,
                 "runtime":%s}
                """.formatted(id, capabilities, actions, resources, events, permissions, runtime);
    }

    private ApplicationManifest validate(String json) {
        ApplicationManifest m = parser.parse(json);
        validator.validate(m);
        return m;
    }

    private void assertCode(String expectedCode, String json) {
        ManifestException e = assertThrows(ManifestException.class, () -> validate(json));
        assertEquals(expectedCode, e.code(), "错误码应能定位问题, 实际消息: " + e.getMessage());
    }
}
