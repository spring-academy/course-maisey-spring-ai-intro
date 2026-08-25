package com.example.support_assistant.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.StubMappingTransformer;
import com.github.tomakehurst.wiremock.matching.ContentPattern;
import com.github.tomakehurst.wiremock.matching.EqualToJsonPattern;
import com.github.tomakehurst.wiremock.matching.MatchesJsonPathPattern;
import com.github.tomakehurst.wiremock.matching.RequestPattern;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.recording.RecordSpecBuilder;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.common.Metadata;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.recordSpec;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.matching.RequestPatternBuilder.newRequestPattern;

@Order(1)
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest
class OpenAiRecordingTest {

    private static final Path WIREMOCK_ROOT = Path.of("src/main/resources/mock");
    private static final Path CLASSPATH_ROOT = Path.of("target/classes/mock");
    private static final String OPENAI_TARGET = "https://api.openai.com";

    private static final WireMockServer recordingServer = new WireMockServer(options()
            .dynamicPort()
            .withRootDirectory(WIREMOCK_ROOT.toString())
            .extensions(new StripVolatileFieldsTransformer()));

    // Set by the flow that is being recorded, read by the transformer below, which stamps it into
    // the metadata of every stub that flow produced. That tag is what makes a single flow
    // re-recordable: its own stubs can be dropped without touching the ones of the other flows.
    private static volatile String currentFlow;

    // Higher precedence than WireMock's default of 5, see the transformer below.
    private static final int RECORDED_STUB_PRIORITY = 1;

    static {
        // No fixtures are cleared here. The server loads the ones already on disk, and they carry a
        // higher priority than the proxy the recorder registers, so a request a previously recorded
        // flow covers is served from that stub instead of being proxied to the API and recorded a
        // second time. Each flow drops its own stubs before it records.
        recordingServer.start();
    }

    private static RecordSpecBuilder flowRecordSpec(String flow) {
        currentFlow = flow;
        return recordSpec()
                .forTarget(OPENAI_TARGET)
                .matchRequestBodyWithEqualToJson(true, true)
                // Collapse repeated identical requests (e.g. a query embedded once per RAG loop, or
                // a chat request issued at several lab steps) into a single reusable stub. Without
                // this WireMock chains the duplicates into scenario states, which only replay in the
                // exact recorded order/count. The workshop replays each request standalone, so a
                // scenario stub sitting at a non-Started state returns 404.
                .ignoreRepeatRequests()
                .transformers(StripVolatileFieldsTransformer.NAME);
    }

    /**
     * Drops whatever the named flow recorded last time and records it again. Everything the flow
     * shares with the others stays in place, so a single flow can be re-recorded on its own with
     * {@code -Dtest=OpenAiRecordingTest#planAndExecute} without spending an API call on the rest.
     */
    private void record(String flow, ThrowingRunnable body) {
        removeRecordedFixtures(flow);
        recordInto(flow, body);
    }

