package com.luxera.companion.application.builtin.reminder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luxera.companion.application.RecordingApplicationEventSink;
import com.luxera.companion.application.action.ActionGateway;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.session.InstallationService;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ApplicationEvent;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.PrincipalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「到点了」—— <b>提醒功能里唯一的调度器</b>, 也是"订阅不是调度器"这句话的可执行版本。
 *
 * <p>订阅回答的是"发生了一件事之后, 谁想收到"; 而 15:00 提醒交房租的这一刻<em>什么也没发生</em>,
 * 只是时间到了。所以它不能表达成订阅, 只能表达成"扫一遍到点的行"。这个类钉住的就是那个扫描:
 *
 * <ol>
 *   <li><b>只挑 PENDING 且已到点。</b>没到点的不动, 办完/取消了的不动;</li>
 *   <li><b>挑中即翻成 DISPATCHED。</b>于是第二次扫描不会重复挑中同一行 —— 扫描范围有界,
 *       而不是"每五分钟把历史全部重发一遍";</li>
 *   <li><b>发 {@code reminder.due}, 且只有真的叫得醒人才出平台。</b>没有数字人安装时事件
 *       安静地留在平台里, 不该凭空产生一次投递。</li>
 * </ol>
 *
 * <p>用例里造的时间是<em>写死的</em>而不是 {@code now()} —— 调度器接受一个"现在", 所以
 * "到点了没有"这件事可以被精确地摆出来, 不必等到真的到点。
 */
@ActiveProfiles("test")
@SpringBootTest
class ReminderDispatchJobTest {

