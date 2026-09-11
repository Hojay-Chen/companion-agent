package com.luxera.companion.application.builtin.tictactoe;

import com.luxera.companion.application.RecordingApplicationEventSink;
import com.luxera.companion.application.RecordingChatWorld;
import com.luxera.companion.contracts.application.ApplicationEvent;
import com.luxera.companion.contracts.events.ChatEventTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 应用平台一侧的事件投递契约 —— 三条互不相干的性质, 每一条都曾经或差点成为线上 bug:
 *
 * <ol>
 *   <li><b>agentTrigger 只在轮到数字人时为真。</b> 从前这个判断写在 {@code AgentRuntime} 里
 *       ({@code if (!"O".equalsIgnoreCase(turn))}) —— 数字人认识井字棋的回合规则。
 *       现在由应用算, 数字人只认那个布尔。</li>
 *   <li><b>事件 id 是确定性的。</b> 同一局同一手重放必须得到同一个 id, 否则数字人侧的去重
 *       形同虚设, 它会对着同一步棋落两次子。</li>
 *   <li><b>投递发生在事务提交之后。</b> 这条最贵: 若在事务内投递, 数字人可能读到随后回滚的
 *       状态, 于是"自信地回应了一步从未发生的棋"。下面用一次显式的回滚来钉死它。</li>
 * </ol>
 */
@ActiveProfiles("test")
@SpringBootTest
class TicTacToeEventDeliveryTest {

    @Autowired
    TicTacToeGameService gameService;

    @Autowired
    RecordingApplicationEventSink sink;

    @Autowired
    RecordingChatWorld chatWorld;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void reset() {
        clearRecordings();
    }

    /** 两个记录器都是单例 Bean, 跨测试方法累积 —— 断言计数前必须清干净。 */
    private void clearRecordings() {
        sink.clear();
        chatWorld.clear();
    }

    @Test
    void humanMoveHandsTheTurnToTheAgentExactlyOnce() {
        GameSession session = gameService.start(newId(), newId());
        clearRecordings();   // 开局那一条不算: 这里只数"真人落的这一手"

        gameService.move(session.getRoomId(), "user", 0);

        assertEquals(1, sink.eventsOfType("game.move").size(), "一手棋只该发一条应用事件");
        ApplicationEvent move = sink.eventsOfType("game.move").get(0);
        assertTrue(move.data().path("agentTrigger").asBoolean(false),
                "真人落子后轮到 O —— 应用应把 agentTrigger 置真, 数字人不必自己判断回合");
        assertEquals(TicTacToeApplicationAdapter.uriOf(session.getRoomId()), move.target(),
                "事件必须带资源 URI, 数字人靠它读统一读模型");
        assertTrue(move.data().path("companionId").asText("").length() > 0,
                "应用事件必须带 companionId —— 数字人侧靠它决定'这是谁的事'");

        // 前端 SSE 那一路照旧, 两条路互不影响
        assertEquals(1, chatWorld.publishedTypes().stream()
                .filter(ChatEventTypes.GAME_EVENT::equals).count());
    }

    @Test
    void theAgentsOwnMoveNeverTriggersTheAgent() {
        GameSession session = gameService.start(newId(), newId());

        assertTrue(sink.eventsOfType("game.start").stream()
                        .noneMatch(e -> e.data().path("agentTrigger").asBoolean(false)),
                "开局不是数字人的回合");
        clearRecordings();

        gameService.move(session.getRoomId(), "companion", 4);   // 数字人自己那一手 → 轮到 X

        assertEquals(1, sink.eventsOfType("game.move").size());
        assertFalse(sink.eventsOfType("game.move").get(0).data().path("agentTrigger").asBoolean(true),
                "数字人自己落的子不该再唤起它自己 —— 否则会自走不停");
    }

    @Test
    void eventIdIsDeterministic() {
        GameSession session = gameService.start(newId(), newId());
        clearRecordings();
        gameService.move(session.getRoomId(), "user", 0);

        // 同一局同一手: 应用重新发射同一条事件(重试/重放), id 必须一模一样
        assertEquals(TicTacToeApplicationAdapter.uriOf(session.getRoomId()) + "#MOVE-0",
                sink.eventsOfType("game.move").get(0).id(),
                "事件 id 必须是 (资源, 类型, 落点) 的确定函数, 不能是随机 UUID");
    }

    @Test
    void rollingBackTheTransactionDropsTheEvent() {
        GameSession session = gameService.start(newId(), newId());
        clearRecordings();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            gameService.move(session.getRoomId(), "user", 0);
            throw new IllegalStateException("业务在落子之后失败");
        }));

        assertTrue(sink.events().isEmpty(),
                "事务回滚了, 事件就不能发出去 —— 否则数字人会回应一步从未发生的棋");
    }

    private static String newId() {
        return UUID.randomUUID().toString();   // 列宽 varchar(36), 别拼前缀
    }
}
