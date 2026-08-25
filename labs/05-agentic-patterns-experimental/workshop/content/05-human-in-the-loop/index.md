---
title: Human In The Loop
---

When information is missing, your assistant so far just guesses. When someone asks "How do I configure virtual threads?", the right answer depends on the Spring Boot version. The model picks a version on its own, and you only find out that it picked wrong when the answer does not work.

The `AskUserQuestionTool` lets it ask instead. The article notes that a web application has to bridge the blocking handler to an asynchronous client. That bridge is what you build here, and it comes down to three pieces. A handler that parks the agent thread, an advisor that adds the tool together with instructions on when to use it, and two REST endpoints for reading the question and posting the answer.

## Bridge the Handler to the Client

The handler is called on the thread that is in the middle of the model call, and it has to return the answers. So it has to wait, while the answers arrive later on a completely different thread over HTTP.


```terminal:execute
command:  mkdir -p ~/sample-app/src/main/java/com/example/support_assistant/tools
cascade: true
description: Create the AskUserQuestionHandler
```
```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/support_assistant/tools/AskUserQuestionHandler.java
description: Create the AskUserQuestionHandler
hidden: true
text: |
  package com.example.support_assistant.tools;

  import org.springaicommunity.agent.tools.AskUserQuestionTool;

  import java.util.List;
  import java.util.Map;
  import java.util.concurrent.CompletableFuture;
  import java.util.concurrent.TimeUnit;

  /**
   * Bridges the blocking {@link AskUserQuestionTool} handler to an asynchronous client.
   * The agent thread waits on a future while the client polls the pending questions and
   * posts the answers over the REST API.
   */
  public class AskUserQuestionHandler implements AskUserQuestionTool.QuestionHandler {

      private static final int TIMEOUT_MINUTES = 5;

      private volatile List<AskUserQuestionTool.Question> pendingQuestions = List.of();

      private volatile CompletableFuture<Map<String, String>> answers = new CompletableFuture<>();

      @Override
      public Map<String, String> handle(List<AskUserQuestionTool.Question> questions) {
          this.answers = new CompletableFuture<>();
          this.pendingQuestions = questions;
          try {
              return this.answers.get(TIMEOUT_MINUTES, TimeUnit.MINUTES);
          } catch (Exception e) {
              throw new IllegalStateException("No answers received within " + TIMEOUT_MINUTES + " minutes", e);
          } finally {
              this.pendingQuestions = List.of();
          }
      }

      public List<AskUserQuestionTool.Question> pendingQuestions() {
          return this.pendingQuestions;
      }

      public void answer(Map<String, String> answers) {
          this.answers.complete(answers);
      }
  }
```

Two threads meet in this class, so walk through it in that order.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/tools/AskUserQuestionHandler.java
text: "public Map<String, String> handle(List<AskUserQuestionTool.Question> questions) {"
description: The agent thread arrives here
```

This is the method the tool calls. It runs inside the tool call, which runs inside `chatClient.call()`, which is still holding the HTTP request of the user who asked the original question. Everything is on hold until this method returns.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/tools/AskUserQuestionHandler.java
text: "this.answers = new CompletableFuture<>();"
description: Publish before you wait
after: 1
```

The order of these two lines matters. A fresh future is created first, and only then are the questions published. If you publish first, a very fast client could answer before the future exists and the answer would go nowhere.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/tools/AskUserQuestionHandler.java
text: "return this.answers.get(TIMEOUT_MINUTES, TimeUnit.MINUTES);"
description: Park the agent thread
```

Here the thread parks. It is the whole trick of this class and also its cost, because a thread is held for as long as the human takes to answer. The timeout is what keeps a forgotten question from holding it forever, and five minutes is generous for a lab and probably too long for production.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/tools/AskUserQuestionHandler.java
text: "this.pendingQuestions = List.of();"
description: Clear the question either way
before: 1
```

