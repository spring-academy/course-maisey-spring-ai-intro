---
title: Agentic Patterns
---

Your Support Assistant has a growing tool inventory: three in-process ticket tools, plus every tool advertised by every connected MCP server — like the Spring Releases MCP server you connected in the previous lab.

There's a problem with that: **every chat call sends the full tool schema to the model**, regardless of whether any of those tools could plausibly help with this question. Each tool description is tokens you pay for on every request, the model has more chances to pick a wrong tool, and the prompt bloats as you add MCP servers.

The fix is the **Tool Search Tool**: an agentic pattern where the model is given a *single* meta-tool called something like `searchTools`, plus an index that embeds every real tool's description into the vector store. When the model needs to act, it calls `searchTools` with a natural-language query, gets back the top-K most relevant tools, and only those flow into the next turn. It's RAG, but for tools.

## Configure the AI Provider

Spring AI supports many chat model providers, such as OpenAI, Anthropic, Amazon Bedrock, and Ollama. In this workshop, we use **OpenAI**. The sample application also ships Spring profiles for the alternatives (`application-anthropic.properties`, `application-ollama.properties`, `application-bedrock-converse.properties`) — set the related credentials and `SPRING_PROFILES_ACTIVE` accordingly if you want to try one of them instead.

Set your OpenAI API key in the terminal session the Support Assistant will run in:

```terminal:input
text: export OPENAI_API_KEY=
endl: false
session: 2
```

## Start the Spring Releases MCP Server

The Spring Releases MCP server from the MCP Integration lab is already part of this workshop environment. Start it so its `fetchReleasesInfo` tool is available to the Support Assistant:

```terminal:execute
command: cd ~/spring-releases-mcp-server && ./mvnw spring-boot:run
session: 3
```

You should see the embedded MCP server start on port 8081 and log one registered tool at startup.

## Add the Tool Search Advisor Dependency

Add the Tool Search advisor to the Support Assistant's `pom.xml`:

```editor:select-matching-text
file: ~/sample-app/pom.xml
text: "</dependencies>"
description: "Apply - Add the Spring AI Tool Search advisor"
before: 0
after: 0
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/pom.xml
hidden: true
text: |2
  		<dependency>
  			<groupId>org.springframework.ai</groupId>
  			<artifactId>spring-ai-tool-search-advisor</artifactId>
  		</dependency>
  	</dependencies>
```

This pulls in `ToolSearchToolCallingAdvisor`, `ToolIndex`, and the vector-store-backed default implementation (`VectorToolIndex`).

## Configure the ToolIndex

The index is what stores the tool descriptions for similarity search. The default `VectorToolIndex` reuses your existing `VectorStore` — so the same `SimpleVectorStore` that holds the knowledge base chunks will also hold the tool descriptions, in separate vectors.

Add the bean to `SupportAssistantConfiguration.java`:

```java
@Bean
ToolIndex toolIndex(VectorStore vectorStore) {
    return new VectorToolIndex(vectorStore);
}
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "class SupportAssistantConfiguration {"
description: "Apply - Configure the ToolIndex"
before: 0
after: 0
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
hidden: true
cascade: true
text: |2
  class SupportAssistantConfiguration {

      @Bean
      ToolIndex toolIndex(VectorStore vectorStore) {
          return new VectorToolIndex(vectorStore);
      }
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
hidden: true
line: 3
text: |-
  import org.springframework.ai.tool.toolsearch.ToolIndex;
  import org.springframework.ai.tool.toolsearch.index.vectorstore.VectorToolIndex;
```

On startup, the auto-configuration iterates every available `ToolCallback` (in-process + MCP) and embeds its name + description into the index. From this point on the model never sees those tools directly — it sees only `searchTools`.

## Add the ToolSearchToolCallingAdvisor to the ChatClient

