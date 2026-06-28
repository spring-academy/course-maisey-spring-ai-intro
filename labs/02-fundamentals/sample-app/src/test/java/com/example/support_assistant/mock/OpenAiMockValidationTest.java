package com.example.support_assistant.mock;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;

/**
 * Replays the workshop chat flows through the real {@link MockOpenAiServer} using the
 * application.properties configuration (mock-api-key, base-url http://localhost:8081/v1).
 *
 * Unlike {@link OpenAiRecordingTest} this test needs no OPENAI_API_KEY and runs on every build,
 * guarding the committed fixtures against drift. When the recording test runs first (it is
 * {@link Order @Order(1)}) it refreshes the classpath fixtures, so this validation exercises the
 * interactions recorded in the same run; otherwise it validates the fixtures already committed
 * under src/main/resources/mock.
 *
 * If a recorded mapping no longer matches a request, the mock returns 404 and the failing call
 * raises an exception, so {@link ChatFlows#exercise} fails this test.
 */
@Order(2)
@SpringBootTest
class OpenAiMockValidationTest {

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private ChatModel chatModel;

    @Value("classpath:/prompts/system-prompt.st")
    private Resource systemPrompt;

    @Test
    void replaysRecordedFlowsThroughMockServer() {
        ChatFlows.exercise(chatModel, chatClientBuilder, systemPrompt);
    }
}