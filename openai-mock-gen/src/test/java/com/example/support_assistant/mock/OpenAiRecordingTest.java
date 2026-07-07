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
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
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

import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.recordSpec;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.matching.RequestPatternBuilder.newRequestPattern;

@Order(1)
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@SpringBootTest
class OpenAiRecordingTest {

    private static final Path WIREMOCK_ROOT = Path.of("src/main/resources/mock");
    private static final Path CLASSPATH_ROOT = Path.of("target/classes/mock");
    private static final String OPENAI_TARGET = "https://api.openai.com";

    private static final WireMockServer recordingServer = new WireMockServer(options()
            .dynamicPort()
            .withRootDirectory(WIREMOCK_ROOT.toString())
            .extensions(new StripVolatileFieldsTransformer()));

    static {
        clearRecordedFixtures();
        recordingServer.start();
        recordingServer.startRecording(recordSpec()
                .forTarget(OPENAI_TARGET)
                .matchRequestBodyWithEqualToJson(true, true)
                .transformers(StripVolatileFieldsTransformer.NAME)
                .build());
    }

    private static void clearRecordedFixtures() {
        for (String subDirectory : List.of("mappings", "__files")) {
            Path directory = WIREMOCK_ROOT.resolve(subDirectory);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> entries = Files.walk(directory)) {
                entries.filter(path -> !path.equals(directory))
                        .sorted(Comparator.reverseOrder())
                        .forEach(OpenAiRecordingTest::deleteQuietly);
            } catch (IOException e) {
                throw new RuntimeException("Failed to clear " + directory, e);
            }
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
    static void stopRecordingServer() {
        recordingServer.stop();
    }

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Test
    void recordChatFlows() throws IOException {
        ChatFlows.exercise(chatModel, chatClientBuilder, embeddingModel);

        recordingServer.stopRecording();
        copyFixturesOntoClasspath();
    }

    static final class StripVolatileFieldsTransformer extends StubMappingTransformer {

        static final String NAME = "strip-volatile-fields";

        private static final Set<String> VOLATILE_FIELDS = Set.of("model", "temperature", "top_p", "max_tokens",
                "max_completion_tokens", "n", "user", "stream", "stream_options");

        private static final Pattern PARENT_DOCUMENT_ID_UUID = Pattern.compile(
                "(?<=parent_document_id: )[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

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
            Matcher matcher = PARENT_DOCUMENT_ID_UUID.matcher(text);
            if (!matcher.find()) {
                return null;
            }
            String regex = Pattern.quote(text.substring(0, matcher.start()))
                    + "[0-9a-fA-F-]{36}"
                    + Pattern.quote(text.substring(matcher.end()));
            return new MatchesJsonPathPattern("$.input[0]", matching(regex));
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