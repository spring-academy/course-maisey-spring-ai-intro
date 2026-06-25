package com.example.support_assistant.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.recordSpec;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Records real OpenAI interactions and stores them as WireMock fixtures under
 * src/main/resources/mock, where {@link MockOpenAiServer} replays them at workshop runtime.
 *
 * This test is skipped unless OPENAI_API_KEY is set. To regenerate the fixtures:
 *
 *   OPENAI_API_KEY=sk-... ./mvnw -o test -Dtest=OpenAiRecordingTest
 *
 * The model used for recording can differ from the model the workshop sends because the recorded
 * request matchers ignore volatile fields such as the model id (see {@link #stripVolatileFields}).
 *
 * After recording it strips the volatile fields and copies the fixtures onto the classpath so that
 * {@link OpenAiMockValidationTest} (which runs next) replays the freshly recorded interactions
 * through the real {@link MockOpenAiServer} and the application.properties configuration.
 */
@Order(1)
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@SpringBootTest
class OpenAiRecordingTest {

    private static final Path WIREMOCK_ROOT = Path.of("src/main/resources/mock");
    private static final Path CLASSPATH_ROOT = Path.of("target/classes/mock");
    private static final String OPENAI_TARGET = "https://api.openai.com";

    /**
     * Recording proxy. It is started before the Spring context so its dynamic port can be
     * fed to Spring AI's OpenAI auto-configuration, which then builds the {@link ChatClient.Builder}
     * pointing at this server. The server proxies to and records the real OpenAI API.
     */
    private static final WireMockServer recordingServer = new WireMockServer(options()
            .dynamicPort()
            .withRootDirectory(WIREMOCK_ROOT.toString()));

    static {
        recordingServer.start();
    }

    /**
     * The recording context points Spring AI at the proxy, so the real {@link MockOpenAiServer}
     * on port 8081 is not needed here. Mocking it away also frees port 8081 for the validation
     * context, which stays cached alongside this one.
     */
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

    @Value("classpath:/prompts/system-prompt.st")
    private Resource systemPrompt;

    /**
     * Fields that vary between recording time and workshop runtime. They are removed
     * from each recorded request matcher so a request still matches even though the
     * workshop sends a different model id, temperature, and so on.
     */
    private static final Set<String> VOLATILE_FIELDS = Set.of("model", "temperature", "top_p", "max_tokens",
            "max_completion_tokens", "n", "user", "stream", "stream_options");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void recordChatFlows() throws IOException {
        // Proxy the chat flows to the real OpenAI API and capture the interactions.
        recordingServer.startRecording(recordSpec()
                .forTarget(OPENAI_TARGET)
                .matchRequestBodyWithEqualToJson(true, true)
                .build());

        ChatFlows.exercise(chatModel, chatClientBuilder, systemPrompt);

        recordingServer.stopRecording();

        // Make the recorded fixtures usable by the workshop and by the validation replay.
        stripVolatileFields();
        copyFixturesOntoClasspath();
    }

    /**
     * Removes {@link #VOLATILE_FIELDS} from the equalToJson matcher of every recorded
     * mapping so the request still matches when the workshop sends different values.
     * The jsonBodyMatchFlags(true, true) recorder setting already keeps ignoreArrayOrder
     * and ignoreExtraElements enabled.
     */
    private void stripVolatileFields() throws IOException {
        var mappings = WIREMOCK_ROOT.resolve("mappings");
        if (!Files.isDirectory(mappings)) {
            return;
        }
        try (Stream<Path> files = Files.list(mappings)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                rewriteMapping(file);
            }
        }
    }

    private void rewriteMapping(Path file) throws IOException {
        ObjectNode mapping = (ObjectNode) objectMapper.readTree(Files.readString(file));
        var request = mapping.get("request");
        if (request == null || !request.has("bodyPatterns")) {
            return;
        }
        ArrayNode bodyPatterns = (ArrayNode) request.get("bodyPatterns");
        boolean changed = false;
        Boolean streaming = null;
        int messageCount = -1;
        for (var pattern : bodyPatterns) {
            if (pattern instanceof ObjectNode patternNode && patternNode.has("equalToJson")) {
                ObjectNode body = (ObjectNode) objectMapper.readTree(patternNode.get("equalToJson").asText());
                // The streaming flag and message count drive the explicit matchers below, so capture
                // them before `stream` is stripped along with the other volatile fields.
                streaming = body.path("stream").asBoolean(false);
                messageCount = body.path("messages").size();
                body.remove(List.copyOf(VOLATILE_FIELDS));
                patternNode.put("equalToJson", objectMapper.writeValueAsString(body));
                patternNode.put("ignoreArrayOrder", true);
                patternNode.put("ignoreExtraElements", true);
                changed = true;
            }
        }
        if (streaming != null) {
            // The equalToJson matcher ignores extra elements and no longer carries `stream`, so a
            // streaming request (stream:true) would otherwise also match a non-streaming mapping and
            // get a plain JSON response back instead of an SSE stream. Add an explicit matcher so each
            // mapping only answers the request shape (streaming or not) it actually recorded.
            ObjectNode streamMatcher = bodyPatterns.addObject();
            streamMatcher.put("matchesJsonPath", streaming ? "$[?(@.stream == true)]" : "$[?(@.stream != true)]");
            changed = true;
        }
        if (messageCount >= 0) {
            // ignoreExtraElements also treats the expected messages array as a subset, so a mapping
            // recorded for a single user message would otherwise match a request that adds a system
            // message (and vice versa). Pin the message count so each mapping only answers requests
            // with the same conversation shape.
            ObjectNode countMatcher = bodyPatterns.addObject();
            countMatcher.put("matchesJsonPath", "$[?(@.messages.length() == " + messageCount + ")]");
            changed = true;
        }
        if (changed) {
            Files.writeString(file, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapping));
        }
    }

    /**
     * Mirrors the just-recorded fixtures from the source tree onto the runtime classpath
     * (target/classes/mock). Maven only copies resources at build start, so without this the
     * {@link MockOpenAiServer} in the validation context would replay the previously committed
     * fixtures instead of the ones recorded in this run.
     */
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