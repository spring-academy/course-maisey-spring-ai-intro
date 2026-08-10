---
title: Agentic Patterns - Tool Search
---

Your support assistant has a growing set of tools. It has three in-process ticket tools. It also has every tool advertised by every connected MCP server, like the Spring Releases MCP server you connected in the previous lab.

This creates a problem. **Every chat call sends the full tool schema to the model**, even when none of those tools can help with the question. Every tool description costs tokens on every request. The model also has more chances to pick the wrong tool, and the prompt keeps growing as you add MCP servers.

The fix is the **Tool Search Tool**. This is an agentic pattern. The model gets a *single* meta-tool, called something like `searchTools`, plus an index that embeds every real tool's description into the vector store. When the model needs to act, it calls `searchTools` with a natural-language query. It gets back the most relevant tools, and only those flow into the next turn. It is RAG, but for tools.

## Start the Spring Releases MCP Server

The Spring Releases MCP server from the MCP lab is already part of this workshop environment. Start it so its `fetchReleasesInfo` tool is available to the support assistant.

```terminal:execute
command: cd ~/spring-releases-mcp-server && ./mvnw spring-boot:run
session: 3
```

You should see the embedded MCP server start on port 8090 and log one registered tool at startup.

## Add the Tool Search Advisor Dependency

Add the Tool Search advisor to the support assistant's `pom.xml`.

```editor:select-matching-text
file: ~/sample-app/pom.xml
text: "<artifactId>mcp-client-security-spring-boot</artifactId>"
description: "Add the Spring AI Tool Search advisor"
before: 2
after: 2
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/pom.xml
hidden: true
text: |2
  		<dependency>
  			<groupId>org.springaicommunity</groupId>
  			<artifactId>mcp-client-security-spring-boot</artifactId>
  			<version>0.1.13</version>
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

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
cascade: true
description: "Configure the ToolIndex"
line: 3
text: |-
  import org.springframework.ai.tool.toolsearch.ToolIndex;
  import org.springframework.ai.tool.toolsearch.index.vectorstore.VectorToolIndex;
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
line: 71
hidden: true
text: |2

      @Bean
      ToolIndex toolIndex(VectorStore vectorStore) {
          return new VectorToolIndex(vectorStore);
      }
```

On startup, the auto-configuration goes through every available `ToolCallback`, both in-process and MCP, and embeds its name and description into the index. From this point on, the model never sees those tools directly. It sees only `searchTools`.

## Add the ToolSearchToolCallingAdvisor to the ChatClient

Register the advisor as a default on the `ChatClient` bean so every call goes through it. Update the `chatClient` factory method in `SupportAssistantConfiguration.java`.
```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "public ChatClient chatClient(ChatClient.Builder builder,"
description: "Add the Tool Search advisor to the ChatClient"
before: 1
after: 12
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
                  .defaultAdvisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
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

Walk through what changed, one piece at a time.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "ToolIndex toolIndex) {"
before: 4
after: 0
description: "The ToolIndex bean is injected alongside the ToolCallbackProvider"
```

First, the **`ToolIndex` is injected** next to the existing `ToolCallbackProvider`. The advisor needs the index to run its similarity search.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "var toolSearchAdvisor = ToolSearchToolCallingAdvisor.builder()"
before: 0
after: 3
description: "Build the ToolSearchToolCallingAdvisor and cap it at 5 results per turn"
```

Next, the **advisor is built from that index**. `maxResults(5)` means the advisor injects only the 5 most relevant tools per turn. Tune this for your tool count and model context budget.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "MessageChatMemoryAdvisor.builder(chatMemory).build(),"
before: 2
after: 1
description: "toolSearchAdvisor is registered next to the logger and memory advisors"
```

Finally, the **`toolSearchAdvisor` is added to `defaultAdvisors`**. It sits next to the logger and memory advisors the bean already has, so every `chatClient.prompt()` chain picks it up automatically.

Behind the scenes, when the model decides to act, the advisor does this.

1. Intercepts the call before tools are sent to the model.
2. Replaces the full tool list with just the `searchTools` meta-tool.
3. When the model calls `searchTools("create a ticket about login failures")`, runs a similarity search against the `ToolIndex`.
4. Surfaces the matching tools to the model on the next turn so it can actually invoke them.

The full list of `createTicket`, `retrieveTickets`, `retrieveOpenTickets`, `fetchReleasesInfo`, and the rest is no longer in every prompt.

The advisor is multi-turn by design. Turn 1 is "search for tools". Turn 2 is "now call them". For this to work, the framework needs the `ChatMemory` we have already configured.

## Start the Support Assistant

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

## Test It

Send a request that needs a tool. Do not set a header, so the controller creates a fresh UUID.

```terminal:execute
command: |
  curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Please open a high-priority ticket: SSO login returns 502 on the Tanzu portal."
session: 1
```

With `logging.level.org.springframework.ai=debug` already enabled in `application.properties`, you will see this in the assistant's logs.

1. A first model call where only `searchTools` is advertised.
2. The model emits a `searchTools` call with the user's intent as the query.
3. The advisor runs a similarity search against the `ToolIndex` and returns the top 5 hits. `createTicket` should be one of them.
4. A second model call with only those matched tools advertised. The model then picks `createTicket`.

A pure RAG question, with no tool, confirms that tool search adds no overhead when no action is needed. The model never calls `searchTools`.

```terminal:execute
command: curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=What is Tanzu Spring Runtime?"
session: 1
```