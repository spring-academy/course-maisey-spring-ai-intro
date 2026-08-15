package com.example.support_assistant;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
class SupportAssistantService {

    private final ChatClient chatClient;

    SupportAssistantService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    SupportResponse generateResponse(String query) {
        return chatClient.prompt()
                .user(u -> u
                        .text("Answer the following question with a short, well-structured explanation: {question}")
                        .param("question", query))
                .call()
                .entity(SupportResponse.class);
    }
}