    private static final String APP_ID = "com.luxera.reminder";
    private static final String CREATE = "reminder.create";
    private static final String UPDATE = "reminder.update";
    private static final String COMPLETE = "reminder.complete";
    private static final String DUE_EVENT = "reminder.due";

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 12, 15, 0);

    @Autowired
    ReminderDispatchJob job;

    @Autowired
    ReminderItemRepository items;

    @Autowired
    ActionGateway gateway;

    @Autowired
    InstallationService installationService;

    @Autowired
    RecordingApplicationEventSink events;

    @Autowired
    ObjectMapper objectMapper;

    /**
     * 调度器扫的是<em>整张表</em> —— 它本来就是全局的, 没有"只扫某个人的"这种形态。
     * 于是任何断言精确条数的用例都必须先自己拥有这张表, 否则它测到的是别的用例留下的行。
     * 清空是诚实的做法: 与其把断言放宽成"至少 2 条", 不如让每一轮扫描的范围就是这一条用例摆出来的局面。
     */
    @BeforeEach
    void cleanTheTable() {
        items.deleteAll();
        events.clear();
    }

    // ─────────────────────────── 扫描范围 ───────────────────────────

    @Test
    void onlyRemindersThatAreDueAndPendingAreDispatched() {
        Human owner = newHuman();
        String past = create(owner, "早就该提醒了", "2026-09-12T14:00");
        String exactlyNow = create(owner, "刚好到点", "2026-09-12T15:00");
        String future = create(owner, "还没到", "2026-09-12T16:00");
        String done = create(owner, "已经办完了", "2026-09-12T13:00");
        gateway.execute(ActionRequest.of(COMPLETE, uriOf(owner), idInput(done)), ctx(owner));

        assertEquals(2, job.dispatch(NOW), "到点的两条应当被派发, 其余不动");

        assertEquals(ReminderItem.STATUS_DISPATCHED, status(past));
        assertEquals(ReminderItem.STATUS_DISPATCHED, status(exactlyNow), "恰好等于 now 的也算到点");
        assertEquals(ReminderItem.STATUS_PENDING, status(future), "没到点的不动");
        assertEquals(ReminderItem.STATUS_DONE, status(done), "已经办完的不动");
    }

    @Test
    void nothingHappensWhenNothingIsDue() {
        Human owner = newHuman();
        create(owner, "以后再说", "2027-01-01T09:00");

        assertEquals(0, job.dispatch(NOW));
    }

    /** 挑选是<em>有界</em>的: 派发过的不会在下一轮里被再挑一次。 */
    @Test
    void aDispatchedReminderIsNotPickedUpAgain() {
        Human owner = newHuman();
        create(owner, "交房租", "2026-09-12T14:00");

        assertEquals(1, job.dispatch(NOW));
        assertEquals(0, job.dispatch(NOW.plusMinutes(5)), "第二轮不该再挑中它");
        assertEquals(0, job.dispatch(NOW.plusDays(1)), "一天之后也不该");
    }

    /** 改期到未来会让它重新进入待办 —— "改个时间"是用户最常见的操作。 */
    @Test
    void reschedulingBringsAReminderBackIntoTheScan() {
        Human owner = newHuman();
        String id = create(owner, "交房租", "2026-09-12T14:00");
        assertEquals(1, job.dispatch(NOW));

        ObjectNode input = idInput(id);
        input.put("dueAt", "2026-09-12T18:00");
        gateway.execute(ActionRequest.of(UPDATE, uriOf(owner), input), ctx(owner));

        assertEquals(0, job.dispatch(NOW), "改到未来之后现在还没到点");
        assertEquals(1, job.dispatch(NOW.plusHours(3)), "到了新时间会再响一次");
    }

    // ─────────────────────────── 事件的形状 ───────────────────────────

    /** 到了点, 应用说"这条提醒关着谁" —— 名单里是它自己的 {@code companionId}。 */
    @Test
    void theDueEventSaysWhoTheReminderIsAbout() {
        Human owner = newHuman();
        Agent companion = newAgent();
        String companionId = companion.companionId;
        create(owner, "交房租", "2026-09-12T14:00", companionId);

        job.dispatch(NOW);

        ApplicationEvent event = dueEvent();
        assertEquals(DUE_EVENT, event.type());
        assertEquals(APP_ID, event.source());
        assertEquals(uriOf(owner), event.target());
        assertEquals(companionId, event.data().path("notifyPrincipalIds").get(0).asText());
        assertEquals("20260912T1400", event.data().path("dueKey").asText());
        assertTrue(event.data().path("agentTrigger").asBoolean(),
                "到点是这条应用里唯一会唤起数字人的事");
    }

    /**
     * <b>没有数字人安装时, 事件不出平台。</b>
     *
     * <p>用户自己设的提醒如果没挂任何数字人, 那么"到点了"这件事在平台里成立、在数字人那里不成立 ——
     * 它不是一条给谁的消息。路由答不出收件人, 投递就不该发生。
     *
     * <p>同时断言<em>库里的状态照翻</em>: 扫描与投递是两件事。让扫描因为无人接收而回退, 会让
     * 这条提醒在下一轮被重新挑中, 于是"没人收"变成"每五分钟扫一遍", 而它本来只是"这一条没人收"。
     */
    @Test
    void aDueEventWithNoAgentInstalledDoesNotLeaveThePlatform() {
        Human owner = newHuman();
        String id = create(owner, "没人关心的提醒", "2026-09-12T14:00");
        events.clear();

        assertEquals(1, job.dispatch(NOW));

        assertEquals(List.of(), events.events(), "没有数字人时不该有人收到投递");
        assertEquals(ReminderItem.STATUS_DISPATCHED, status(id));
    }

    /**
     * 装了应用的数字人会被叫醒, 而且事件上被盖了 {@code companionId}。
     *
     * <p>这一条是 {@code 应用说"关着谁" → 平台答"其中谁是数字人"}} 这条链路的端到端证据。
     * 应用侧代码里没有一处 Human / Agent 分支 —— 它只是把 {@code companionId} 填进名单,
     * 谁是数字人由平台查安装表回答。
     */
    @Test
    void aDueEventReachesTheAgentThatHasTheApplicationInstalled() {
        Human owner = newHuman();
        Agent companion = newAgent();
        create(owner, "交房租", "2026-09-12T14:00", companion.companionId);
        events.clear();

        job.dispatch(NOW);

        assertEquals(companion.companionId,
                dueEvent().data().path("companionId").asText(),
                "平台把'谁是数字人'的答案盖了进去");
    }

    /** 挂了一个<em>没装过这个应用的</em>数字人不算数 —— 路由查的是安装表, 不是名单本身。 */
    @Test
    void aCompanionThatHasNotInstalledTheApplicationIsNotWoken() {
        Human owner = newHuman();
        create(owner, "交房租", "2026-09-12T14:00", "companion-without-install");
        events.clear();

        job.dispatch(NOW);

        assertEquals(List.of(), events.events(), "没装应用的数字人不是这条事件的对象");
    }

    /**
     * 事件 id 里带着 {@code dueKey}: 同一次到点重发是同一件事, 改了期是另一件事。
     *
     * <p>这是"到点"这种事件的天然困难 —— 它没有"用户点了按钮"这样的唯一动作可依附, 只有一个时刻。
     * 把那个时刻写进 id, 就把"重复扫描"和"又一次到点"分开来了。
     */
    @Test
    void theEventIdCarriesTheDueKey() {
        Human owner = newHuman();
        Agent companion = newAgent();
        String id = create(owner, "交房租", "2026-09-12T14:00", companion.companionId);

        job.dispatch(NOW);
        String first = dueEvent().id();
        assertTrue(first.endsWith("20260912T1400"), "id 里应当看得见是哪一次: " + first);

        ObjectNode input = idInput(id);
        input.put("dueAt", "2026-09-12T18:00");
        gateway.execute(ActionRequest.of(UPDATE, uriOf(owner), input), ctx(owner));
        events.clear();
        job.dispatch(NOW.plusHours(4));

        assertNotEquals(first, dueEvent().id(), "改期后的到点是另一次到点");
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private record Human(String principalId) {
        ResolvedPrincipal principal() {
            return new ResolvedPrincipal(PrincipalType.HUMAN, principalId, null, principalId,
                    null, UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_JWT);
        }
    }

    private record Agent(String companionId, String userId) {
        ResolvedPrincipal principal() {
            return new ResolvedPrincipal(PrincipalType.AGENT, companionId, companionId, userId,
                    null, UUID.randomUUID().toString(), ResolvedPrincipal.SOURCE_INTERNAL);
        }
    }

    private Human newHuman() {
        Human human = new Human(UUID.randomUUID().toString());
        installationService.install(APP_ID, human.principal(), null);
        return human;
    }

    private Agent newAgent() {
        Agent agent = new Agent("companion-" + UUID.randomUUID(), UUID.randomUUID().toString());
        installationService.install(APP_ID, agent.principal(), null);
        return agent;
    }

    private static String uriOf(Human human) {
        return ReminderApplication.uriOf(human.principalId());
    }

    private String create(Human owner, String title, String dueAt) {
        return create(owner, title, dueAt, null);
    }

    private String create(Human owner, String title, String dueAt, String companionId) {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("title", title);
        input.put("dueAt", dueAt);
        if (companionId != null) {
            input.put("companionId", companionId);
        }
        return gateway.execute(ActionRequest.of(CREATE, uriOf(owner), input), ctx(owner))
                .result().path("id").asText();
    }

    private String status(String reminderId) {
        return items.findById(reminderId).orElseThrow().getStatus();
    }

    /** 最后一条投递出去的 {@code reminder.due} —— 用例都通过"数字人收到了什么"来观察调度结果。 */
    private ApplicationEvent dueEvent() {
        List<ApplicationEvent> matching = events.eventsOfType(DUE_EVENT);
        assertTrue(!matching.isEmpty(), "没有收到 " + DUE_EVENT + " 投递: " + events.events());
        return matching.get(matching.size() - 1);
    }

    private ObjectNode idInput(String reminderId) {
        return objectMapper.createObjectNode().put("reminderId", reminderId);
    }

    private static InvocationContext ctx(Human human) {
        return human.principal().toInvocationContext();
    }
}
