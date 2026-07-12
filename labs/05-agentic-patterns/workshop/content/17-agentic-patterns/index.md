---
title: Agentic Patterns
---

Your Support Assistant has a growing set of tools. It has three in-process ticket tools. It also has every tool advertised by every connected MCP server, like the Spring Releases MCP server you connected in the previous lab.

This creates a problem. **Every chat call sends the full tool schema to the model**, even when none of those tools can help with the question. Every tool description costs tokens on every request. The model also has more chances to pick the wrong tool, and the prompt keeps growing as you add MCP servers.

The fix is the **Tool Search Tool**. This is an agentic pattern. The model gets a *single* meta-tool, called something like `searchTools`, plus an index that embeds every real tool's description into the vector store. When the model needs to act, it calls `searchTools` with a natural-language query. It gets back the most relevant tools, and only those flow into the next turn. It is RAG, but for tools.

## Start the Spring Releases MCP Server

The Spring Releases MCP server from the MCP lab is already part of this workshop environment. Start it so its `fetchReleasesInfo` tool is available to the Support Assistant.

```terminal:execute
command: cd ~/spring-releases-mcp-server && ./mvnw spring-boot:run
session: 3
```

You should see the embedded MCP server start on port 8090 and log one registered tool at startup.

## Add the Tool Search Advisor Dependency

Add the Tool Search advisor to the Support Assistant's `pom.xml`.

```editor:select-matching-text
file: ~/sample-app/pom.xml
text: "<artifactId>spring-ai-starter-mcp-client</artifactId>"
description: "Add the Spring AI Tool Search advisor"
before: 2
after: 1
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/pom.xml
hidden: true
text: |2
  		<dependency>
  			<groupId>org.springframework.ai</groupId>
  			<artifactId>spring-ai-starter-mcp-client</artifactId>
  		</dependency>
  		<dependency>
  			<groupId>org.springframework.ai</groupId>
  			<artifactId>spring-ai-tool-search-advisor</artifactId>
  		</dependency>
```

This pulls in `ToolSearchToolCallingAdvisor`, `ToolIndex`, and the default implementation backed by the vector store, `VectorToolIndex`.

## Configure the ToolIndex

The index stores the tool descriptions for similarity search. The default `VectorToolIndex` reuses your existing `VectorStore`. So the same `SimpleVectorStore` that holds the knowledge base chunks also holds the tool descriptions, in separate vectors.

