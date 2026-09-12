package com.luxera.companion.application.remote;

import com.luxera.companion.application.action.ActionHandler;
import com.luxera.companion.application.action.ActionHandlerContext;
import com.luxera.companion.application.action.ActionOutcome;
import com.luxera.companion.application.manifest.ApplicationManifest;

/**
 * 一个 REMOTE 应用的某一个动作 —— <b>它就是"加一种运行时 = 加一个 handler"这句话本身。</b>
 *
 * <p>网关按 {@code (applicationId, version, actionId)} 找到它, 调 {@code execute}, 拿到
 * {@link ActionOutcome} —— 与内置应用交给它的东西是同一个类型。所以远端应用不需要在
 * {@code ActionGateway} 里有一条自己的路, 也不会因为"少走了一步"而与内置应用行为不一致。
 *
 * <p>它以 manifest 为不可变字段: 同一个远端应用的每个动作各持一个实例, 而它们共享同一个
 * manifest 对象 —— 转发时要用到的 {@code baseUrl} / {@code authRef} / 版本号都在那里。
 */
public final class RemoteActionHandler implements ActionHandler {

    private final ApplicationManifest manifest;
    private final RemoteApplicationInvoker invoker;

    public RemoteActionHandler(ApplicationManifest manifest, RemoteApplicationInvoker invoker) {
        this.manifest = manifest;
        this.invoker = invoker;
    }

    @Override
    public ActionOutcome execute(ActionHandlerContext context) {
        return invoker.invoke(manifest, context);
    }

    public ApplicationManifest manifest() {
        return manifest;
    }
}
