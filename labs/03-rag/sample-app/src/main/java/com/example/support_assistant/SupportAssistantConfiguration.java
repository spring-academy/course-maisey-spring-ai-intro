package com.example.support_assistant;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

@Configuration
class SupportAssistantConfiguration {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder,
                          @Value("classpath:/prompts/system-prompt.st") Resource systemPrompt) {
        return builder.defaultSystem(systemPrompt).build();
    }
}
