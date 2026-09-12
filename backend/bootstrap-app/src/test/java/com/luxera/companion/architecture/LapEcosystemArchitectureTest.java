package com.luxera.companion.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * LAP v2 §115–§118 的四条架构守卫 —— 放在 bootstrap-app, 与
 * {@link ModuleBoundaryArchitectureTest} 同一个理由: 只有这里同时看得见所有模块。
 *
 * <p>这四条守卫与前两条(§113/§114, 已分别在 DhApplicationKnowledgeArchitectureTest
 * 与 ModuleBoundaryArchitectureTest)合起来, 对应方案里的六条"最重要的架构守卫"。
 * 它们与前两条不同: 不是模块之间的边界, 而是<b>同一模块内部的层边界</b> ——
 * 一条越界不会在 pom 上留下痕迹(依赖关系完全合法), 只有对着字节码问"谁调了谁"才查得出来。
 *
 * <table border="1">
 *   <tr><th>节</th><th>守的是什么</th><th>越界的症状</th></tr>
 *   <tr><td>§115</td><td>chat 只认识 Application Protocol, 不认识 application.builtin.*</td>
 *       <td>聊天平台出现"五子棋"三个字 —— 第三方应用上架时聊天侧要跟着改</td></tr>
 *   <tr><td>§116</td><td>所有 Action 执行经过 ActionGateway</td>
 *       <td>Controller 直调 handler, 绕过幂等/审计/权限 —— 平台的核心承诺失效</td></tr>
 *   <tr><td>§117</td><td>所有 Resource 写经过 ResourceStore</td>
 *       <td>Controller 直写 repository, 绕过 CAS —— 两个 principal 抢同一步棋时丢子</td></tr>
 *   <tr><td>§118</td><td>Agent 的应用动作经 ApplicationRuntimePort / Gateway</td>
 *       <td>AgentRuntime 直调平台服务 —— "加第二个游戏要改 DH"回到仓库里</td></tr>
 * </table>
 *
 * <p>§116/§117 的检查面刻意是 <b>web 层</b>对 <b>gateway/repository</b> 的依赖:
 * "Controller → Gateway → (ResourceStore)" 是合法链, 断言的是 web 层不许<b>跳过</b>中间层。
 * ActionGateway 自身依赖 ResourceAccess/ResourceStore 不但合法, 而且正是设计。
 */
class LapEcosystemArchitectureTest {

    private static final String[] APPLICATION_PLATFORM_PACKAGES = {
            "com.luxera.companion.application..",
    };

    /** §115: 内置应用住在 application.builtin 下 —— 这是"平台私有的实现细节"。 */
    private static final String BUILTIN_PACKAGES = "com.luxera.companion.application.builtin..";

    /** §118: AgentRuntime 里不该出现任何平台服务的直接依赖。 */
    private static final String AGENT_RUNTIME =
            "com.luxera.companion.runtime..";

    private static JavaClasses classes;

