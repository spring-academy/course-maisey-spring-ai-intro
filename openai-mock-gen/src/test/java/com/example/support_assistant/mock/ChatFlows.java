package com.example.support_assistant.mock;

import com.example.support_assistant.advisor.AskUserQuestionToolAdvisor;
import com.example.support_assistant.advisor.SelfRefineEvaluationAdvisor;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springaicommunity.agent.tools.ShellTools;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.index.vectorstore.VectorToolIndex;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chat flows the workshop relies on, exercised both when recording fixtures
 * ({@link OpenAiRecordingTest}) and when replaying them against the mock
 * ({@link OpenAiMockValidationTest}). Keeping the calls in one place guarantees the
 * recorded interactions and the validated interactions stay identical.
 */
final class ChatFlows {

    private static final String SYSTEM_PROMPT =
            "You are a support agent for the Spring framework. Answer clearly and always include a link to the "
                    + "relevant official docs when one exists, never inventing URLs.";

    private static final String USER_PROMPT =
            "Answer the following question with a short, well-structured explanation: {question}";

    private static final String CONVERSATION_ID = "123e4567-e89b-12d3-a456-426614174000";

    // What the cve-lookup.sh of the agent skill prints for org.springframework.boot:spring-boot 3.2.4.
    private static final String CVE_LOOKUP_RESULT = """
            state=AFFECTED package=org.springframework.boot:spring-boot version=3.2.4 count=2
              id=CVE-2024-38807 severity=MODERATE fixed=3.1.13,3.2.9,3.3.3 summary=Signature forgery in Spring Boot's loader
              id=CVE-2025-22235 severity=MODERATE fixed=3.3.11,3.4.5 summary=EndpointRequest.to() creates a wrong matcher for an actuator endpoint
            """;

    private final ChatModel chatModel;
    private final ChatClient.Builder chatClientBuilder;
    private final ChatClient chatClient;
    private final ChatClient chatClientWithSystemPrompt;
    private final ChatClient promptBasedEntityClient;
    private final ChatClient memoryClient;
    private final ChatClient mcpChatClient;
    private final ChatClient skillsChatClient;
    private final ChatClient planningChatClient;

    private final SimpleVectorStore vectorStore;
    private final SearchRequest ragSearchRequest;
    private final QuestionAnswerAdvisor ragAdvisorWithDefaultPrompt;
    private final QuestionAnswerAdvisor ragAdvisor;
    private final QuestionAnswerAdvisor knowledgeBaseRagAdvisor;
    private final SelfRefineEvaluationAdvisor selfRefineEvaluationAdvisor;

    private final Tools tools = new Tools();
    private final List<String> invokedTools = new ArrayList<>();
    private final List<TodoWriteTool.Todos> writtenPlans = new ArrayList<>();

    /**
     * Builds everything the flows share. Every request this makes is an embedding of the knowledge
     * base, so a single flow can be recorded or replayed on its own: the shared setup either
     * records those embeddings once or is served by the stubs a previous run left behind.
     */
    ChatFlows(ChatModel chatModel, ChatClient.Builder chatClientBuilder, EmbeddingModel embeddingModel)
            throws IOException {
        this.chatModel = chatModel;
        this.chatClientBuilder = chatClientBuilder;
        this.chatClient = chatClientBuilder.build();

        var systemPrompt = new ClassPathResource("/prompts/system-prompt.st");
        // Build through mutate() so the shared chatClientBuilder keeps no default system prompt.
        // The Lab 04 RelevancyEvaluator and the Lab 05 judge derive their own builders from it, and
        // the sample-app injects a fresh builder there, so it must stay free of a system message
        // for the fixtures to match.
        // The sample-app enables native structured output as a default advisor from the
        // structured-output lab onward, so every .entity() call below records with it too.
        // (It is a no-op for the .content() and .stream() calls, which do no structured conversion.)
        this.chatClientWithSystemPrompt = chatClientBuilder.build().mutate()
                .defaultSystem(systemPrompt)
                .defaultAdvisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .build();
        this.promptBasedEntityClient = chatClientBuilder.build().mutate()
                .defaultSystem(systemPrompt)
                .build();
        // mutate() does not carry over the native structured-output advisor param, so re-apply it
        // here to match the sample-app's config bean (which enables it on every call).
        this.memoryClient = this.chatClientWithSystemPrompt.mutate()
                .defaultAdvisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(MessageWindowChatMemory.builder().build()).build())
                .build();

