package com.example.support_assistant.mock;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;

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
 * There is one test per lab flow, mirroring {@link OpenAiRecordingTest}, so a single flow can be
 * replayed on its own with {@code -Dtest=OpenAiMockValidationTest#planAndExecute}.
 *
 * If a recorded mapping no longer matches a request, the mock returns 404 and the failing call
 * raises an exception, so the flow fails this test.
 */
@Order(2)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest
class OpenAiMockValidationTest {

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private EmbeddingModel embeddingModel;

    private ChatFlows flows;

    @BeforeAll
    void buildSharedSetup() throws IOException {
        this.flows = new ChatFlows(chatModel, chatClientBuilder, embeddingModel);
    }

    @Test
    @Order(1)
    void fundamentals() {
        flows.fundamentals();
    }

    @Test
    @Order(2)
    void advisors() {
        flows.advisors();
    }

    @Test
    @Order(3)
    void rag() {
        flows.rag();
    }

    @Test
    @Order(4)
    void toolCalling() {
        flows.toolCalling();
    }

    @Test
    @Order(5)
    void testing() {
        flows.testing();
    }

    @Test
    @Order(6)
    void mcp() {
        flows.mcp();
    }

    @Test
    @Order(7)
    void toolSearch() {
        flows.toolSearch();
    }

    @Test
    @Order(8)
    void evaluatorOptimizer() {
        flows.evaluatorOptimizer();
    }

    @Test
    @Order(9)
    void agentSkills() {
        flows.agentSkills();
    }

    @Test
    @Order(10)
    void planAndExecute() {
        flows.planAndExecute();
    }

    @Test
    @Order(11)
    void humanInTheLoop() {
        flows.humanInTheLoop();
    }
}