The `finally` clears the pending questions on the way out, no matter whether an answer arrived or the timeout hit. Otherwise a stale question would still be offered to the client after nobody is waiting for it any more.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/tools/AskUserQuestionHandler.java
text: "public void answer(Map<String, String> answers) {"
description: The HTTP thread arrives here
after: 2
```

This is the other side. It is called from a request thread that has nothing to do with the agent, and completing the future is what wakes the parked thread up. Both fields are `volatile` because the two threads never share a lock, they only share these two references.

## Add the Tool with an Advisor

The handler belongs to one conversation, so the tool cannot be a default tool on the shared `ChatClient`. It has to be added per call, and only when the caller asked for it.

You could add the `AskUserQuestionTool` with `.tools(...)` like any other tool. In practice the model then rarely calls it. It prefers to guess the missing information, or it asks for it in the text of its final answer, where your client cannot answer. The description of the tool alone does not tell the model when it should ask. So you **write a small custom advisor** that adds the tool and extra instructions to the system prompt in one step.

```terminal:execute
command:  mkdir -p ~/sample-app/src/main/java/com/example/support_assistant/advisor
cascade: true
description: Create the AskUserQuestionToolAdvisor
```
```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/support_assistant/advisor/AskUserQuestionToolAdvisor.java
description: Create the AskUserQuestionToolAdvisor
hidden: true
text: |
  package com.example.support_assistant.advisor;

  import java.util.Objects;

  import org.jspecify.annotations.Nullable;
  import org.springaicommunity.agent.tools.AskUserQuestionTool;
  import org.springaicommunity.agent.tools.AskUserQuestionTool.QuestionHandler;

  import org.springframework.ai.chat.client.ChatClientRequest;
  import org.springframework.ai.chat.client.ChatClientResponse;
  import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
  import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
  import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
  import org.springframework.ai.model.tool.ToolCallingChatOptions;
  import org.springframework.ai.support.ToolCallbacks;
  import org.springframework.ai.tool.ToolCallback;
  import org.springframework.util.Assert;

  /**
   * Custom advisor that uses the {@link AskUserQuestionTool} from the Spring AI Community agent utils.
   * It adds the tool to the request and extra guidance to the system prompt.
   * This guidance makes the AI model more probably call the tool, so a human in the loop can provide missing information
   * like the Spring version before the model answers.
   */
  public final class AskUserQuestionToolAdvisor implements BaseAdvisor {

      public static final int DEFAULT_ORDER = ToolCallingAdvisor.DEFAULT_ORDER - 100;

      public static final String DEFAULT_PROMPT = """
              You can ask the user questions with the AskUserQuestionTool.
              The right answer to many Spring questions depends on the version of Spring Boot or Spring Framework.
              Before you answer, check if the user already told you the version in the question or earlier in the conversation.
              If the version or other information you need is missing, you MUST call the AskUserQuestionTool first and wait for the answer.
              Offer the most common versions as options and put the latest version first.
              Never guess missing information and never ask for it in your final answer text. Always use the AskUserQuestionTool for that.
              Only answer the question after you have all the information you need.
              """;

      private final ToolCallback[] toolCallbacks;
      private final String promptTemplate;
      private final int order;

      private AskUserQuestionToolAdvisor(QuestionHandler questionHandler, @Nullable String promptTemplate, int order) {
          this.toolCallbacks = ToolCallbacks.from(
                  AskUserQuestionTool.builder().questionHandler(questionHandler).build());
          this.promptTemplate = promptTemplate == null ? DEFAULT_PROMPT : promptTemplate;
          this.order = order;
      }

      public static Builder builder(QuestionHandler questionHandler) {
          return new Builder(questionHandler);
      }

      @Override
      public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
          var toolOptions = Objects.requireNonNull((ToolCallingChatOptions) chatClientRequest.prompt().getOptions());
          var updatedToolOptionsBuilder = toolOptions.mutate().toolCallbacks(this.toolCallbacks);

          return chatClientRequest.mutate()
                  .prompt(chatClientRequest.prompt().augmentSystemMessage(systemMessage -> systemMessage.mutate()
                                  .text(systemMessage.getText() + System.lineSeparator() + this.promptTemplate)
                                  .build())
                          .mutate()
                          .chatOptions(updatedToolOptionsBuilder.build())
                          .build())
                  .build();
      }

      @Override
      public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
          return chatClientResponse;
      }

      @Override
      public int getOrder() {
          return this.order;
      }

      public static final class Builder {

          private final QuestionHandler questionHandler;
          private @Nullable String promptTemplate;
          private int order = DEFAULT_ORDER;

          private Builder(QuestionHandler questionHandler) {
              Assert.notNull(questionHandler, "questionHandler cannot be null");
              this.questionHandler = questionHandler;
          }

          public Builder promptTemplate(String promptTemplate) {
              Assert.notNull(promptTemplate, "promptTemplate cannot be null");
              this.promptTemplate = promptTemplate;
              return this;
          }

          public Builder order(int order) {
              this.order = order;
              return this;
          }

          public AskUserQuestionToolAdvisor build() {
              return new AskUserQuestionToolAdvisor(this.questionHandler, this.promptTemplate, this.order);
          }
      }
  }
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/advisor/AskUserQuestionToolAdvisor.java
text: "public static final String DEFAULT_PROMPT = \"\"\""
description: Tell the model when to ask
after: 8
```

This is the part that makes the difference. The prompt tells the model that many Spring answers depend on the version, and that it has to check whether the user already named it. If the version is missing, the model **must** call the tool. The prompt also forbids the two things the model likes to do instead, guessing and asking in the answer text. The latest version comes first in the options, so the user has a quick default to pick.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/advisor/AskUserQuestionToolAdvisor.java
text: "var updatedToolOptionsBuilder = toolOptions.mutate().toolCallbacks(this.toolCallbacks);"
description: Add the tool and the instructions
before: 1
after: 9
```

