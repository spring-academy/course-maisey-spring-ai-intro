package com.example.support_assistant.mock;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat flows the workshop relies on, exercised both when recording fixtures
 * ({@link OpenAiRecordingTest}) and when replaying them against the mock
 * ({@link OpenAiMockValidationTest}). Keeping the calls in one place guarantees the
 * recorded interactions and the validated interactions stay identical.
 */
final class ChatFlows {

    private ChatFlows() {
    }

    private static final String SYSTEM_PROMPT =
            "You are a support agent for the Spring framework. Answer clearly and always include a link to the "
                    + "relevant official docs when one exists, never inventing URLs.";

    static void exercise(ChatModel chatModel, ChatClient.Builder chatClientBuilder, EmbeddingModel embeddingModel) throws IOException {
        // Lab 02-fundamentals
        assertNotBlank(chatModel.call("Tell me about Spring AI"));

        var chatClient = chatClientBuilder.build();

        assertNotBlank(chatClient.prompt()
                .user("Tell me about Spring AI")
                .call()
                .content());

        assertNotBlank(chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user("Tell me about Spring AI")
                .call()
                .content());

        assertNotBlank(chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user("Answer the following question with a short, well-structured explanation: Tell me about Spring AI")
                .call()
                .content());

        var chunks = chatClient.prompt()
                .user("Tell me about Spring AI")
                .stream()
                .content()
                .collectList()
                .block();
        assertNotNull(chunks, "streamed content");
        assertFalse(chunks.isEmpty(), "streamed content");

        assertNotBlank(chatClient.prompt()
                .user(u -> u
                        .text("Answer the following question with a short, well-structured explanation: {question}")
                        .param("question", "Tell me about Spring AI"))
                .call()
                .content());

        var systemPrompt = new ClassPathResource("/prompts/system-prompt.st");
        var chatClientWithSystemPrompt = chatClientBuilder.defaultSystem(systemPrompt).build();

        assertNotBlank(chatClientWithSystemPrompt.prompt()
                .user("Answer the following question with a short, well-structured explanation: Tell me about Spring AI")
                .call()
                .content());

        var jsonStringResponse = chatClientWithSystemPrompt.prompt()
                .system("""
                    You are a Spring support classifier.
                    Reply only with JSON in this form:
                    {"category":"...","answer":"..."}
                    The category must be one of: TECHNICAL, BILLING, SECURITY, GENERAL.
                    Examples:
                    - "Why was I billed twice?"     -> {"category":"BILLING","answer":"..."}
                    - "How do I rotate my API key?" -> {"category":"SECURITY","answer":"..."}
                    """)
                .user("Tell me about Spring AI")
                .call()
                .content();
        assertNotNull(jsonStringResponse, "json content");
        assertTrue(jsonStringResponse.contains("category") && jsonStringResponse.contains("answer"), "json content contains relevant attributes");

        var chatMemory = MessageWindowChatMemory.builder().build();
        var memoryClient = chatClientWithSystemPrompt.mutate()
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
        var conversationId = "123e4567-e89b-12d3-a456-426614174000";

        SupportResponse response = memoryClient.prompt()
                .user(u -> u
                        .text("Answer the following question with a short, well-structured explanation: {question}")
                        .param("question", "Tell me about Spring AI"))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .entity(SupportResponse.class);
        assertNotNull(response, "structured response");
        assertNotNull(response.category(), "structured response category");
        assertNotBlank(response.answer());
        
        SupportResponse followUp = memoryClient.prompt()
                .user(u -> u
                        .text("Answer the following question with a short, well-structured explanation: {question}")
                        .param("question", "What did I just ask you about?"))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .entity(SupportResponse.class);
        assertNotNull(followUp, "memory follow-up response");
        assertNotBlank(followUp.answer());

        // Lab 03-rag
        var vectorStore = SimpleVectorStore.builder(embeddingModel).build();

        var knowledgeFiles = new PathMatchingResourcePatternResolver().getResources("classpath:knowledge-base/*.md");

        var documentReaderConfig = MarkdownDocumentReaderConfig.builder()
                .withHorizontalRuleCreateDocument(true)
                .withIncludeBlockquote(true)
                .withIncludeCodeBlock(true)
                .build();
        var documentReader = new MarkdownDocumentReader(Arrays.asList(knowledgeFiles), documentReaderConfig);
        List<Document> documents = documentReader.read();

        var tokenTextSplitter = TokenTextSplitter.builder()
                .withMinChunkLengthToEmbed(25)
                .build();
        var splitDocuments = tokenTextSplitter.apply(documents);
        vectorStore.add(splitDocuments);

        var ragSearchRequest = SearchRequest.builder().topK(4).similarityThreshold(0.4).build();
        QuestionAnswerAdvisor ragAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(ragSearchRequest)
                .build();

        for (var question : List.of(
                "Does VMware Tanzu Spring provide commercial support for Micrometer?",
                "Tell me about breaking changes in Spring Framework 7"
                )) {
            response = chatClientWithSystemPrompt.prompt()
                    .user(u -> u
                            .text("Answer the following question with a short, well-structured explanation: {question}")
                            .param("question", question))
                    .advisors(ragAdvisor)
                    .call()
                    .entity(SupportResponse.class);
            assertNotNull(response, "structured response");
            assertNotNull(response.category(), "structured response category");
            assertNotBlank(response.answer());
        }

        var ragPrompt = new ClassPathResource("/prompts/rag-prompt.st");
        var ragPromptTemplate = PromptTemplate.builder().resource(ragPrompt).build();
        ragAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(ragSearchRequest)
                .promptTemplate(ragPromptTemplate)
                .build();
        for (var question : List.of(
                "Does VMware Tanzu Spring provide commercial support for Micrometer?",
                "Tell me about breaking changes in Spring Framework 7"
        )) {
            response = chatClientWithSystemPrompt.prompt()
                    .user(u -> u
                            .text("Answer the following question with a short, well-structured explanation: {question}")
                            .param("question", question))
                    .advisors(ragAdvisor)
                    .call()
                    .entity(SupportResponse.class);
            assertNotNull(response, "structured response");
            assertNotNull(response.category(), "structured response category");
            assertNotBlank(response.answer());
        }

        // Lab 03-tool-calling
        // Share a single Tools instance across the flow so a ticket created by one
        // query is visible to the follow-up queries, mirroring the app's DB-backed
        // SupportTicketService that persists tickets across requests.
        var tools = new Tools();
        for (var question : List.of(
                "Open a high priority ticket to request a trial for VMware Tanzu Spring",
                "Provide me an overview of all open support tickets",
                "Does VMware Tanzu Spring provide support for Spring Boot 2.7? If yes, open a ticket to request a trial"
        )) {
            response = chatClientWithSystemPrompt.prompt()
                    .user(u -> u
                            .text("Answer the following question with a short, well-structured explanation: {question}")
                            .param("question", question)
                    )
                    .advisors(ragAdvisor)
                    .tools(tools)
                    .call()
                    .entity(SupportResponse.class);
        }
    }

