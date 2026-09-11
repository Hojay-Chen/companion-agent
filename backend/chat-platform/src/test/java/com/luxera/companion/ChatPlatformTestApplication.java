package com.luxera.companion;

import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.spi.CompanionDirectoryPort;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

/**
 * Test-only bootstrap for the chat module.
 *
 * <p>In production the chat platform is launched by {@code bootstrap-app} together with the
 * digital-human platform. Here it is launched alone, which is exactly the property worth testing:
 * the module must be able to start without the digital human. The one thing it cannot answer by
 * itself — "what is this companion called" — is stubbed below.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class ChatPlatformTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatPlatformTestApplication.class, args);
    }

    /**
     * The chat platform never implements {@link CompanionDirectoryPort} — that is the digital
     * human's job. Module tests only need an id-echoing stand-in.
     */
    @Bean
    CompanionDirectoryPort testCompanionDirectory() {
        return new CompanionDirectoryPort() {
            @Override
            public CompanionRef requireOwned(String userId, String companionId) {
                return new CompanionRef(companionId, "测试伴侣", companionId);
            }

            @Override
            public void onUserMessage(String userId, String companionId, String conversationId,
                                      List<MessageView> messages) {
                // 模块测试里没有数字人平台: 消息落库即结束
            }
        };
    }
}
