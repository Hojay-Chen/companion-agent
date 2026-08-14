package com.luxera.companion.proactive;

import com.luxera.companion.persona.PersonaEvolutionService;
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
    private final PersonaEvolutionService personaEvolutionService;

    public AdminController(ReflectionService reflectionService, ProactiveEngine proactiveEngine,
                           BirthdayService birthdayService, PersonaEvolutionService personaEvolutionService) {
        this.reflectionService = reflectionService;
        this.proactiveEngine = proactiveEngine;
        this.birthdayService = birthdayService;
        this.personaEvolutionService = personaEvolutionService;
    }

    @PostMapping("/reflection/run")
    public Map<String, Object> runReflection() {
        return Map.of("records", reflectionService.runAllDaily());
    }

    @PostMapping("/reflection/run-weekly")
    public Map<String, Object> runWeeklyReflection() {
        return Map.of("records", reflectionService.runAllWeekly());
    }

    @PostMapping("/persona/evolve")
    public Map<String, Object> evolvePersona() {
        List<String> changes = personaEvolutionService.runAll();
        return Map.of("changes", changes, "count", changes.size());
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
