package com.luxera.companion.application.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.application.StubPrincipalTokenReader;
import com.luxera.companion.application.repository.ActionInvocationRepository;
import com.luxera.companion.contracts.application.PrincipalType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 面 —— {@code /api/v1} 之下真人与 Agent 走的是<em>同一条</em>路。
 *
 * <p>单测里 {@link com.luxera.companion.application.action.ActionGateway} 是直接被调用的,
 * 于是有一整类问题它照不到: 幂等键到底有没有从请求头读进来、重放有没有真的回一个
 * {@code Idempotent-Replay} 头、HTTP 状态码与 {@code ActionStatus} 的映射在真实响应里对不对、
 * 冒号路由({@code /actions:execute})在 Spring MVC 里到底通不通。这些只有过一遍 MockMvc 才知道。
 *
 * <p>身份走 {@link StubPrincipalTokenReader} —— 测试模块里没有 kernel, 令牌读取被换成一个能造出
 * 任意 principal 的替身。这也让"换个 principal 再调一次"变得和换一个请求头一样便宜。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class LapWebSurfaceTest {

    private static final String APP_ID = "com.luxera.tictactoe";
    private static final String CAPABILITIES = "/api/v1/capabilities";
    private static final String EXECUTE = "/api/v1/actions:execute";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ActionInvocationRepository invocations;

    // ─────────────────────────── 发现 ───────────────────────────

    /** 发现链的三级都过 HTTP 走一遍: 能力 → 候选应用 → 动作。 */
    @Test
    void theDiscoveryChainIsAvailableOverHttp() throws Exception {
        mvc.perform(get(CAPABILITIES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.capabilityId=='game.play')]").exists());

        mvc.perform(get("/api/v1/capabilities/game.play/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.applicationId=='" + APP_ID + "')]").exists());

        mvc.perform(get("/api/v1/applications/" + APP_ID + "/actions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.actionId=='game.make_move')]").exists())
                // agentHint 一路带到传输层 —— 它是 LLM 在这条链上唯一能读到的"怎么做"
                .andExpect(jsonPath("$[?(@.actionId=='game.make_move')].agentHint").exists());
    }

    // ─────────────────────────── 安装 / 会话 / 订阅 ───────────────────────────

    @Test
    void installingOpensASessionAndTheResponseCarriesBothIds() throws Exception {
        String alice = principalId();

        String body = mvc.perform(post("/api/v1/applications/" + APP_ID + "/install")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"capabilities\":[\"game.play\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(APP_ID))
                .andExpect(jsonPath("$.principalType").value("HUMAN"))
                .andExpect(jsonPath("$.principalId").value(alice))
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.capabilities[0]").value("game.play"))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        assertEquals(36, json.path("installationId").asText().length());
        assertEquals(36, json.path("sessionId").asText().length(),
                "安装顺带开会话, 调用方下一步就能直接下动作");
    }

    /** 省略请求体 = 这个应用声明的全部能力, 而不是"什么都没授权"。 */
    @Test
    void installingWithoutBodyGrantsEverythingTheApplicationDeclares() throws Exception {
        String body = mvc.perform(post("/api/v1/applications/" + APP_ID + "/install")
                        .header("Authorization", bearer(principalId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals("game.play", objectMapper.readTree(body).path("capabilities").get(0).asText());
    }

    @Test
    void sessionsCanBeOpenedListedAndEnded() throws Exception {
        String companionId = "dh-" + UUID.randomUUID();
        install(companionId, PrincipalType.AGENT);

        String sessionId = openSession(companionId, PrincipalType.AGENT);

        mvc.perform(get("/api/v1/sessions").param("companionId", companionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sessionId=='" + sessionId + "')]").exists())
                .andExpect(jsonPath("$[0].companionId").value(companionId));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/sessions/" + sessionId))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/actions:execute")
                        .header("Authorization", bearer(companionId, PrincipalType.AGENT))
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody("game.create", "game://session/" + sessionId, "{}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SESSION_ENDED"));
    }

    @Test
    void subscriptionsCanBeCreatedListedAndRevoked() throws Exception {
        String alice = principalId();
        install(alice, PrincipalType.HUMAN);
        String sessionId = openSession(alice, PrincipalType.HUMAN);

        String body = mvc.perform(post("/api/v1/subscriptions")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"%s","resourceUriPattern":"game://session/%s",
                                 "eventTypes":["game.move","game.finish"],"deliveryMode":"SINK"}"""
                                .formatted(sessionId, sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.deliveryMode").value("SINK"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();

        String subscriptionId = objectMapper.readTree(body).path("subscriptionId").asText();

        mvc.perform(get("/api/v1/subscriptions").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.subscriptionId=='" + subscriptionId + "')]").exists());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/subscriptions/" + subscriptionId)
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isNoContent());
    }

    // ─────────────────────────── 动作 ───────────────────────────

    @Test
    void aWholeGameCanBePlayedOverHttp() throws Exception {
        String alice = principalId();
        install(alice, PrincipalType.HUMAN);
        String sessionId = openSession(alice, PrincipalType.HUMAN);
        String uri = "game://session/" + sessionId;

        mvc.perform(post(EXECUTE)
                        .header("Authorization", bearer(alice))
                        .header("Idempotency-Key", "create-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody("game.create", uri, "{}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.resource.uri").value(uri))
                .andExpect(jsonPath("$.resource.version").value(1));

        mvc.perform(post(EXECUTE)
                        .header("Authorization", bearer(alice))
                        .header("Idempotency-Key", "move-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody("game.make_move", uri, "{\"position\":4}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource.state.board[4]").value("X"));

        // 读动作没有键也能走, 而且读到的必须是<b>刚下完</b>的棋盘
        mvc.perform(get("/api/v1/resources").param("uri", uri))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state.board[4]").value("X"))
                .andExpect(jsonPath("$[0].version").value(2));
    }

    /** Canonical 与 Alias 必须是同一条路 —— 不是"也能通", 是行为逐字节相同。 */
    @Test
    void theAliasRouteBehavesExactlyLikeTheCanonicalOne() throws Exception {
        String body = executeBody("game.teleport", "game://session/" + UUID.randomUUID(), "{}");

        String canonical = mvc.perform(post(EXECUTE)
                        .header("Authorization", bearer(principalId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACTION_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();

        String alias = mvc.perform(post("/api/v1/actions/execute")
                        .header("Authorization", bearer(principalId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertEquals(objectMapper.readTree(canonical).path("error").path("code"),
                objectMapper.readTree(alias).path("error").path("code"),
                "两条路由必须映射到同一个方法, 而不是各写一份");
    }

    // ─────────────────────────── 幂等 ───────────────────────────

    @Test
    void replayingWithTheSameKeyReturnsTheSameBytesAndReplayHeader() throws Exception {
        String alice = principalId();
        install(alice, PrincipalType.HUMAN);
        String uri = "game://session/" + openSession(alice, PrincipalType.HUMAN);
        String key = "create-" + UUID.randomUUID();
        String raw = executeBody("game.create", uri, "{}");

        String first = mvc.perform(post(EXECUTE)
                        .header("Authorization", bearer(alice))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(raw))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replay"))
                .andReturn().getResponse().getContentAsString();

        long before = invocationCount(alice);

        mvc.perform(post(EXECUTE)
                        .header("Authorization", bearer(alice))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(raw))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(content().string(first));

        assertEquals(before, invocationCount(alice), "重放不该再落一行");
    }

    @Test
    void reusingAKeyWithADifferentPayloadIs422() throws Exception {
        String alice = principalId();
        install(alice, PrincipalType.HUMAN);
        String uri = "game://session/" + openSession(alice, PrincipalType.HUMAN);
        String key = "create-" + UUID.randomUUID();

        mvc.perform(post(EXECUTE)
                        .header("Authorization", bearer(alice))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody("game.create", uri, "{}")))
                .andExpect(status().isOk());

        mvc.perform(post(EXECUTE)
                        .header("Authorization", bearer(alice))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody("game.create", uri, "{\"opponentPrincipalId\":\"x\"}")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void writingWithoutAnIdempotencyKeyIs400() throws Exception {
        String alice = principalId();
        install(alice, PrincipalType.HUMAN);
        String uri = "game://session/" + openSession(alice, PrincipalType.HUMAN);

        mvc.perform(post(EXECUTE)
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody("game.create", uri, "{}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    // ─────────────────────────── 参数与身份 ───────────────────────────

    @Test
    void anUnknownActionIs404AndAMissingTargetIs400() throws Exception {
        String alice = principalId();

        mvc.perform(post(EXECUTE)
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody("game.teleport", "game://session/x", "{}")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACTION_NOT_FOUND"));

        mvc.perform(post(EXECUTE)
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody("game.make_move", null, "{}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TARGET_REQUIRED"));
    }

    /** 没有身份的请求 401, 而不是被当成某个默认的真人 —— 那正是 Principal 统一要防的事。 */
    @Test
    void anUnidentifiedCallerIs401() throws Exception {
        mvc.perform(post(EXECUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody("game.create", "game://session/x", "{}")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("DENIED"))
                .andExpect(jsonPath("$.error.code").value("UNIDENTIFIED_PRINCIPAL"));
    }

    /**
     * 没装这个应用的人调动作 → 403 NOT_INSTALLED。
     *
     * <p>注意这里<em>借用了别人的会话 URI</em>: 会话解析排在权限判定之前, 所以拿一个不存在的
     * 会话 id 过来会先撞上 404 UNKNOWN_SESSION, 测不到权限那条。这不是巧合 —— 顺序是
     * "解析目标 → 判权限", 于是未安装者拿随机 UUID 试探时得到的是 404 而非 403。
     */
    @Test
    void aCallerWithoutAnInstallationIs403() throws Exception {
        String alice = principalId();
        install(alice, PrincipalType.HUMAN);
        String uri = "game://session/" + openSession(alice, PrincipalType.HUMAN);

        mvc.perform(post(EXECUTE)
                        .header("Authorization", bearer(principalId()))
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody("game.create", uri, "{}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_INSTALLED"));
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private void install(String principalId, PrincipalType type) throws Exception {
        mvc.perform(post("/api/v1/applications/" + APP_ID + "/install")
                        .header("Authorization", bearer(principalId, type)))
                .andExpect(status().isOk());
    }

    private String openSession(String principalId, PrincipalType type) throws Exception {
        String body = mvc.perform(post("/api/v1/sessions")
                        .header("Authorization", bearer(principalId, type))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applicationId\":\"" + APP_ID + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("sessionId").asText();
    }

    private String executeBody(String action, String target, String inputJson) {
        return """
                {"action":"%s","target":%s,"input":%s}"""
                .formatted(action,
                        target == null ? "null" : "\"" + target + "\"",
                        inputJson);
    }

    private long invocationCount(String principalId) {
        return invocations.findAll().stream()
                .filter(r -> principalId.equals(r.getPrincipalId()))
                .count();
    }

    private static String principalId() {
        return UUID.randomUUID().toString();
    }

    private static String bearer(String principalId) {
        return bearer(principalId, PrincipalType.HUMAN);
    }

    private static String bearer(String principalId, PrincipalType type) {
        return StubPrincipalTokenReader.bearer(type, principalId);
    }
}
