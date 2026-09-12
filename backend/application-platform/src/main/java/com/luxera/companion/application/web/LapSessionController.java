package com.luxera.companion.application.web;

import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.application.domain.SessionParticipantRecord;
import com.luxera.companion.application.principal.PrincipalResolvers;
import com.luxera.companion.application.principal.ResolvedPrincipal;
import com.luxera.companion.application.session.ApplicationSessionService;
import com.luxera.companion.application.session.ParticipantService;
import com.luxera.companion.application.session.SessionException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * LAP v2: <b>打开应用 = 开一个会话</b>。
 *
 * <p>v1 这里是 {@code LapInstallationController}, 三个概念(安装 / 会话 / 订阅)挤在一个类里,
 * 而安装排在第一位。v2 把安装整个拿掉了, 剩下两件事各自独立成控制器:
 *
 * <pre>
 *   会话   —— "这一次是这一场"(Application × 一群人, 有始有终)      ← 本类
 *   参与者 —— "谁在这一场里、他能做什么"({@link LapParticipantController})
 *   订阅   —— "这一类资源上的这一类事件我要"({@link LapSubscriptionController})
 * </pre>
 *
 * <p><b>没有"安装"了, 也没有任何东西取代它的位置。</b> 原则 1 说的是 Application 不需要用户
 * 安装 —— 不是"换个名字继续装"。所以这张面上不该有一个"激活/启用/订阅这个应用"的入口:
 * 打开一个应用就是开一个会话, 不打开就没有任何关系存在。
 *
 * <p>{@code POST /applications/{id}/install} 因此必须 404, 而 {@code check-lap.sh} 断言 1
 * 就是盯着这一条的。
 */
@RestController
@RequestMapping("/api/v1")
public class LapSessionController {

    private final ApplicationSessionService sessions;
    private final ParticipantService participants;
    private final PrincipalResolvers principals;

    public LapSessionController(ApplicationSessionService sessions,
                                ParticipantService participants,
                                PrincipalResolvers principals) {
        this.sessions = sessions;
        this.participants = participants;
        this.principals = principals;
    }

    /**
     * 开启选项。三个字段都是可选的 —— 一个空 {@code {}} 就够用。
     *
     * <p>刻意<b>没有</b> {@code applicationId}: 它在路径里。v1 的 {@code POST /sessions}
     * 把它放在请求体里, 于是同一个概念有两个来源, 而"路径里的那个和体里的那个不一样"这件事
     * 从来没有被定义过。
     */
    public record LaunchRequest(String conversationId, Integer minParticipants, Integer maxParticipants) {}

    /**
     * 一个会话对外的样子。
     *
     * <p>{@code participantCount} 是<em>当前在场</em>的人数, 不是历史累计 —— 看板上"这局几个人"
     * 要的是前者。想看在过场的全部人走 {@code GET /sessions/{id}/participants}。
     */
    public record SessionResponse(String sessionId,
                                  String applicationId,
                                  String versionId,
                                  String ownerPrincipalType,
                                  String ownerPrincipalId,
                                  String status,
                                  String visibility,
                                  String joinPolicy,
                                  int minParticipants,
                                  int maxParticipants,
                                  String conversationId,
                                  int participantCount,
                                  List<String> capabilities,
                                  String createdAt,
                                  String startedAt,
                                  String endedAt,
                                  String lastActiveAt) {

        static SessionResponse of(ApplicationSessionRecord row,
                                  int participantCount,
                                  List<String> capabilities) {
            return new SessionResponse(row.getId(), row.getApplicationId(), row.getVersionId(),
                    row.getOwnerPrincipalType(), row.getOwnerPrincipalId(),
                    row.getStatus(), row.getVisibility(), row.getJoinPolicy(),
                    row.getMinParticipants(), row.getMaxParticipants(), row.getConversationId(),
                    participantCount, capabilities,
                    text(row.getCreatedAt()), text(row.getStartedAt()),
                    text(row.getEndedAt()), text(row.getLastActiveAt()));
        }

        private static String text(java.time.LocalDateTime value) {
            return value == null ? null : value.toString();
        }
    }

    // ─────────────────────────── 开启 ───────────────────────────