The `before` method changes the request before it goes to the model. It adds the tool callback to the chat options and appends the prompt to the existing system message. The tool and the instructions always travel together, so you never get one without the other.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/advisor/AskUserQuestionToolAdvisor.java
text: "public static final int DEFAULT_ORDER = ToolCallingAdvisor.DEFAULT_ORDER - 100;"
description: Run before the tool calling loop
```

The order puts this advisor before the `ToolCallingAdvisor`, which runs the tool calling loop. So the tool is already part of the request when the loop starts, and the system prompt is extended only once, no matter how many tool calls follow.

Now use the advisor in the service, but only when there is a handler.

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
line: 3
description: Add the advisor per call
cascade: true
text: |2
  import org.jspecify.annotations.Nullable;
  import com.example.support_assistant.advisor.AskUserQuestionToolAdvisor;
  import org.springaicommunity.agent.tools.AskUserQuestionTool.QuestionHandler;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "SupportResponse generateResponse(String query, String conversationId) {"
after: 17
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
hidden: true
text: |2
      SupportResponse generateResponse(String query, String conversationId,
                                       @Nullable QuestionHandler questionHandler) {
          var ragSearchRequest = SearchRequest.builder().topK(4).similarityThreshold(0.4).build();

          var promptTemplate = PromptTemplate.builder().resource(ragPromptResource).build();
          var ragAdvisor = QuestionAnswerAdvisor.builder(vectorStore).searchRequest(ragSearchRequest)
                  .promptTemplate(promptTemplate).build();

          var request = chatClient.prompt()
              .user(u -> u
                      .text("Answer the following question with a short, well-structured explanation: {question}")
                      .param("question", query)
              )
              .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
              .advisors(ragAdvisor)
              .tools(supportTicketService);

          if (questionHandler != null) {
              request.advisors(AskUserQuestionToolAdvisor.builder(questionHandler).build());
          }

          return request.call().entity(SupportResponse.class);
      }
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "var request = chatClient.prompt()"
description: The chain is split in two
```

The call chain had to be split for this. The builder is kept in a variable, the advisor is added to it only when there is a handler, and `call()` moves to the end. Without a handler the request is exactly what it was before, so nothing changes for the other sections of this lab.

## Expose the Questions and the Answers

The client needs two more endpoints. One to read what the agent is asking, one to send the answer back.

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
line: 3
description: Add the question and answer endpoints
cascade: true
text: |2
  import com.example.support_assistant.tools.AskUserQuestionHandler;
  import org.springaicommunity.agent.tools.AskUserQuestionTool;
  import org.springframework.http.HttpStatus;
  import org.springframework.web.bind.annotation.PostMapping;
  import org.springframework.web.bind.annotation.RequestBody;
  import org.springframework.web.server.ResponseStatusException;
  import java.util.List;
  import java.util.Map;
  import java.util.concurrent.ConcurrentHashMap;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