Add the bean to `SupportAssistantConfiguration.java`.
```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "class SupportAssistantConfiguration {"
description: "Configure the ToolIndex"
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

On startup, the auto-configuration goes through every available `ToolCallback`, both in-process and MCP, and embeds its name and description into the index. From this point on, the model never sees those tools directly. It sees only `searchTools`.

## Add the ToolSearchToolCallingAdvisor to the ChatClient

Register the advisor as a **default** on the `ChatClient` bean so every call goes through it. Update the `chatClient` factory method in `SupportAssistantConfiguration.java`.
```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "public ChatClient chatClient(ChatClient.Builder builder,"
description: "Add the Tool Search advisor to the ChatClient"
before: 1
after: 11
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
hidden: true
cascade: true
text: |2
      @Bean
      public ChatClient chatClient(ChatClient.Builder builder,
                                   @Value("classpath:/prompts/system-prompt.st") Resource systemPrompt,
                                   ChatMemory chatMemory,
                                   ToolCallbackProvider tools,
                                   ToolIndex toolIndex) {
          var toolSearchAdvisor = ToolSearchToolCallingAdvisor.builder()
                  .toolIndex(toolIndex)
                  .maxResults(5)
                  .build();

          return builder
                  .defaultSystem(systemPrompt)
                  .defaultAdvisors(
                          new SimpleLoggerAdvisor(Ordered.LOWEST_PRECEDENCE),
                          MessageChatMemoryAdvisor.builder(chatMemory).build(),
                          toolSearchAdvisor)
                  .defaultTools(tools)
                  .build();
      }
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
hidden: true
line: 3
text: import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
```

What changed.

- **`ToolIndex toolIndex` is injected** next to the existing `ToolCallbackProvider`.
- **`maxResults(5)`**. The advisor injects the 5 most relevant tools per turn. Tune this for your tool count and model context budget.
- **`toolSearchAdvisor` added to `defaultAdvisors`**. It sits next to the logger and memory advisors the bean already has, so every `chatClient.prompt()` chain picks it up.

Behind the scenes, when the model decides to act, the advisor does this.

1. Intercepts the call before tools are sent to the model.
2. Replaces the full tool list with just the `searchTools` meta-tool.
3. When the model calls `searchTools("create a ticket about login failures")`, runs a similarity search against the `ToolIndex`.
4. Surfaces the matching tools to the model on the next turn so it can actually invoke them.

The full list of `createTicket`, `retrieveTickets`, `retrieveOpenTickets`, `fetchReleasesInfo`, and the rest is no longer in every prompt.

## Wire Conversation Memory Through a Conversation ID

The advisor is multi-turn by design. Turn 1 is "search for tools". Turn 2 is "now call them". For this to work, the framework needs `ChatMemory` and a **conversation id** so it can connect the two turns. `ChatMemory` is auto-configured once the advisor is on the classpath, with `MessageWindowChatMemory` and an in-memory repository. The only thing you supply is the conversation id per request.

See the [Spring AI Tool Search Tool documentation](https://docs.spring.io/spring-ai/reference/2.0/api/tools.html#tool-search-tool) for the framework contract.

### Accept the Conversation ID as a Header

Update `SupportAssistantController.java` to read `X-Conversation-Id` from the request. When the client does not supply one, fall back to a generated UUID.

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

`@RequestHeader.defaultValue` only takes string literals, so you use a null check with a `UUID.randomUUID()` fallback instead. A client that wants a continuous conversation supplies a stable id. This is the typical case for an agentic assistant, where turn N+1 needs to see the history from turn N.

### Pass the ID Into the Chain

Update `SupportAssistantService.generateResponse` to accept the id and forward it through the advisor param. The framework looks for it under `ChatMemory.CONVERSATION_ID`.

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

The `.advisors(a -> a.param(...))` call passes the conversation id to **every** registered advisor. Both your RAG advisor and the tool-search advisor pick it up. Each call with the same `conversationId` lands in the same memory window. A fresh UUID gives the client a clean slate.

## Start the Support Assistant

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

## Test It

Send a request that needs a tool. Do not set a header, so the controller creates a fresh UUID.

```terminal:execute
command: curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Please open a high-priority ticket: SSO login returns 502 on the Tanzu portal."
session: 1
```

With `logging.level.org.springframework.ai=debug` already enabled in `application.properties`, you will see this in the assistant's logs.

1. A first model call where only `searchTools` is advertised.
2. The model emits a `searchTools` call with the user's intent as the query.
3. The advisor runs a similarity search against the `ToolIndex` and returns the top 5 hits. `createTicket` should be one of them.
4. A second model call with only those matched tools advertised. The model then picks `createTicket`.

Now try a multi-turn flow by reusing the same id.

```terminal:execute
command: |-
  CID=$(cat /proc/sys/kernel/random/uuid)

  curl -G "http://localhost:8080/api/v1/chat" \
       -H "X-Conversation-Id: $CID" \
       --data-urlencode "query=What's the latest release of Spring Boot?"
session: 1
```

```terminal:execute
command: |-
  curl -G "http://localhost:8080/api/v1/chat" \
       -H "X-Conversation-Id: $CID" \
       --data-urlencode "query=Please file a ticket asking the team to upgrade us to that version. High priority."
session: 1
```

The second call sees the conversation history from the first. So "that version" refers to the Spring Boot release the model fetched a moment ago. The tool search advisor still picks the right action, `createTicket`, from the indexed pool.

A pure RAG question, with no tool, confirms that tool search adds no overhead when no action is needed. The model never calls `searchTools`.

```terminal:execute
command: curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=What is Tanzu Spring Runtime?"
session: 1
```

## Stop the Applications

Stop the Support Assistant.

```terminal:interrupt
session: 2
```

Then stop the MCP server.

```terminal:interrupt
session: 3
```
