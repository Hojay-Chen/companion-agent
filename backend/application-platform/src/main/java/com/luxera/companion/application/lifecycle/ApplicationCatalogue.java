package com.luxera.companion.application.lifecycle;

import com.luxera.companion.application.domain.ApplicationRecord;
import com.luxera.companion.application.manifest.ApplicationManifest;
import com.luxera.companion.application.manifest.ManifestRegistry;
import com.luxera.companion.application.repository.ApplicationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * LAP v1: <b>发现链上看到的那些应用</b> —— 两份真相的交点。
 *
 * <p>平台里"有哪些应用"本来是两件事, 而且它们刻意是两件事:
 * <ul>
 *   <li>{@link ManifestRegistry} —— <b>这个 JVM 里注册了哪些 manifest</b>(随构建/部署而来);</li>
 *   <li>{@code application} 表 —— <b>生命周期状态</b>(随运营与开发者流程而来)。</li>
 * </ul>
 *
 * <p>发现链要的恰恰是两者的<em>交</em>: 一个应用既要在本构建里注册过, 又要处在
 * {@code PUBLISHED} 上。分散到各个调用方去各查一次的话, 一定会有一处忘了查 ——
 * 于是"下架的应用在 MCP 里还看得见、在 REST 里看不见"这类偏差就有了生长的空间。
 * 所以这里只答一次, 谁问都是同一个答案。
 *
 * <p><b>账本里没有这个应用时, 答案是"在架"。</b> 这看起来像放水, 但它挡住的是更坏的一种故障:
 * 一个本构建新引入的应用还没被启动同步写进数据库(或测试夹具根本没建账本行), 若按"查不到就是
 * 下架"处理, 它会在所有人眼皮底下凭空消失, 而日志里什么也没有。宁可多上一个, 不要静默少一个。
 */
@Slf4j
@Service
public class ApplicationCatalogue {

    private final ManifestRegistry manifests;
    private final ApplicationRepository applications;

    public ApplicationCatalogue(ManifestRegistry manifests, ApplicationRepository applications) {
        this.manifests = manifests;
        this.applications = applications;
    }

    /** 全部在架应用(每个应用的最新版本), 按 appId 排序。 */
    public List<ApplicationManifest> discoverable() {
        return manifests.applications().stream()
                .filter(m -> isDiscoverable(m.applicationId())).toList();
    }

    /** 某个能力下的在架应用 —— 发现链的第二级。 */
    public List<ApplicationManifest> discoverableFor(String capabilityId) {
        return manifests.byCapability(capabilityId).stream()
                .filter(m -> isDiscoverable(m.applicationId())).toList();
    }

    /** 某个应用当前在架的那一版; 不在架就是空。 */
    public Optional<ApplicationManifest> discoverable(String applicationId) {
        if (!isDiscoverable(applicationId)) {
            return Optional.empty();
        }
        return manifests.published(applicationId);
    }

    /** @see ApplicationCatalogue 类注释里"查不到即在架"的那一段 */
    public boolean isDiscoverable(String applicationId) {
        if (applicationId == null) {
            return false;
        }
        return applications.findById(applicationId)
                .map(ApplicationRecord::statusEnum)
                .map(status -> {
                    boolean ok = status.isDiscoverable();
                    if (!ok) {
                        log.debug("[ApplicationCatalogue] {} 当前状态 {} —— 不在发现链上",
                                applicationId, status);
                    }
                    return ok;
                })
                .orElse(true);
    }
}
