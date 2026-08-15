package com.example.support_assistant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SupportAssistantConfiguration {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
            @Value("classpath:/prompts/system-prompt.st") Resource systemPrompt) {
        return builder
                .defaultSystem(systemPrompt)
                .defaultAdvisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .build();
    }
}
