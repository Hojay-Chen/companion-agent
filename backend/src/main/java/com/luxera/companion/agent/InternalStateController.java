package com.luxera.companion.agent;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.experience.Experience;
import com.luxera.companion.experience.ExperienceService;
import com.luxera.companion.openloop.OpenLoop;
import com.luxera.companion.openloop.OpenLoopService;
import com.luxera.companion.persona.CompanionService;
import com.luxera.companion.thought.Thought;
import com.luxera.companion.thought.ThoughtService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内部状态读取(用户视角, 不暴露数值面板)。
 * 设计文档 V2.0 §30: 普通用户看到"她最近怎么样/未完成的事/经历", 而不是 mood=0.63。
 */
@RestController
@RequestMapping("/api/companions/{companionId}")
public class InternalStateController {

    private final ThoughtService thoughtService;
    private final OpenLoopService openLoopService;
    private final ExperienceService experienceService;
    private final CompanionService companionService;
    private final CurrentUser currentUser;

    public InternalStateController(ThoughtService thoughtService, OpenLoopService openLoopService,
                                   ExperienceService experienceService, CompanionService companionService,
                                   CurrentUser currentUser) {
        this.thoughtService = thoughtService;
        this.openLoopService = openLoopService;
        this.experienceService = experienceService;
        this.companionService = companionService;
        this.currentUser = currentUser;
    }

    /** 高价值想法(用户视角: "她最近在想什么"的温和呈现) */
    @GetMapping("/thoughts")
    public List<Thought> thoughts(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return thoughtService.activeThoughts(companionId).stream()
                .filter(t -> t.getStrength() >= 0.4)
                .limit(8)
                .toList();
    }

    /** 未完成事项(用户可查看/关闭) */
    @GetMapping("/open-loops")
    public List<OpenLoop> openLoops(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return openLoopService.activeLoops(companionId);
    }

    /** 最近经历(用户视角) */
    @GetMapping("/experiences")
    public List<Experience> experiences(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return experienceService.list(companionId);
    }
}
