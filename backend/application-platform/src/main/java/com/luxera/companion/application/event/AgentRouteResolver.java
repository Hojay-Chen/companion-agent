package com.luxera.companion.application.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.application.domain.ApplicationSessionRecord;
import com.luxera.companion.application.domain.InstallationRecord;
import com.luxera.companion.application.repository.ApplicationSessionRepository;
import com.luxera.companion.application.repository.InstallationRepository;
import com.luxera.companion.contracts.application.ApplicationEvent;
import com.luxera.companion.contracts.application.PrincipalType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * LAP v1: "这条事件该叫醒哪一个数字人" —— 平台侧的答案。
 *
 * <p><b>为什么这件事必须由平台做, 而不能由应用做。</b> 应用知道的是"这盘棋里除了我之外还有谁"
 * (它把这些人写进 {@code data.notifyPrincipalIds}); 应用<em>不可能</em>知道其中谁是 Agent ——
 * 它的代码里不允许出现 Human / Agent 分支, 那是开工前强制约束里的一条。反过来, 平台只需要查一次
 * {@code installation} 表就知道答案: 一个 principal 装了某个应用并且 {@code principal_type=AGENT},
 * 它就是我们这边的数字人。
 *
 * <p>于是有一条干净的切分:
 * <ul>
 *   <li>应用说 —— "还有谁该知道这件事" ({@code notifyPrincipalIds});</li>
 *   <li>平台答 —— "其中谁是数字人" (本类), 并把 {@code companionId} 盖进 {@code data};</li>
 *   <li>数字人说 —— "那我就读一下资源看看能做什么" ({@code AgentApplicationFlow})。</li>
 * </ul>
 *
 * <p><b>不猜。</b> 名单里没有 AGENT 安装就返回空, 事件不出平台 —— 宁可数字人晚知道, 也不要它
 * 因为一条"大概是给它的"事件去读一个不相干的资源。这条与 {@code DhApplicationEventSink}
 * 里"缺了 companionId 就丢弃并告警"是同一句话的两端。
 *
 * <p>{@code userId} 顺带取出来: 数字人记一笔账时要知道它代表的是哪位用户, 而那是会话的属性,
 * 只有平台查得到。取不到就留空 —— 空 userId 不影响权限判定(权限锚在 principal 上)。
 */
@Service
public class AgentRouteResolver {

    private final InstallationRepository installations;
    private final ApplicationSessionRepository sessions;

    public AgentRouteResolver(InstallationRepository installations, ApplicationSessionRepository sessions) {
        this.installations = installations;
        this.sessions = sessions;
    }

    /** 路由结果: 叫醒谁, 以及它代表哪位用户。 */
    public record Route(String companionId, String userId) {}

    /** 在事件声明的通知名单里, 找出第一个装了本应用的数字人。 */
    public Optional<Route> resolve(String applicationId, ApplicationEvent event) {
        if (applicationId == null || event == null) {
            return Optional.empty();
        }
        for (String principalId : notifyPrincipalIds(event.data())) {
            Optional<InstallationRecord> installation =
                    installations.findByApplicationIdAndPrincipalTypeAndPrincipalId(
                            applicationId, PrincipalType.AGENT.name(), principalId);
            if (installation.isPresent() && installation.get().active()) {
                return Optional.of(new Route(principalId, userIdOf(applicationId, principalId)));
            }
        }
        return Optional.empty();
    }

    /** @see ApplicationEvent 里对 {@code data.notifyPrincipalIds} 的约定 */
    public static List<String> notifyPrincipalIds(JsonNode data) {
        if (data == null || !data.path("notifyPrincipalIds").isArray()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode node : data.get("notifyPrincipalIds")) {
            if (node != null && node.isTextual() && !node.asText().isBlank()) {
                ids.add(node.asText());
            }
        }
        return ids;
    }

    /**
     * 该数字人在这个应用里代表的那位用户。同一 companion 在一个应用里可能开过多个会话
     * (每局棋一个), 取最近活跃的那个 —— 用户身份不会因为换了一局棋而变化, 所以这里
     * 只影响"取不取得到", 不影响"取得对不对"。
     */
    private String userIdOf(String applicationId, String companionId) {
        List<ApplicationSessionRecord> found = sessions.findByApplicationIdAndPrincipalTypeAndPrincipalId(
                applicationId, PrincipalType.AGENT.name(), companionId);
        return found.stream()
                .filter(ApplicationSessionRecord::active)
                .map(ApplicationSessionRecord::getUserId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElse(null);
    }
}
