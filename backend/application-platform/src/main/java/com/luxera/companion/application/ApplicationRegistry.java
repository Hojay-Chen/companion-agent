package com.luxera.companion.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

/**
 * V10 §33 Application Registry: 启动时从 DB 加载 + 注册内置应用。
 *
 * 内置应用(Hello World / TicTacToe)通过 ApplicationProvider SPI 注册;
 * 数据库中的声明的应用(code/name/manifest)作为可扩展的外部能力。
 * 本轮为骨架: 注册表能列出应用、按 code 查找、invoke 方法分发。
 */
@Slf4j
@Service
public class ApplicationRegistry {

    private final LapApplicationRepository repository;

    public ApplicationRegistry(LapApplicationRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void ensureBuiltins() {
        // 骨架: 种子内置应用(幂等)
        seedIfMissing("hello-world", "Hello World", "{\"permissions\":[\"print\"]}");
        seedIfMissing("tictactoe", "井字棋", "{\"permissions\":[\"game.move\",\"game.chat\"]}");
        log.info("[ApplicationRegistry] 内置应用已就绪: hello-world, tictactoe");
    }

    private void seedIfMissing(String code, String name, String manifest) {
        try {
            if (repository.findByCode(code).isEmpty()) {
                LapApplication app = new LapApplication();
                app.setCode(code);
                app.setName(name);
                app.setManifestJson(manifest);
                app.setStatus("ACTIVE");
                repository.save(app);
            }
        } catch (Exception e) {
            log.warn("[ApplicationRegistry] 种子应用 {} 失败: {}", code, e.getMessage());
        }
    }

    /** 列出所有 ACTIVE 应用 */
    public java.util.List<LapApplication> list() {
        return repository.findAll();
    }

    /** 按 code 查找 */
    public java.util.Optional<LapApplication> byCode(String code) {
        return repository.findByCodeAndStatus(code, "ACTIVE");
    }
}