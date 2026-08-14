package com.luxera.companion.proactive;

import com.luxera.companion.reflection.ReflectionService;
import com.luxera.companion.tool.BirthdayService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 手动触发验收用的管理端点 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ReflectionService reflectionService;
    private final ProactiveEngine proactiveEngine;
    private final BirthdayService birthdayService;

    public AdminController(ReflectionService reflectionService, ProactiveEngine proactiveEngine,
                           BirthdayService birthdayService) {
        this.reflectionService = reflectionService;
        this.proactiveEngine = proactiveEngine;
        this.birthdayService = birthdayService;
    }

    @PostMapping("/reflection/run")
    public Map<String, Object> runReflection() {
        return Map.of("records", reflectionService.runAllDaily());
    }

    @PostMapping("/proactive/run")
    public Map<String, Object> runProactive() {
        List<String> actions = proactiveEngine.run();
        return Map.of("actions", actions, "count", actions.size());
    }

    @PostMapping("/birthday/ensure")
    public Map<String, Object> ensureBirthdays() {
        birthdayService.ensureBirthdayReminders();
        return Map.of("success", true);
    }
}