Wire the advisor as a **default** on the `ChatClient` bean so every call goes through it. Update the `chatClient` factory method in `SupportAssistantConfiguration.java`:

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder, ToolCallbackProvider tools, ToolIndex toolIndex) {
    var toolSearchAdvisor = ToolSearchToolCallingAdvisor.builder()
            .toolIndex(toolIndex)
            .maxResults(5)
            .build();

    return builder
            .defaultSystem("You are a Spring and AI expert.")
            .defaultTools(tools)
            .defaultAdvisors(toolSearchAdvisor)
            .build();
}
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "ChatClient chatClient(ChatClient.Builder builder, ToolCallbackProvider tools) {"
description: "Apply - Add the Tool Search advisor to the ChatClient"
before: 0
after: 1
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
hidden: true
cascade: true
text: |2
      ChatClient chatClient(ChatClient.Builder builder, ToolCallbackProvider tools, ToolIndex toolIndex) {
          var toolSearchAdvisor = ToolSearchToolCallingAdvisor.builder()
                  .toolIndex(toolIndex)
                  .maxResults(5)
                  .build();

          return builder
                  .defaultSystem("You are a Spring and AI expert.")
                  .defaultTools(tools)
                  .defaultAdvisors(toolSearchAdvisor)
                  .build();
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
hidden: true
line: 3
text: import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
```

What changed:

- **`ToolIndex toolIndex` injected** alongside the existing `ToolCallbackProvider`.
- **`maxResults(5)`** — the advisor will inject the top 5 most relevant tools per turn. Tune this for your tool count and model context budget.
- **`.defaultAdvisors(toolSearchAdvisor)`** — registered once on the bean, so every `chatClient.prompt()` chain picks it up.

Behind the scenes, when the model decides to act, the advisor:

1. Intercepts the call before tools are sent to the model.
2. Replaces the full tool list with just the `searchTools` meta-tool.
3. When the model calls `searchTools("create a ticket about login failures")`, runs a similarity search against the `ToolIndex`.
4. Surfaces the matching tools to the model on the next turn so it can actually invoke them.

The full list — `createTicket`, `retrieveTickets`, `retrieveOpenTickets`, `spring-releases_fetchReleasesInfo`, etc. — is no longer in every prompt.

## Wire Conversation Memory Through a Conversation ID

The advisor is multi-turn by design: turn 1 is "search for tools", turn 2 is "now actually call them". For that to work, the framework needs `ChatMemory` plus a **conversation id** so it can correlate the two turns. `ChatMemory` is auto-configured (`MessageWindowChatMemory` + in-memory repository) once the advisor is on the classpath; the only thing you have to supply is the conversation id per request.

See the [Spring AI Tool Search Tool documentation](https://docs.spring.io/spring-ai/reference/2.0/api/tools.html#tool-search-tool) for the framework contract.

### Accept the Conversation ID as a Header

Update `SupportAssistantController.java` to read `X-Conversation-Id` from the request, falling back to a generated UUID when the client doesn't supply one:

```java
@GetMapping(path = "/api/{version}/chat")
SupportResponse chat(@RequestParam String query,
                     @RequestHeader(value = "X-Conversation-Id", required = false) String conversationId) {
    return service.generateResponse(query, conversationId != null ? conversationId : UUID.randomUUID().toString());
}
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
text: "SupportResponse chat(@RequestParam String query) {"
description: "Apply - Accept the conversation id as a header"
before: 0
after: 1
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
hidden: true
cascade: true
text: |2
      SupportResponse chat(@RequestParam String query,
                           @RequestHeader(value = "X-Conversation-Id", required = false) String conversationId) {
          return service.generateResponse(query, conversationId != null ? conversationId : UUID.randomUUID().toString());
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
hidden: true
cascade: true
line: 4
text: import org.springframework.web.bind.annotation.RequestHeader;
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
hidden: true
line: 7
text: |2

  import java.util.UUID;
```

`@RequestHeader.defaultValue` only takes string literals, hence the null-check + `UUID.randomUUID()` fallback. Clients that *do* want a continuous conversation (the typical case for an agentic assistant — turn N+1 needs to see turn N's history) supply a stable id.

### Pass the ID Into the Chain

Update `SupportAssistantService.generateResponse` to accept the id and forward it via the advisor param. The framework looks for it under `ChatMemory.CONVERSATION_ID`:

```java
SupportResponse generateResponse(String query, String conversationId) {
    var ragSearchRequest = SearchRequest.builder().topK(3).similarityThreshold(0.7).build();

    var promptTemplate = PromptTemplate.builder().resource(ragPromptResource).build();
    var ragAdvisor = QuestionAnswerAdvisor.builder(vectorStore).searchRequest(ragSearchRequest)
            .promptTemplate(promptTemplate).build();

    return chatClient.prompt()
            .user(u -> u
                    .text("Answer the following question with a short, well-structured explanation: {question}")
                    .param("question", query)
            )
            .advisors(ragAdvisor)
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
            .tools(supportTicketService)
            .call()
            .entity(SupportResponse.class);
}
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "SupportResponse generateResponse(String query) {"
description: "Apply - Pass the conversation id to the advisors"
before: 0
after: 16
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
hidden: true
cascade: true
text: |2
      SupportResponse generateResponse(String query, String conversationId) {
          var ragSearchRequest = SearchRequest.builder().topK(3).similarityThreshold(0.7).build();

          var promptTemplate = PromptTemplate.builder().resource(ragPromptResource).build();
          var ragAdvisor = QuestionAnswerAdvisor.builder(vectorStore).searchRequest(ragSearchRequest)
                  .promptTemplate(promptTemplate).build();

          return chatClient.prompt()
                  .user(u -> u
                          .text("Answer the following question with a short, well-structured explanation: {question}")
                          .param("question", query)
                  )
                  .advisors(ragAdvisor)
                  .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                  .tools(supportTicketService)
                  .call()
                  .entity(SupportResponse.class);
      }
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
hidden: true
line: 3
text: import org.springframework.ai.chat.memory.ChatMemory;
```

The `.advisors(a -> a.param(...))` call passes the conversation id to **every** registered advisor — both your RAG advisor and the tool-search advisor pick it up. Each call with the same `conversationId` lands in the same memory window; a fresh UUID gives the client a clean slate.

## Start the Support Assistant

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

## Test It

Send a request that requires a tool — without setting a header, so the controller mints a fresh UUID:

```terminal:execute
command: curl -G "http://localhost:8080/api/1.0/chat" --data-urlencode "query=Please open a high-priority ticket: SSO login returns 502 on the Tanzu portal."
session: 1
```

With `logging.level.org.springframework.ai=debug` already enabled in `application.properties`, you'll see in the assistant's logs:

1. A first model call where only `searchTools` is advertised.
2. The model emitting a `searchTools` call with the user's intent as the query.
3. The advisor running a similarity search against the `ToolIndex` and returning the top 5 hits — `createTicket` should be one of them.
4. A second model call with just those matched tools advertised; the model then picks `createTicket`.

Now try a multi-turn flow by reusing the same id:

```terminal:execute
command: |-
  CID=$(cat /proc/sys/kernel/random/uuid)

  curl -G "http://localhost:8080/api/1.0/chat" \
       -H "X-Conversation-Id: $CID" \
       --data-urlencode "query=What's the latest release of Spring Boot?"
session: 1
```

```terminal:execute
command: |-
  curl -G "http://localhost:8080/api/1.0/chat" \
       -H "X-Conversation-Id: $CID" \
       --data-urlencode "query=Please file a ticket asking the team to upgrade us to that version. High priority."
session: 1
```

The second call sees the conversation history from the first, so "that version" refers to the Spring Boot release the model fetched a moment ago — and the tool search advisor still picks the right action (`createTicket`) from the indexed pool.

A pure RAG question (no tool) confirms tool search adds zero overhead when no action is needed — the model never calls `searchTools`:

```terminal:execute
command: curl -G "http://localhost:8080/api/1.0/chat" --data-urlencode "query=What is Tanzu Spring Runtime?"
session: 1
```

## Stop the Applications

Stop the Support Assistant:

```terminal:interrupt
session: 2
```

And the MCP server:

```terminal:interrupt
session: 3
```
