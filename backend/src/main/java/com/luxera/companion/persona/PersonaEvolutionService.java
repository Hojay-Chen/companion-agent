package com.luxera.companion.persona;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipService;
import com.luxera.companion.usermodel.UserModelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 人格演化(设计文档 47/98 节): 由反思证据驱动,对人格 traits 做保守微调,生成新版本。
 * 流程: 证据 → LLM 提出调整 → 限幅(±0.05/次) → 校验 → 新版本。核心价值观与边界不可变。
 */
@Slf4j
@Service
public class PersonaEvolutionService {

    private static final String SYSTEM = """
            你是人格演化引擎。基于用户与伴侣相处的证据,提出对伴侣人格的保守微调。
            当前人格: %s

            相处证据: %s

            输出严格 JSON,不要输出其他内容:
            {
              "adjustments": [
                {"field":"personality.traits.warmth","delta":0.03,"reason":"为什么这样调"}
              ],
              "summary_note": "可选的人格描述更新"
            }
            规则:
            - delta 绝对值不超过 0.05,每次最多改 3 处
            - 只改有充分证据支撑的 traits,不要为了变而变
            - 绝不改变 values / boundaries / 身份
            """;

    private final PersonaService personaService;
    private final UserModelService userModelService;
    private final RelationshipService relationshipService;
    private final CompanionRepository companionRepo;
    private final LlmRouter llm;

    public PersonaEvolutionService(PersonaService personaService, UserModelService userModelService,
                                   RelationshipService relationshipService, CompanionRepository companionRepo,
                                   LlmRouter llm) {
        this.personaService = personaService;
        this.userModelService = userModelService;
        this.relationshipService = relationshipService;
        this.companionRepo = companionRepo;
        this.llm = llm;
    }

    /** 对所有伴侣执行人格演化 */
    @Transactional
    public List<String> runAll() {
        List<String> results = new ArrayList<>();
        for (Companion c : companionRepo.findAll()) {
            if (c.getDeletedAt() != null) continue;
            List<String> changes = evolve(c);
            if (!changes.isEmpty()) {
                results.add(c.getName() + ": " + String.join("; ", changes));
            }
        }
        return results;
    }

    /** 对单个伴侣执行人格演化,返回变更描述(空 = 无变化) */
    @Transactional
    public List<String> evolve(Companion c) {
        Persona persona = personaService.getActive(c.getId());
        List<String> changes = new ArrayList<>();
        if (persona == null) return changes;

        String evidence = buildEvidence(c, persona);
        String prompt = String.format(SYSTEM,
                personaSummary(persona),
                evidence.length() > 3000 ? evidence.substring(0, 3000) : evidence);

        try {
            var res = llm.structured(StructuredRequest.builder()
                    .task("persona-evolution")
                    .system(prompt)
                    .user("请基于以上证据提出人格微调。")
                    .temperature(0.3)
                    .build());
            JsonNode root = res.getJson();
            int applied = 0;
            for (JsonNode adj : root.path("adjustments")) {
                String field = adj.path("field").asText("");
                double rawDelta = adj.path("delta").asDouble(0);
                String reason = adj.path("reason").asText("");
                double delta = Math.max(-0.05, Math.min(0.05, rawDelta));
                if (field.startsWith("personality.traits.") && Math.abs(delta) >= 0.01) {
                    String trait = field.substring("personality.traits.".length());
                    if (persona.getPersonality() == null) persona.setPersonality(new Persona.Personality());
                    Map<String, Double> traits = persona.getPersonality().getTraits();
                    double cur = traits.getOrDefault(trait, 0.5);
                    double updated = clamp(cur + delta);
                    if (Math.abs(updated - cur) >= 0.01) {
                        traits.put(trait, updated);
                        changes.add(String.format("%s %s%.2f (%s)", trait, delta >= 0 ? "+" : "", delta, reason));
                        applied++;
                    }
                }
            }
            String summaryNote = root.path("summary_note").asText("");
            if (applied > 0) {
                if (StringUtils.hasText(summaryNote) && summaryNote.length() < 100
                        && persona.getPersonality() != null) {
                    persona.getPersonality().setSummary(summaryNote);
                }
                String reason = "人格演化: " + String.join("; ", changes);
                personaService.update(c.getId(), persona, reason, "evolution");
                log.info("[人格演化] {}: {}", c.getName(), reason);
            }
        } catch (Exception e) {
            log.warn("人格演化失败 companion={}: {}", c.getId(), e.getMessage());
        }
        return changes;
    }

    private String buildEvidence(Companion c, Persona persona) {
        StringBuilder sb = new StringBuilder();
        UserModelService.UserModelSummary summary = userModelService.summary(c.getUserId(), c.getId());
        append(sb, "用户事实", summary.facts());
        append(sb, "用户偏好", summary.preferences());
        append(sb, "用户习惯", summary.patterns());
        append(sb, "用户推测", summary.hypotheses());
        Relationship rel = relationshipService.find(c.getUserId(), c.getId());
        if (rel != null) {
            sb.append("关系: 阶段").append(rel.getRelationshipStage())
                    .append(",累计").append(rel.getMessageCount()).append("条消息,")
                    .append("熟悉度").append(round(rel.getFamiliarity()))
                    .append(",信任").append(round(rel.getTrust()))
                    .append(",亲密度").append(round(rel.getIntimacy()))
                    .append("。");
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String label, List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        sb.append(label).append(": ");
        for (String l : lines) sb.append(l).append(";");
        sb.append(" ");
    }

    private static String personaSummary(Persona p) {
        if (p.getPersonality() == null) return "";
        StringBuilder sb = new StringBuilder();
        if (p.getPersonality().getTraits() != null && !p.getPersonality().getTraits().isEmpty()) {
            sb.append("traits: ").append(p.getPersonality().getTraits()).append(";");
        }
        if (p.getPersonality().getSummary() != null) {
            sb.append("summary: ").append(p.getPersonality().getSummary());
        }
        return sb.toString();
    }

    private static double clamp(double v) {
        return Math.max(0.1, Math.min(0.95, v));
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
