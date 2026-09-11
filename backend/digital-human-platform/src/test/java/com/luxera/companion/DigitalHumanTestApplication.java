package com.luxera.companion;

import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.contracts.spi.SimulatorAccessPort;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Optional;

/**
 * Test-only bootstrap for the digital-human module.
 *
 * <p>In production this platform is launched by {@code bootstrap-app} alongside the chat platform,
 * which supplies the two ports below through their chat-side adapters. Here it is launched alone —
 * which is exactly the property worth testing: the digital human must live, think and act with no
 * chat platform on the classpath. The ports it cannot answer by itself are faked in-memory.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class DigitalHumanTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(DigitalHumanTestApplication.class, args);
    }

    /**
     * The chat platform owns conversations and messages. Module tests use an in-memory stand-in so
     * that a reply she writes can be read back; cross-platform behaviour is tested in
     * {@code bootstrap-app} against the real adapter.
     */
    @Bean
    ChatWorldPort inMemoryChatWorld() {
        return new InMemoryChatWorld();
    }

    /**
     * Device tokens are issued by the chat platform; with no chat platform present the device is
     * simply unknown, which is a state the connector already handles.
     */
    @Bean
    SimulatorAccessPort simulatorAccessPort() {
        return (deviceId, secret) -> Optional.empty();
    }
}
