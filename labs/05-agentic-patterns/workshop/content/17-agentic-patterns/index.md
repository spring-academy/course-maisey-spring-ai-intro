---
title: Agentic Patterns - Tool Search
---

Your support assistant has a growing number of tools. Three ticket tools run inside the application, and every MCP server you connect adds more on top, like the `fetchReleasesInfo` tool of the Spring Releases MCP server from the previous lab.

In this lab you put the **Tool Search Tool** in front of that list, so the model only sees the tools that fit the current request.

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

The dependency brings in the `ToolSearchToolCallingAdvisor`, the `ToolIndex` interface, and `VectorToolIndex`, the index implementation that searches by meaning.

## Configure the ToolIndex

`VectorToolIndex` works with the `VectorStore` you already have. The same `SimpleVectorStore` that holds your knowledge base documents will now also hold the tool descriptions, stored as separate vectors.

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

At startup the auto-configuration walks through every available `ToolCallback`, the local ones as well as the ones from your MCP servers, and adds the name and description of each of them to the index.

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

Here is what changed, one piece at a time.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "ToolIndex toolIndex) {"
before: 4
after: 0
description: "The ToolIndex bean is injected alongside the ToolCallbackProvider"
```

First, the **`ToolIndex` is injected** next to the existing `ToolCallbackProvider`, because the advisor needs the index to run its search.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "var toolSearchAdvisor = ToolSearchToolCallingAdvisor.builder()"
before: 0
after: 3
description: "Build the ToolSearchToolCallingAdvisor and cap it at 5 results per turn"
```

Next, the **advisor is built from that index**. With `maxResults(5)` only the five best matching tools are added to the next model call. Pick that number based on how many tools you have and how much context you want to spend on them.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "MessageChatMemoryAdvisor.builder(chatMemory).build(),"
before: 2
after: 1
description: "toolSearchAdvisor is registered next to the logger and memory advisors"
```

Finally, the **`toolSearchAdvisor` is added to `defaultAdvisors`**, next to the logger and memory advisors the bean already has, so every `chatClient.prompt()` chain picks it up automatically. From now on `createTicket`, `retrieveTickets`, `retrieveOpenTickets`, and `fetchReleasesInfo` are no longer part of every prompt.

The advisor needs more than one model call to do its work. In the first call the model searches for tools, and in the second one it calls them. This only works because the `ChatMemory` you configured earlier keeps the result of the search in the conversation.

## Start the Support Assistant

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

## Test It

Send a request that needs a tool. Do not set a header, so the controller creates a fresh UUID.

```terminal:execute
command: |
  curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Open a high-priority ticket for an auth issue with the Spring Enterprise Repository."
session: 1
```

With `logging.level.org.springframework.ai=debug` already enabled in `application.properties`, you can follow the whole flow in the assistant's logs.

1. A first model call where `searchTools` is the only tool that is offered.
2. The model calls `searchTools` with the intent of the user as the query.
3. The advisor searches the `ToolIndex` and returns the top 5 hits, and `createTicket` should be one of them.
4. A second model call that offers only those matched tools, and the model picks `createTicket`.

Now ask a question that needs no tool at all. The model answers from the knowledge base and never calls `searchTools`, so tool search costs you nothing when there is nothing to do.

```terminal:execute
command: curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=What are the key features of VMware Tanzu Spring?"
session: 1
```