        this.vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        var knowledgeFiles = new PathMatchingResourcePatternResolver().getResources("classpath:knowledge-base/*.md");
        var documentReaderConfig = MarkdownDocumentReaderConfig.builder()
                .withHorizontalRuleCreateDocument(true)
                .withIncludeBlockquote(true)
                .withIncludeCodeBlock(true)
                .build();
        List<Document> documents = new MarkdownDocumentReader(Arrays.asList(knowledgeFiles), documentReaderConfig)
                .read();
        var splitDocuments = TokenTextSplitter.builder().withMinChunkLengthToEmbed(25).build().split(documents);
        this.vectorStore.add(splitDocuments);

        this.ragSearchRequest = SearchRequest.builder().topK(4).similarityThreshold(0.4).build();
        this.ragAdvisorWithDefaultPrompt = QuestionAnswerAdvisor.builder(this.vectorStore)
                .searchRequest(this.ragSearchRequest)
                .build();
        var ragPromptTemplate = PromptTemplate.builder()
                .resource(new ClassPathResource("/prompts/rag-prompt.st"))
                .build();
        this.ragAdvisor = QuestionAnswerAdvisor.builder(this.vectorStore)
                .searchRequest(this.ragSearchRequest)
                .promptTemplate(ragPromptTemplate)
                .build();

        // The MCP client adds the remote spring-releases tool as a default tool on the ChatClient.
        // No MCP server runs while recording, so we stand in a ToolCallback with the same name and
        // schema the client would import (the client imports the tool under its plain name,
        // fetchReleasesInfo, without a connection prefix). The recorder strips tool-result content
        // from the request matcher, so a canned return value is enough for the fixtures to match.
        this.mcpChatClient = this.chatClientWithSystemPrompt.mutate()
                .defaultAdvisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .defaultTools(springReleasesToolCallback())
                .build();

        // The tool search advisor is turned off in the experimental lab, so the sample app never
        // indexes its tools into the vector store and the RAG advisor only ever retrieves knowledge
        // base chunks. The store above collects the tool documents of the tool search lab, and
        // those outrank the knowledge base for anything that sounds like a ticket, so the
        // experimental flows need a store that holds the knowledge base alone. The chunks are the
        // same, so embedding them again records no new fixtures.
        var knowledgeBaseVectorStore = SimpleVectorStore.builder(embeddingModel).build();
        knowledgeBaseVectorStore.add(splitDocuments);
        this.knowledgeBaseRagAdvisor = QuestionAnswerAdvisor.builder(knowledgeBaseVectorStore)
                .searchRequest(this.ragSearchRequest)
                .promptTemplate(ragPromptTemplate)
                .build();

        this.selfRefineEvaluationAdvisor = SelfRefineEvaluationAdvisor.builder()
                .chatClientBuilder(chatClientBuilder.build().mutate()
                        .defaultAdvisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
                        .defaultOptions(ChatOptions.builder().temperature(0.0)))
                .build();

        // The Skill tool carries the front matter of every skill it found in its description, and
        // that description is part of the recorded request. The SKILL.md under
        // src/test/resources/skills must therefore stay identical to the one the lab writes into
        // the sample app.
        var skillsTool = recording(SkillsTool.builder()
                .addSkillsResource(new ClassPathResource("skills"))
                .build(), this.invokedTools);
        // The shell tools run whatever the model asks for on this machine, and the script of the
        // skill calls out to osv.dev. Keep the tool definitions the sample app sends, but answer
        // every call with a canned lookup, so recording and the offline validation run stay
        // hermetic. The recorder strips tool-result content from the request matcher, so only the
        // definitions have to match, not the results.
        var shellToolCallbacks = cannedToolCallbacks(ShellTools.builder().build(), CVE_LOOKUP_RESULT)
                .stream()
                .map(callback -> recording(callback, this.invokedTools))
                .toList();
        this.skillsChatClient = this.chatClientWithSystemPrompt.mutate()
                .defaultAdvisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .defaultTools(Stream.concat(
                        Stream.of(springReleasesToolCallback(), skillsTool),
                        shellToolCallbacks.stream()).toArray())
                .build();