    /** Records without dropping what the flow recorded before, for stubs the other flows share. */
    private void recordInto(String flow, ThrowingRunnable body) {
        recordingServer.startRecording(flowRecordSpec(flow).build());
        try {
            body.run();
        }
        catch (RuntimeException | Error e) {
            throw e;
        }
        catch (Throwable e) {
            throw new IllegalStateException("Flow " + flow + " failed", e);
        }
        finally {
            // Keep whatever was recorded even when the flow failed one of its assertions, so a
            // single unmet expectation does not leave the fixture set of that flow empty.
            recordingServer.stopRecording();
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * Removes the stubs of one flow, from the running server and from disk. Stub files carry the
     * flow in their metadata; the response bodies WireMock stored next to them under __files are
     * named in the stub, so they go with it.
     */
    private static void removeRecordedFixtures(String flow) {
        recordingServer.removeStubsByMetadata(matchingJsonPath("$.flow", equalTo(flow)));

        Path mappings = WIREMOCK_ROOT.resolve("mappings");
        if (!Files.isDirectory(mappings)) {
            return;
        }
        ObjectMapper objectMapper = new ObjectMapper();
        try (Stream<Path> files = Files.list(mappings)) {
            for (Path file : files.toList()) {
                JsonNode mapping = objectMapper.readTree(file.toFile());
                if (!flow.equals(mapping.path("metadata").path("flow").asText(null))) {
                    continue;
                }
                String bodyFileName = mapping.path("response").path("bodyFileName").asText(null);
                if (bodyFileName != null && !bodyFileName.isBlank()) {
                    Files.deleteIfExists(WIREMOCK_ROOT.resolve("__files").resolve(bodyFileName));
                }
                Files.deleteIfExists(file);
            }
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to remove the recorded fixtures of flow " + flow, e);
        }
    }

    @MockitoBean
    private MockOpenAiServer mockOpenAiServer;

    @DynamicPropertySource
    static void openAiProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.api-key", () -> System.getenv("OPENAI_API_KEY"));
        registry.add("spring.ai.openai.base-url", () -> "http://localhost:" + recordingServer.port() + "/v1");
    }

    @AfterAll
    void publishFixtures() throws IOException {
        copyFixturesOntoClasspath();
        recordingServer.stop();
    }

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private EmbeddingModel embeddingModel;

    private ChatFlows flows;

    @BeforeAll
    void recordSharedSetup() {
        // Only embeddings of the knowledge base, and every flow below shares them, so they are not
        // dropped first: on a run that records a single flow they are served by the stubs already
        // on disk and nothing is sent to the API. Start from an empty folder
        // (generate-openai-mocks.sh --fresh) to record them again.
        recordInto("setup", () -> this.flows = new ChatFlows(chatModel, chatClientBuilder, embeddingModel));
    }

    @Test
    @Order(1)
    void fundamentals() {
        record("fundamentals", flows::fundamentals);
    }

    @Test
    @Order(2)
    void advisors() {
        record("advisors", flows::advisors);
    }

    @Test
    @Order(3)
    void rag() {
        record("rag", flows::rag);
    }

    @Test
    @Order(4)
    void toolCalling() {
        record("toolCalling", flows::toolCalling);
    }

    @Test
    @Order(5)
    void testing() {
        record("testing", flows::testing);
    }

    @Test
    @Order(6)
    void mcp() {
        record("mcp", flows::mcp);
    }

    @Test
    @Order(7)
    void toolSearch() {
        record("toolSearch", flows::toolSearch);
    }

    @Test
    @Order(8)
    void evaluatorOptimizer() {
        record("evaluatorOptimizer", flows::evaluatorOptimizer);
    }

    @Test
    @Order(9)
    void agentSkills() {
        record("agentSkills", flows::agentSkills);
    }

    @Test
    @Order(10)
    void planAndExecute() {
        record("planAndExecute", flows::planAndExecute);
    }

    @Test
    @Order(11)
    void humanInTheLoop() {
        record("humanInTheLoop", flows::humanInTheLoop);
    }

    static final class StripVolatileFieldsTransformer extends StubMappingTransformer {

        static final String NAME = "strip-volatile-fields";

        private static final Set<String> VOLATILE_FIELDS = Set.of("model", "temperature", "top_p", "max_tokens",
                "max_completion_tokens", "n", "user", "stream", "stream_options");

        // Embedded text carries document metadata, and several of those values are UUIDs that are
        // new on every run, such as the parent_document_id a TokenTextSplitter stamps on each
        // chunk. Match every UUID with a wildcard so the stub stays reusable.
        private static final Pattern ANY_UUID = Pattern.compile(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

        // A VectorToolIndex document, embedded when the tool search advisor indexes the tools of a
        // conversation. Two of its metadata values are volatile (a fresh id and the conversation id
        // as sessionId), and it keeps its metadata in an immutable map, whose iteration order is
        // randomized per JVM, so the metadata lines come out in another order in the sample app
        // than they did here. The tool name alone identifies the document, so match on that.
        private static final Pattern TOOL_INDEX_TOOL_NAME = Pattern.compile("^toolName: (.+)$", Pattern.MULTILINE);

        private final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public boolean applyGlobally() {
            return false;
        }

        @Override
        public StubMapping transform(StubMapping stubMapping, FileSource files, Parameters parameters) {
            stubMapping.setMetadata(Metadata.metadata().attr("flow", currentFlow).build());
            // Outrank the catch-all proxy stub the recorder registers, which sits at WireMock's
            // default priority of 5 and, being added last, otherwise wins every match. With that
            // priority a recording proxies and re-records even the requests the stubs on disk
            // already cover, and a second stub for the same embedding input holds a slightly
            // different vector: the mock then answers with either of them and the documents the
            // RAG advisor retrieves come back in another order than they did while recording.
            stubMapping.setPriority(RECORDED_STUB_PRIORITY);
            RequestPattern request = stubMapping.getRequest();
            List<ContentPattern<?>> bodyPatterns = request.getBodyPatterns();
            if (bodyPatterns == null || bodyPatterns.isEmpty()) {
                return stubMapping;
            }
            try {
                stubMapping.setRequest(rewriteRequest(request, bodyPatterns));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return stubMapping;
        }

        private RequestPattern rewriteRequest(RequestPattern request, List<ContentPattern<?>> bodyPatterns)
                throws IOException {
            List<ContentPattern<?>> newPatterns = new ArrayList<>();
            Boolean streaming = null;
            Boolean hasResponseFormat = null;
            int messageCount = -1;
            for (ContentPattern<?> pattern : bodyPatterns) {
                if (!(pattern instanceof EqualToJsonPattern jsonPattern)) {
                    newPatterns.add(pattern);
                    continue;
                }
                ObjectNode body = (ObjectNode) objectMapper.readTree(jsonPattern.getEqualToJson());
                if (body.has("messages")) {
                    streaming = body.path("stream").asBoolean(false);
                    messageCount = body.get("messages").size();
                    // Native structured output drops the prompt-appended format instructions, so a
                    // native .entity() request has the same messages as a plain .content() request
                    // and only differs by response_format. Match on its presence/absence so the two
                    // do not collide under the matcher's ignoreExtraElements.
                    hasResponseFormat = body.has("response_format");
                    stripToolMessageContent((ArrayNode) body.get("messages"));
                }
                MatchesJsonPathPattern embeddingMatcher = embeddingInputMatcher(body);
                if (embeddingMatcher != null) {
                    newPatterns.add(embeddingMatcher);
                    continue;
                }
                body.remove(List.copyOf(VOLATILE_FIELDS));
                newPatterns.add(new EqualToJsonPattern(objectMapper.writeValueAsString(body), true, true));
            }
            if (streaming != null) {
                newPatterns.add(new MatchesJsonPathPattern(
                        streaming ? "$[?(@.stream == true)]" : "$[?(@.stream != true)]"));
            }
            if (messageCount >= 0) {
                newPatterns.add(new MatchesJsonPathPattern("$[?(@.messages.length() == " + messageCount + ")]"));
            }
            if (hasResponseFormat != null) {
                newPatterns.add(new MatchesJsonPathPattern(
                        hasResponseFormat ? "$[?(@.response_format)]" : "$[?(!@.response_format)]"));
            }

            RequestPatternBuilder builder = newRequestPattern(request.getMethod(), request.getUrlMatcher());
            newPatterns.forEach(builder::withRequestBody);
            return builder.build();
        }


        private void stripToolMessageContent(ArrayNode messages) {
            for (JsonNode message : messages) {
                if ("tool".equals(message.path("role").asText())) {
                    ((ObjectNode) message).remove("content");
                }
            }
        }

        private MatchesJsonPathPattern embeddingInputMatcher(ObjectNode body) {
            JsonNode input = body.get("input");
            if (input == null || !input.isArray() || input.isEmpty() || !input.get(0).isTextual()) {
                return null;
            }
            String text = input.get(0).asText();
            Matcher toolName = TOOL_INDEX_TOOL_NAME.matcher(text);
            if (toolName.find()) {
                return new MatchesJsonPathPattern("$.input[0]",
                        matching("(?s).*" + Pattern.quote(toolName.group()) + ".*"));
            }
            Matcher matcher = ANY_UUID.matcher(text);
            StringBuilder regex = new StringBuilder();
            int copiedUpTo = 0;
            while (matcher.find()) {
                regex.append(Pattern.quote(text.substring(copiedUpTo, matcher.start())))
                        .append("[0-9a-fA-F-]{36}");
                copiedUpTo = matcher.end();
            }
            if (copiedUpTo == 0) {
                return null;
            }
            regex.append(Pattern.quote(text.substring(copiedUpTo)));
            return new MatchesJsonPathPattern("$.input[0]", matching(regex.toString()));
        }
    }

    private void copyFixturesOntoClasspath() throws IOException {
        if (!Files.isDirectory(WIREMOCK_ROOT)) {
            return;
        }
        if (Files.exists(CLASSPATH_ROOT)) {
            try (Stream<Path> existing = Files.walk(CLASSPATH_ROOT)) {
                existing.sorted(Comparator.reverseOrder()).forEach(OpenAiRecordingTest::deleteQuietly);
            }
        }
        try (Stream<Path> sources = Files.walk(WIREMOCK_ROOT)) {
            for (Path source : sources.toList()) {
                Path target = CLASSPATH_ROOT.resolve(WIREMOCK_ROOT.relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target);
                }
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to clear " + path, e);
        }
    }
}