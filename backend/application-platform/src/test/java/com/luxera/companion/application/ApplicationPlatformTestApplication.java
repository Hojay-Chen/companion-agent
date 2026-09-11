package com.luxera.companion.application;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Test-only bootstrap for the application platform.
 *
 * <p>In production this module is launched by {@code bootstrap-app} alongside the chat platform and
 * the digital human. Here it runs alone, which is the property worth testing: the platform can
 * discover, read and execute applications with neither of the other two present.
 *
 * <p>The two ports it cannot answer itself are faked: chat is where the SSE side of a game reaches
 * the browser, and the event sink is the digital human's door, which belongs to
 * {@code digital-human-platform} and is therefore absent by construction.
 */
@SpringBootApplication
public class ApplicationPlatformTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApplicationPlatformTestApplication.class, args);
    }

    /** 返回具体类型(而非端口类型), 测试才能直接注入并断言录到了什么。 */
    @Bean
    RecordingChatWorld recordingChatWorld() {
        return new RecordingChatWorld();
    }

    @Bean
    RecordingApplicationEventSink recordingEventSink() {
        return new RecordingApplicationEventSink();
    }
}
