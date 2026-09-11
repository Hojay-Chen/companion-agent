package com.luxera.companion.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.AttentionPolicy;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.PermissionLevel;
import com.luxera.companion.contracts.application.ResourceView;
import com.luxera.companion.contracts.application.RiskLevel;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import com.luxera.companion.digitalhuman.actor.PersonActorRegistry;
import com.luxera.companion.digitalhuman.event.EventRouter;
import com.luxera.companion.digitalhuman.event.ExternalEvent;
import com.luxera.companion.digitalhuman.event.ExternalEventType;
import com.luxera.companion.digitalhuman.reality.RealityEventType;
import com.luxera.companion.digitalhuman.reality.RealityLedger;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import com.luxera.companion.llm.StructuredResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * LAP v1 R2: 数字人反应应用事件的通用链。
 *
 * 本测试最要紧的一条是 <b>"LLM 不可用 ⇒ 零次 execute"</b> —— 用户明确要求数字人下棋必须
 * 由 LLM 决策, 不许有启发式兜底。把这条断言写死在这里, 就是这次解耦过程中该性质不会
 * 被悄悄替换成"随便挑一个空位"的保险丝。
 *
 * <p>直接构造 {@code AgentApplicationFlow}(不启 Spring): 用 Mockito 的
 * {@code PersonActorRegistry} 把 mailbox 变成同线程直调, 于是断言是确定性的, 不靠 sleep。
 */
class AgentApplicationFlowTest {

    private static final String COMPANION = "companion-1";
    private static final String USER = "user-1";
    private static final String URI = "game://session/room-1";

    private final ObjectMapper mapper = new ObjectMapper();

    private ApplicationRuntimePort port;
    private LlmRouter llmRouter;
    private RealityLedger realityLedger;
    private AgentApplicationFlow flow;