        // The todo tool only keeps the plan in memory, so the real one can run here.
        var todoWriteTool = TodoWriteTool.builder().todoEventHandler(this.writtenPlans::add).build();
        this.planningChatClient = this.chatClientWithSystemPrompt.mutate()
                .defaultAdvisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .defaultTools(Stream.concat(
                        Stream.of(springReleasesToolCallback(), skillsTool, todoWriteTool),
                        shellToolCallbacks.stream()).toArray())
                .build();
    }

    /** Lab 02-fundamentals. */
    void fundamentals() {
        assertNotBlank(chatModel.call("Tell me about Spring AI"));

        var userPromptTemplate = PromptTemplate.builder()
                .template(USER_PROMPT)
                .variables(Map.of("question", "Tell me about Spring AI"))
                .build();
        var prompt = new Prompt(
                List.of(new SystemMessage(SYSTEM_PROMPT), userPromptTemplate.createMessage()),
                OpenAiChatOptions.builder().model("gpt-5.4-mini").temperature(0.0).build());
        assertNotNull(chatModel.call(prompt));

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
                .user(u -> u.text(USER_PROMPT).param("question", "Tell me about Spring AI"))
                .call()
                .content());
    }

    /** Lab 02-advisors, the system prompt, the structured output and the chat memory steps. */
    void advisors() {
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

        // The "Return the Record" step, before native output is enabled. The ChatClient has only a
        // default system prompt, so .entity() is prompt-based here.
        SupportResponse promptBasedResponse = promptBasedEntityClient.prompt()
                .user(u -> u.text(USER_PROMPT).param("question", "Tell me about Spring AI"))
                .call()
                .entity(SupportResponse.class);
        assertNotNull(promptBasedResponse, "prompt-based structured response");
        assertNotBlank(promptBasedResponse.answer());

        SupportResponse response = memoryClient.prompt()
                .user(u -> u.text(USER_PROMPT).param("question", "Tell me about Spring AI"))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
                .call()
                .entity(SupportResponse.class);
        assertStructured(response);

        SupportResponse followUp = memoryClient.prompt()
                .user(u -> u.text(USER_PROMPT).param("question", "What did I just ask you about?"))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
                .call()
                .entity(SupportResponse.class);
        assertNotNull(followUp, "memory follow-up response");
        assertNotBlank(followUp.answer());
    }

    /** Lab 03-rag. */
    void rag() {
        for (var advisor : List.of(ragAdvisorWithDefaultPrompt, ragAdvisor)) {
            for (var question : List.of(
                    "Does VMware Tanzu Spring provide commercial support for Micrometer?",
                    "Tell me about breaking changes in Spring Framework 7"
            )) {
                assertStructured(chatClientWithSystemPrompt.prompt()
                        .user(u -> u.text(USER_PROMPT).param("question", question))
                        .advisors(advisor)
                        .call()
                        .entity(SupportResponse.class));
            }
        }
    }

    /** Lab 03-tool-calling. */
    void toolCalling() {
        for (var question : List.of(
                "Open a high priority ticket to request a trial for VMware Tanzu Spring",
                "Provide me an overview of all open support tickets",
                "Does VMware Tanzu Spring provide support for Spring Boot 2.7? If yes, open a ticket to request a trial"
        )) {
            assertStructured(chatClientWithSystemPrompt.prompt()
                    .user(u -> u.text(USER_PROMPT).param("question", question))
                    .advisors(ragAdvisor)
                    .tools(tools)
                    .call()
                    .entity(SupportResponse.class));
        }
    }

    /** Lab 04-testing. */
    void testing() {
        assertNotBlank(chatClientWithSystemPrompt.prompt()
                .user("Tell me about Spring AI")
                .call()
                .content());

        var question = "What are the key features of VMware Tanzu Spring?";
        var chatResponse = chatClientWithSystemPrompt.prompt()
                .user(question)
                .advisors(QuestionAnswerAdvisor.builder(vectorStore).build())
                .call()
                .chatResponse();

        var evaluationRequest = new EvaluationRequest(
                question,
                chatResponse.getMetadata().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS),
                chatResponse.getResult().getOutput().getText()
        );
        var evaluator = new RelevancyEvaluator(chatClientBuilder.build().mutate()
                .defaultOptions(ChatOptions.builder().model("gpt-5.4-nano")));
        var evaluationResponse = evaluator.evaluate(evaluationRequest);

        assertThat(evaluationResponse.isPass())
                .as("RAG response should be relevant to the retrieved context")
                .isTrue();
    }

    /** Lab 05-mcp. */
    void mcp() {
        for (var question : List.of(
                "What is the latest stable release of Spring AI?",
                "What is the latest stable release of Spring AI? Please also open a high-priority ticket to request access to Spring Application Advisor to accelerate upgrading our application to that version."
        )) {
            assertStructured(mcpChatClient.prompt()
                    .user(u -> u.text(USER_PROMPT).param("question", question))
                    .advisors(ragAdvisor)
                    .tools(tools)
                    .call()
                    .entity(SupportResponse.class));
        }
    }

    /** Lab 05-agentic-patterns, the tool search tool. */
    void toolSearch() {
        // The advisor indexes the tools into the same vector store the RAG advisor searches, and it
        // keys that index by conversation id. Keep one conversation id for both questions, so the
        // tools are indexed once and every tool ends up with a single embedding fixture. A second
        // fixture for the same tool would hold a slightly different vector, the mock would answer
        // with either of them, and the retrieved documents of the sample-app would come back in
        // another order than they did here.
        ToolIndex toolIndex = new VectorToolIndex(vectorStore);
        var toolSearchAdvisor = ToolSearchToolCallingAdvisor.builder()
                .toolIndex(toolIndex)
                .maxResults(5)
                .build();

        for (var question : List.of(
                "Open a high-priority ticket for an auth issue with the Spring Enterprise Repository.",
                "What are the key features of VMware Tanzu Spring?"
        )) {
            assertStructured(mcpChatClient.prompt()
                    .user(u -> u.text(USER_PROMPT).param("question", question))
                    .advisors(ragAdvisor, toolSearchAdvisor)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
                    .tools(tools)
                    .call()
                    .entity(SupportResponse.class));
        }
    }

    /** Lab 05-agentic-patterns-experimental, "Evaluator Optimizer". */
    void evaluatorOptimizer() {
        for (var question : List.of(
                "What are the key features of VMware Tanzu Spring?",
                "Open a high-priority ticket for an auth issue with the Spring Enterprise Repository."
        )) {
            assertStructured(mcpChatClient.prompt()
                    .user(u -> u.text(USER_PROMPT).param("question", question))
                    // The lab turns the tool search advisor off in application.properties, so all
                    // tools are sent with the request again and no tool index is built here.
                    .advisors(knowledgeBaseRagAdvisor, selfRefineEvaluationAdvisor)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
                    .tools(tools)
                    .call()
                    .entity(SupportResponse.class));
        }
    }

    /** Lab 05-agentic-patterns-experimental, "Agent Skills". */
    void agentSkills() {
        for (var question : List.of(
                "Our scanner flagged spring-boot 3.2.4 in our application. Is that version affected by any known vulnerability, and what should we upgrade to?",
                "What are the key features of VMware Tanzu Spring?"
        )) {
            assertStructured(skillsChatClient.prompt()
                    .user(u -> u.text(USER_PROMPT).param("question", question))
                    .advisors(knowledgeBaseRagAdvisor, selfRefineEvaluationAdvisor)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
                    .tools(tools)
                    .call()
                    .entity(SupportResponse.class));
        }

        // What the section puts on screen. It is worth nothing if the model never opened the skill
        // and never ran its script, so a recording in which it did not has to fail here rather than
        // ship a section that demonstrates nothing.
        assertTrue(invokedTools.contains("Skill"), "the model never opened the cve-lookup skill");
        assertTrue(invokedTools.contains("Bash"), "the model never ran the script of the skill");
    }

    /** Lab 05-agentic-patterns-experimental, "Plan and Execute". */
    void planAndExecute() {
        for (var question : List.of(
                "We run spring-boot 3.2.4. Use the TodoWrite tool to plan the work, then work through that plan: check whether that version has known vulnerabilities, find out which versions we could upgrade to, and open a high-priority ticket for the upgrade.",
                "What are the key features of VMware Tanzu Spring?"
        )) {
            assertStructured(planningChatClient.prompt()
                    .user(u -> u.text(USER_PROMPT).param("question", question))
                    .advisors(knowledgeBaseRagAdvisor, selfRefineEvaluationAdvisor)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
                    .tools(tools)
                    .call()
                    .entity(SupportResponse.class));
        }

        // The section is about watching the plan being written and worked off, so a recording in
        // which the model answered in one stretch has to fail here.
        assertFalse(writtenPlans.isEmpty(), "the model wrote no plan with TodoWrite");
    }

    /** Lab 05-agentic-patterns-experimental, "Human In The Loop". */
    void humanInTheLoop() {
        // The lab adds the question tool per call, only for a request that carries a conversation
        // id the client can answer on. The handler of the sample app parks the agent thread until
        // an answer arrives over HTTP; here it answers right away with the first option the model
        // offered, so the recorded conversation looks like a user who took the recommendation.
        var askUserQuestionToolAdvisor = AskUserQuestionToolAdvisor.builder(ChatFlows::answerWithFirstOption).build();

        assertStructured(planningChatClient.prompt()
                .user(u -> u.text(USER_PROMPT)
                        .param("question", "How do I configure virtual threads in my Spring Boot application?"))
                .advisors(knowledgeBaseRagAdvisor, selfRefineEvaluationAdvisor, askUserQuestionToolAdvisor)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
                .tools(tools)
                .call()
                .entity(SupportResponse.class));
    }

    private static void assertStructured(SupportResponse response) {
        assertNotNull(response, "structured response");
        assertNotNull(response.category(), "structured response category");
        assertNotBlank(response.answer());
    }

    private static Map<String, String> answerWithFirstOption(List<AskUserQuestionTool.Question> questions) {
        Map<String, String> answers = new LinkedHashMap<>();
        for (var question : questions) {
            var options = question.options();
            answers.put(question.question(), options.isEmpty() ? "Yes" : options.get(0).label());
        }
        return answers;
    }

    /**
     * The tool definitions of an annotated tool object, with every call answered by a canned
     * result instead of running the tool.
     */
    private static List<ToolCallback> cannedToolCallbacks(Object toolObject, String result) {
        return Arrays.stream(ToolCallbacks.from(toolObject))
                .map(callback -> cannedToolCallback(callback.getToolDefinition(), result))
                .toList();
    }

    /** A tool callback that notes its own name whenever the model calls it. */
    private static ToolCallback recording(ToolCallback delegate, List<String> invokedTools) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public String call(String toolInput) {
                invokedTools.add(delegate.getToolDefinition().name());
                return delegate.call(toolInput);
            }
        };
    }

    private static ToolCallback cannedToolCallback(ToolDefinition definition, String result) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return result;
            }
        };
    }

    private static ToolCallback springReleasesToolCallback() {
        var definition = ToolDefinition.builder()
                .name("fetchReleasesInfo")
                .description("Get all releases for a Spring project, including version and support status.")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": { "projectSlug": { "type": "string" } }
                        }
                        """)
                .build();
        return cannedToolCallback(definition,
                "[{\"version\":\"2.0.2-SNAPSHOT\",\"status\":\"SNAPSHOT\",\"current\":false},{\"version\":\"2.0.1\",\"status\":\"GENERAL_AVAILABILITY\",\"current\":true},{\"version\":\"1.1.8\",\"status\":\"GENERAL_AVAILABILITY\",\"current\":false},{\"version\":\"1.1.9-SNAPSHOT\",\"status\":\"SNAPSHOT\",\"current\":false},{\"version\":\"1.0.9\",\"status\":\"GENERAL_AVAILABILITY\",\"current\":false}]");
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