    /**
     * 打开应用 —— <b>每次都是一个新会话</b>。
     *
     * <p>"每次都新建"是刻意的, 也是它与 {@code ensureSession}(进程内那条幂等的路)的全部区别:
     * 用户在应用市场点"打开", 要的是开一局新的, 而不是被塞回昨天那局。想续上一局的人拿着旧会话
     * id 回来就是了(见 {@code GET /sessions})。
     */
    @PostMapping("/applications/{applicationId}/sessions")
    public SessionResponse launch(@PathVariable String applicationId,
                                  @RequestHeader(value = "Authorization", required = false) String authorization,
                                  @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
                                  @RequestBody(required = false) LaunchRequest request) {
        ResolvedPrincipal principal = principal(authorization, correlationId);
        ApplicationSessionRecord session = sessions.launch(
                applicationId,
                principal,
                request == null ? null : request.conversationId(),
                request == null ? null : request.minParticipants(),
                request == null ? null : request.maxParticipants());
        return view(session, principal);
    }

    // ─────────────────────────── 读 ───────────────────────────

    /**
     * 看一个会话。<b>只有在这局里的人看得到</b> —— 这是原则 3 最直白的一条: 会话是协作的边界,
     * 边界的意思就是"外面的人看不见里面"。
     *
     * <p>刻意不用 {@code requireUsable}: 那个方法连 ENDED 都要拒(409)。而这里读的是一个<em>已经
     * 结束</em>的会话时, 正确的答案是把它交出去 —— "这局昨天结束了"比"会话已结束"有用得多,
     * 而且调用方要的正是看一眼它的终局。读路径只挡"不知情的人", 不挡"过期的东西"。
     */
    @GetMapping("/sessions/{sessionId}")
    public SessionResponse session(@PathVariable String sessionId,
                                   @RequestHeader(value = "Authorization", required = false) String authorization,
                                   @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        ResolvedPrincipal principal = principal(authorization, correlationId);
        ApplicationSessionRecord session = sessions.require(sessionId);
        requireParticipant(sessionId, principal);
        return view(session, principal);
    }

    /** 我参与的全部活跃会话 —— 真人 UI 的"我正在用的应用"用的就是它。 */
    @GetMapping("/sessions")
    public List<SessionResponse> sessions(@RequestParam(required = false) String companionId,
                                          @RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        ResolvedPrincipal principal = principal(authorization, correlationId);
        return sessions.ofPrincipal(principal.type(), principal.principalId()).stream()
                .filter(s -> matchesCompanion(s.getId(), principal, companionId))
                .map(s -> view(s, principal))
                .toList();
    }

    // ─────────────────────────── 结束 ───────────────────────────

    /**
     * 结束一个会话。只有开局的人能结束它。
     *
     * <p>v1 这里是不设防的(任何装了应用的人都能结束任何会话)—— 那时候会话是"我的", 结束自己的
     * 东西无害。会话变成多人共用之后, 不设防意味着<em>对手可以随时把棋局收掉</em>。所以要求
     * OWNER: 这不是权限判定(那要看 {@code session_permission}), 而是"谁说得上话"。
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> end(@PathVariable String sessionId,
                                    @RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        ResolvedPrincipal principal = principal(authorization, correlationId);
        SessionParticipantRecord me = participants.find(sessionId, principal.type(), principal.principalId())
                .orElseThrow(() -> new SessionException("NOT_A_PARTICIPANT",
                        "调用方不在会话 " + sessionId + " 里"));
        if (!me.owner()) {
            throw new SessionException("NOT_SESSION_OWNER", "只有会话的主人能结束它");
        }
        sessions.end(sessionId);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private SessionResponse view(ApplicationSessionRecord session, ResolvedPrincipal principal) {
        return SessionResponse.of(session,
                participants.activeParticipantsOf(session.getId()).size(),
                participants.find(session.getId(), principal.type(), principal.principalId())
                        .map(participants::capabilitiesOf)
                        .orElse(List.of()));
    }

    private void requireParticipant(String sessionId, ResolvedPrincipal principal) {
        if (participants.find(sessionId, principal.type(), principal.principalId()).isEmpty()) {
            throw new SessionException("NOT_A_PARTICIPANT",
                    principal.typeName() + " " + principal.principalId()
                            + " 不在会话 " + sessionId + " 里");
        }
    }

    /** {@code companionId} 只是一个过滤器, 不是一个身份 —— 身份永远来自凭据。 */
    private boolean matchesCompanion(String sessionId, ResolvedPrincipal principal, String companionId) {
        if (companionId == null || companionId.isBlank()) {
            return true;
        }
        return participants.find(sessionId, principal.type(), principal.principalId())
                .map(p -> companionId.equals(p.getCompanionId()))
                .orElse(false);
    }

    private ResolvedPrincipal principal(String authorization, String correlationId) {
        return principals.resolveHeader(authorization,
                correlationId == null || correlationId.isBlank()
                        ? UUID.randomUUID().toString()
                        : correlationId);
    }
}