    private static void assertNotBlank(String content) {
        assertNotNull(content, "chat content");
        assertFalse(content.isBlank(), "chat content");
    }

    enum SupportCategory {
        TECHNICAL,
        BILLING,
        SECURITY,
        GENERAL
    }

    record SupportResponse(
            @JsonPropertyDescription("The category of the support question: TECHNICAL, BILLING, SECURITY, or GENERAL")
            SupportCategory category,

            @JsonPropertyDescription("The helpful answer to the customer's question")
            String answer
    ) { }

    record SupportTicket(@Nullable Long id, String summary, SupportCategory category, Priority priority,
                         Status status, LocalDateTime createdAt) {

        SupportTicket { }

        SupportTicket(String summary, SupportCategory category, Priority priority) {
            this(null, summary, category, priority, Status.OPEN, LocalDateTime.now());
        }

        SupportTicket withId(Long id) {
            return new SupportTicket(id, summary, category, priority, status, createdAt);
        }

        enum Status {
            OPEN, IN_PROGRESS, CLOSED
        }

        enum Priority {
            LOW, MEDIUM, HIGH, CRITICAL
        }
    }

    static class Tools {

        private SupportTicket ticket;

        @Tool(description = "Create a new support ticket. Use this when the user explicitly requests to create, open, or file a support ticket.")
        SupportTicket createTicket(
                @ToolParam(description = "Brief summary of the issue (max 100 chars)") String summary,
                @ToolParam(description = "The category of the issue") SupportCategory category,
                @ToolParam(description = "The priority of the support ticket") SupportTicket.Priority priority) {
            ticket = new SupportTicket(1L, summary, category, priority, SupportTicket.Status.OPEN, LocalDateTime.now());
            return ticket;
        }

        @Tool(description = "List all support tickets")
        List<SupportTicket> retrieveTickets() {
            return ticket == null ? Collections.emptyList() : List.of(ticket);
        }

        @Tool(description = "List all support tickets that are not yet resolved")
        List<SupportTicket> retrieveOpenTickets() {
            return ticket == null ? Collections.emptyList() : List.of(ticket);
        }
    }
}