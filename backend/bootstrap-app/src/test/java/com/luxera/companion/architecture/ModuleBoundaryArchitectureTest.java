package com.luxera.companion.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 三平台边界的结构性守卫 —— 放在 bootstrap-app, 因为这里是唯一同时看得见所有模块的地方。
 *
 * <p>边界靠两件事保证, 二者都要成立:
 * <ol>
 *   <li><b>classpath</b>(硬约束): 模块 pom 不声明对方的依赖, 对方就不在编译期可见。
 *       {@code scripts/check-v10.sh} 第 3 步检查 pom 依赖图。</li>
 *   <li><b>本文的 ArchUnit 规则</b>(结构断言): 有人通过加依赖"绕开"边界时, 这里立刻失败 ——
 *       比 grep 更强, 因为它看的是编译后的字节码, 而不是源码里的 import 文本。</li>
 * </ol>
 *
 * <p><b>包名必须写全限定前缀</b>({@code com.luxera.companion.conversation..} 而不是 {@code ..conversation..})。
 * 两个平台各自都有叫 conversation / event / state / memory 的包, 用 {@code ..conversation..} 这种
 * 通配写法会把 digital-human 自己的 {@code digitalhuman.conversation} 也算进去, 规则立刻变成
 * 几百条假阳性, 最后只能被删掉 —— 那才是最坏的结果: 一个看起来在守边界、其实什么都守不住的空壳。
 *
 * <p>顶层包之间的循环依赖在这个 Codebase 里是既成事实(拆分前就存在, 与本次拆分无关),
 * 所以这里不做循环断言 —— 与其写一条注定失败或注定被放宽的规则, 不如不写。
 * 模块层面的依赖方向由上面两条保证。
 */
class ModuleBoundaryArchitectureTest {

    /** chat-platform 拥有的顶层包(见 scripts/check-v10.sh 的自动推导) */
    private static final String[] CHAT_PLATFORM_PACKAGES = {
            "com.luxera.companion.conversation..", "com.luxera.companion.event..",
            "com.luxera.companion.simulator..",
    };

    /** digital-human-platform 拥有的顶层包 */
    private static final String[] DIGITAL_HUMAN_PACKAGES = {
            "com.luxera.companion.agent..", "com.luxera.companion.appraisal..",
            "com.luxera.companion.attention..", "com.luxera.companion.behavior..",
            "com.luxera.companion.cognition..", "com.luxera.companion.cognitive..",
            "com.luxera.companion.digitalhuman..", "com.luxera.companion.emotion..",
            "com.luxera.companion.eval..", "com.luxera.companion.experience..",
            "com.luxera.companion.intention..", "com.luxera.companion.interaction..",
            "com.luxera.companion.life..", "com.luxera.companion.llm..",
            "com.luxera.companion.memory..", "com.luxera.companion.openloop..",
            "com.luxera.companion.person..", "com.luxera.companion.persona..",
            "com.luxera.companion.phone..", "com.luxera.companion.plan..",
            "com.luxera.companion.proactive..", "com.luxera.companion.reality..",
            "com.luxera.companion.reflection..", "com.luxera.companion.relationship..",
            "com.luxera.companion.runtime..", "com.luxera.companion.selfmodel..",
            "com.luxera.companion.sleep..", "com.luxera.companion.state..",
            "com.luxera.companion.thought..", "com.luxera.companion.tool..",
            "com.luxera.companion.usermodel..", "com.luxera.companion.world..",
    };

    /**
     * application-platform 拥有的顶层包。只有一个 —— 整个模块都在
     * {@code com.luxera.companion.application} 之下, 这也是 check-v10.sh 能从目录结构自动推导
     * 出归属的原因。写全限定前缀的理由同 {@link #DIGITAL_HUMAN_PACKAGES}:
     * {@code ..application..} 会把 {@code contracts.application} 一起吞掉。
     */
    private static final String[] APPLICATION_PLATFORM_PACKAGES = {
            "com.luxera.companion.application..",
    };

    private static JavaClasses classes;

    @BeforeAll
    static void setup() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.luxera.companion");
    }

    @Test
    void chat_platform_does_not_depend_on_digital_human() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(CHAT_PLATFORM_PACKAGES)
                .should().dependOnClassesThat().resideInAnyPackage(DIGITAL_HUMAN_PACKAGES);
        rule.check(classes);
    }

    @Test
    void digital_human_does_not_depend_on_chat_platform() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(DIGITAL_HUMAN_PACKAGES)
                .should().dependOnClassesThat().resideInAnyPackage(CHAT_PLATFORM_PACKAGES);
        rule.check(classes);
    }

    /**
     * 数字人只通过 {@code contracts} 里的 {@code ApplicationRuntimePort} 看见应用 ——
     * 它不认识任何一个具体应用, 也不认识承载它们的那个模块。
     */
    @Test
    void digital_human_does_not_depend_on_application_platform() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(DIGITAL_HUMAN_PACKAGES)
                .should().dependOnClassesThat().resideInAnyPackage(APPLICATION_PLATFORM_PACKAGES);
        rule.check(classes);
    }

    @Test
    void chat_platform_does_not_depend_on_application_platform() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(CHAT_PLATFORM_PACKAGES)
                .should().dependOnClassesThat().resideInAnyPackage(APPLICATION_PLATFORM_PACKAGES);
        rule.check(classes);
    }

    /**
     * 应用平台是宿主, 不是客人: 它可以看见 contracts, 但不许反过来依赖两个平台中的任何一个。
     * 应用事件进入数字人的唯一通道是 DH 侧实现 {@code ApplicationEventSink} —— 单向门。
     */
    @Test
    void application_platform_depends_on_neither_platform() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(APPLICATION_PLATFORM_PACKAGES)
                .should().dependOnClassesThat()
                .resideInAnyPackage(CHAT_PLATFORM_PACKAGES)
                .orShould().dependOnClassesThat()
                .resideInAnyPackage(DIGITAL_HUMAN_PACKAGES);
        rule.check(classes);
    }

    @Test
    void contracts_depend_on_no_platform() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.luxera.companion.contracts..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(CHAT_PLATFORM_PACKAGES)
                .orShould().dependOnClassesThat()
                .resideInAnyPackage(DIGITAL_HUMAN_PACKAGES)
                .orShould().dependOnClassesThat()
                .resideInAnyPackage(APPLICATION_PLATFORM_PACKAGES);
        rule.check(classes);
    }
}