    @BeforeEach
    void setUp() {
        port = mock(ApplicationRuntimePort.class);
        llmRouter = mock(LlmRouter.class);
        realityLedger = mock(RealityLedger.class);

        // mailbox 同线程直调, 让"订阅 → 反应"这条链在测试里是同步的
        PersonActorRegistry registry = mock(PersonActorRegistry.class);
        doAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return null;
        }).when(registry).tell(anyString(), any(Runnable.class));

        flow = new AgentApplicationFlow(port, llmRouter, realityLedger, new EventRouter(), registry);
    }

    // ─────────────────────────── LLM 优先, 不降级 ───────────────────────────

    @Test
    void llmUnavailableMeansTheAgentDoesNothing() {
        when(llmRouter.available()).thenReturn(false);
        stubResourceAndPending();

        flow.react(COMPANION, USER, URI, "evt-1");

        verify(port, never()).execute(any(), any());
        verifyNoInteractions(realityLedger);
    }

    @Test
    void mockProviderMeansTheAgentDoesNothing() {
        when(llmRouter.available()).thenReturn(true);
        when(llmRouter.isMockActive()).thenReturn(true);
        stubResourceAndPending();

        flow.react(COMPANION, USER, URI, "evt-1");

        verify(port, never()).execute(any(), any());
        verifyNoInteractions(realityLedger);
    }

    // ─────────────────────────── 正常路径 ───────────────────────────

    @Test
    void llmChoiceBecomesExactlyOneExecuteWithTheLlmInput() {
        stubResourceAndPending();
        stubLlm("""
                {"input":{"position":4,"player":"companion"},"reason":"占据中心"}
                """);
        when(port.execute(any(), any())).thenReturn(ActionResponse.success(mapper.createObjectNode(), null));

        flow.react(COMPANION, USER, URI, "evt-1");

        ArgumentCaptor<ActionRequest> request = ArgumentCaptor.forClass(ActionRequest.class);
        verify(port, times(1)).execute(request.capture(), any(InvocationContext.class));

        assertEquals("game.make_move", request.getValue().action());
        assertEquals(URI, request.getValue().target());
        assertEquals(4, request.getValue().input().path("position").asInt());
        assertEquals("companion", request.getValue().input().path("player").asText());
        // 乐观并发令牌来自读到的资源版本 —— CAS 在 R4 生效, 但现在就得传对
        assertEquals(1L, request.getValue().expectedResourceVersion());

        verify(realityLedger, times(1))
                .append(eq(COMPANION), eq(RealityEventType.APPLICATION_ACTION_EXECUTED), any(), any(), any());
    }

    @Test
    void llmSayingDoNothingProducesNoExecute() {
        stubResourceAndPending();
        stubLlm("""
                {"input":null,"reason":"局面还不该我动"}
                """);

        flow.react(COMPANION, USER, URI, "evt-1");

        verify(port, never()).execute(any(), any());
        verifyNoInteractions(realityLedger);
    }

    // ─────────────────────────── 边界 ───────────────────────────

    @Test
    void nothingPendingMeansNoLlmCallAtAll() {
        when(port.read(URI)).thenReturn(Optional.of(resource()));
        when(port.pendingActions(eq(URI), any())).thenReturn(List.of());

        flow.react(COMPANION, USER, URI, "evt-1");

        verifyNoInteractions(llmRouter);
        verify(port, never()).execute(any(), any());
    }

    @Test
    void missingResourceMeansNothingHappens() {
        when(port.read(URI)).thenReturn(Optional.empty());

        flow.react(COMPANION, USER, URI, "evt-1");

        verifyNoInteractions(llmRouter);
        verify(port, never()).execute(any(), any());
    }

    @Test
    void rejectedActionIsNotWrittenToTheLedger() {
        stubResourceAndPending();
        stubLlm("""
                {"input":{"position":4,"player":"companion"},"reason":"占据中心"}
                """);
        when(port.execute(any(), any())).thenReturn(ActionResponse.failure(
                com.luxera.companion.contracts.application.ActionStatus.STATE_CONFLICT,
                "STATE_CONFLICT", "版本已变"));

        flow.react(COMPANION, USER, URI, "evt-1");

        verify(port, times(1)).execute(any(), any());
        verifyNoInteractions(realityLedger);
    }

    // ─────────────────────────── 事件入口过滤 ───────────────────────────

    @Test
    void agentTriggerFalseIsIgnoredBeforeAnyRead() {
        flow.onApplicationEvent(ExternalEvent.of(COMPANION, ExternalEventType.APPLICATION_EVENT,
                Map.of("resourceUri", URI, "agentTrigger", false)));

        verifyNoInteractions(port);
    }

    @Test
    void agentTriggerTrueReachesThePort() {
        stubResourceAndPending();
        stubLlm("""
                {"input":{"position":0,"player":"companion"},"reason":"占角"}
                """);
        when(port.execute(any(), any())).thenReturn(ActionResponse.success(mapper.createObjectNode(), null));

        flow.onApplicationEvent(ExternalEvent.of(COMPANION, ExternalEventType.APPLICATION_EVENT,
                Map.of("resourceUri", URI, "userId", USER, "agentTrigger", true)));

        verify(port, times(1)).execute(any(), any());
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private void stubResourceAndPending() {
        when(port.read(URI)).thenReturn(Optional.of(resource()));
        when(port.pendingActions(eq(URI), any())).thenReturn(List.of(makeMoveSpec()));
    }

    private void stubLlm(String json) {
        when(llmRouter.available()).thenReturn(true);
        when(llmRouter.isMockActive()).thenReturn(false);
        when(llmRouter.structured(any(StructuredRequest.class)))
                .thenReturn(new StructuredResult(json, mapper));
    }

    private ResourceView resource() {
        JsonNode state = mapper.valueToTree(Map.of(
                "board", List.of("X", "", "", "", "O", "", "", "", ""),
                "turn", "O", "winner", ""));
        return new ResourceView(URI, "game.session", "tictactoe", "room-1", state, 1L, Instant.now(), null);
    }

    private ActionSpec makeMoveSpec() {
        JsonNode schema = mapper.valueToTree(Map.of(
                "type", "object",
                "properties", Map.of("position", Map.of("type", "integer"))));
        return new ActionSpec("game.make_move", "tictactoe", "game.play", "落子",
                PermissionLevel.WRITE, RiskLevel.LOW, AttentionPolicy.FOCUSED, schema,
                "能三连就三连, 否则阻断对手");
    }
}