text: "private final SupportAssistantService service;"
after: 13
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
hidden: true
text: |2
      private final SupportAssistantService service;

      private final Map<String, AskUserQuestionHandler> questionHandlers = new ConcurrentHashMap<>();

      SupportAssistantController(SupportAssistantService service) {
          this.service = service;
      }

      // curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about Spring AI" \
      //   --data-urlencode "humanInTheLoop=true" -H "X-Conversation-Id: 1234"
      @GetMapping(path = "/api/v{version}/chat")
      ResponseEntity<SupportResponse> chat(@RequestParam String query,
                                           @RequestParam(defaultValue = "false") boolean humanInTheLoop,
                                           @RequestHeader(value = CONVERSATION_ID_HEADER, required = false) String conversationId) {
          var id = (conversationId != null) ? conversationId : UUID.randomUUID().toString();

          var questionHandler = (humanInTheLoop && conversationId != null)
                  ? questionHandlers.computeIfAbsent(conversationId, key -> new AskUserQuestionHandler())
                  : null;
          var response = service.generateResponse(query, id, questionHandler);
          return ResponseEntity.ok().header(CONVERSATION_ID_HEADER, id).body(response);
      }

      // curl "http://localhost:8080/api/v1/chat/questions" -H "X-Conversation-Id: 1234"
      @GetMapping(path = "/api/v{version}/chat/questions")
      List<AskUserQuestionTool.Question> questions(@RequestHeader(CONVERSATION_ID_HEADER) String conversationId) {
          return handlerFor(conversationId).pendingQuestions();
      }

      // curl "http://localhost:8080/api/v1/chat/answers" -H "X-Conversation-Id: 1234" -H "Content-Type: application/json" \
      //   -d '{"Which Spring Boot version do you use?": "Spring Boot 4"}'
      @PostMapping(path = "/api/v{version}/chat/answers")
      void answers(@RequestHeader(CONVERSATION_ID_HEADER) String conversationId,
                   @RequestBody Map<String, String> answers) {
          handlerFor(conversationId).answer(answers);
      }

      private AskUserQuestionHandler handlerFor(String conversationId) {
          var questionHandler = questionHandlers.get(conversationId);
          if (questionHandler == null) {
              throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                      "No human in the loop conversation with id " + conversationId);
          }
          return questionHandler;
      }
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
text: "private final Map<String, AskUserQuestionHandler> questionHandlers = new ConcurrentHashMap<>();"
description: One handler per conversation
```

Two users must not answer each other's questions, so there is one handler per conversation id and the map is concurrent because several requests hit it at the same time.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
text: "var questionHandler = (humanInTheLoop && conversationId != null)"
description: Only when the client can answer
after: 2
```

The handler is only created when the caller asked for it with `humanInTheLoop=true` and sent a conversation id. Without an id there would be no way to send the answer back, so the tool stays off and the model guesses as before. This also keeps the other sections of the lab working unchanged.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
text: "List<AskUserQuestionTool.Question> questions(@RequestHeader(CONVERSATION_ID_HEADER) String conversationId) {"
description: What the client polls
```

Polling is the simplest thing that works in a lab. A real user interface would push the question over WebSocket or server-sent events instead, but nothing about the handler changes for that. Only this endpoint would.

## Test It

Wait until DevTools restarted the application, then send a deliberately vague request. This call will not answer right away, so start it in the background and write the result to a file.

```terminal:execute
command: |
  curl -s -G "http://localhost:8080/api/v1/chat" \
    --data-urlencode "query=How do I configure virtual threads in my Spring Boot application?" \
    --data-urlencode "humanInTheLoop=true" \
    -H "X-Conversation-Id: 1234" > /tmp/chat-response.json &
session: 1
```

Give the model a few seconds to get to the tool call, then ask what it wants to know.

```terminal:execute
command: |
  curl -s "http://localhost:8080/api/v1/chat/questions" -H "X-Conversation-Id: 1234"
session: 1
```

You should get one or more questions, each with a short header, the question text, and a handful of options. If the list is still empty, run the command again. The model is probably still working.

Now answer. The key of each entry is the exact question text from the response you just got.

```terminal:execute
command: |
  curl -s -X POST "http://localhost:8080/api/v1/chat/answers" \
    -H "X-Conversation-Id: 1234" \
    -H "Content-Type: application/json" \
    -d '{"Which Spring Boot version is your application using?": "Spring Boot 4.x"}'
session: 1
```

The moment this request is handled, the parked thread wakes up, the tool returns your answer to the model, and the model finishes the work it started.

```terminal:execute
command: cat /tmp/chat-response.json
session: 1
```

The answer now fits the version you chose instead of the version the model would have guessed. In the logs of the assistant you can see the whole gap. First the `askUserQuestion` tool call, then a long pause with nothing happening, and then the final answer that uses your choice.