    @BeforeAll
    static void setup() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.luxera.companion");
    }

    // ───────────────────────────── §115 ─────────────────────────────

    /**
     * Chat Platform 只认识 Application Protocol(contracts + chat 契约), 不认识任何内置应用。
     * 它能开应用、能铸邀请, 全部经 SPI 端口 —— 一旦这条破了, "第三方上架要不要改聊天侧"
     * 的答案从"不用"变成"要", 而那正是 LAP 要消灭的世界。
     */
    @Test
    void chat_platform_does_not_know_any_builtin_application() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "com.luxera.companion.conversation..",
                        "com.luxera.companion.event..",
                        "com.luxera.companion.simulator..")
                .should().dependOnClassesThat()
                .resideInAPackage(BUILTIN_PACKAGES);
        rule.check(classes);
    }

    // ───────────────────────────── §116 ─────────────────────────────

    /**
     * 所有 Action 执行入口必须经过 ApplicationGateway(ActionGateway)。
     *
     * <p>合法链是 {@code Controller → ActionGateway → handler}: 幂等、两段式事务、审计、
     * 权限检查全在 gateway 里。注意 ActionGateway 与 ActionOutcome 恰好住在 action 包下,
     * 所以规则不是"web 不许碰 action 包" —— 而是 web 只许碰那两样, 不许碰 <b>handler
     * 侧</b>的东西: ActionHandler / ActionHandlerRegistry(找到并直调 handler)、
     * RemoteApplicationInvoker(绕过网关直接转发)。碰任何一样, 就是跳过了网关这一层。
     */
    @Test
    void action_execution_always_goes_through_the_gateway() {
        // handler 侧的东西: 找到/直调 handler、绕过网关直接转发。依赖任何一样 = 跳过了网关。
        String handlerSide = ".*\\.(ActionHandler|ActionHandlerRegistry|ActionHandlerContext|"
                + "ActionResolver|RemoteActionHandler|RemoteApplicationInvoker|"
                + "RemoteApplicationRegistrar|RemoteSignature)";
        noClasses()
                .that().resideInAPackage("com.luxera.companion.application.web..")
                .should().dependOnClassesThat().haveNameMatching(handlerSide)
                .check(classes);

        // 正面断言: 执行端点真的在走 gateway —— 一条"没人走"的门等于没有门。
        classes()
                .that().haveFullyQualifiedName(
                        "com.luxera.companion.application.web.LapActionController")
                .should().dependOnClassesThat().haveSimpleName("ActionGateway")
                .check(classes);
    }

    // ───────────────────────────── §117 ─────────────────────────────

    /**
     * 所有 Resource 写操作必须 Action → ResourceStore, 禁止 Controller →
     * ResourceRepository.save()。绕过 ResourceStore 的写会绕过 CAS 与版本推进 ——
     * "两个 principal 抢同一步棋, 输的那个拿到干净的 409"就没了。
     *
     * <p>注意 ResourceStore 自己可以(也必须)用 ResourceRepository —— 断言的只是
     * web 层不许跳过它。
     */
    @Test
    void resource_writes_always_go_through_the_resource_store() {
        // web 层根本不该看见仓库接口 —— 读资源走 ResourceStore, 写更是。
        ArchRule controllerBypass = noClasses()
                .that().resideInAPackage("com.luxera.companion.application.web..")
                .should().dependOnClassesThat().haveSimpleName("ResourceRepository");
        controllerBypass.check(classes);

        // 平台内其余部分也一样: ResourceRepository 的合法用户只有 resource 包(ResourceStore)
        // 与 repository 包(接口自己)。其它任何调用者都在跳过 CAS 与版本推进。
        ArchRule othersBypass = noClasses()
                .that().resideOutsideOfPackages(
                        "com.luxera.companion.application.resource..",
                        "com.luxera.companion.application.repository..")
                .and().resideInAnyPackage(APPLICATION_PLATFORM_PACKAGES)
                .should().dependOnClassesThat().haveSimpleName("ResourceRepository");
        othersBypass.check(classes);
    }

    // ───────────────────────────── §118 ─────────────────────────────

    /**
     * 所有 Agent 的 Application 动作必须经 ApplicationRuntimePort(contracts)或
     * ApplicationGateway, 禁止 AgentRuntime → ApplicationService 直连。
     * 数字人模块(digitalhuman)侧已有 pom 硬约束; 这里守的是 <b>runtime 包</b> ——
     * AgentRuntime 若直接 import 平台服务类, "加第二个游戏要改 DH"就回来了。
     */
    @Test
    void agent_actions_go_through_the_port_or_the_gateway() {
        // AgentRuntime 不得依赖 application-platform 的任何服务/仓储/web 类。
        // contracts 里的 ApplicationRuntimePort 是唯一合法通道(pom 已保证可见性),
        // 这里防的是"有人往 runtime 包里加了直连"。
        ArchRule rule = noClasses()
                .that().resideInAPackage(AGENT_RUNTIME)
                .should().dependOnClassesThat()
                .resideInAnyPackage(APPLICATION_PLATFORM_PACKAGES);
        rule.check(classes);
    }
}
