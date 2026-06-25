package com.example.support_assistant;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
class SupportAssistantController {

    private final SupportAssistantService service;

    SupportAssistantController(SupportAssistantService service) {
        this.service = service;
    }

    // curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about Spring AI"
    @GetMapping(path = "/api/v{version}/chat")
    String chat(@RequestParam String query) {
        return service.generateResponse(query);
    }
}
