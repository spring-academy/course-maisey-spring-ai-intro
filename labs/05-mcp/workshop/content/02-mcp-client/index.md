---
title: Consuming Tools With an MCP Client
---

The Spring Releases MCP server is now running on port 8090. Next you connect the Support Assistant to it as an MCP **client**. The model can then call the remote `fetchReleasesInfo` tool next to its in-process ticket tools.

## Add the MCP Client Dependency

In the Support Assistant project, add the MCP client starter to the `pom.xml`.

```editor:select-matching-text
file: ~/sample-app/pom.xml
text: "<artifactId>spring-ai-markdown-document-reader</artifactId>"
description: Add the Spring AI MCP client starter
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
  			<artifactId>spring-ai-markdown-document-reader</artifactId>
  		</dependency>
  		<dependency>
  			<groupId>org.springframework.ai</groupId>
  			<artifactId>spring-ai-starter-mcp-client</artifactId>
  		</dependency>
```

For every named connection in your configuration, the starter creates one MCP client. It then exposes a single `ToolCallbackProvider` bean that gathers every remote tool.

## Configure the MCP Client

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/application.properties
description: "Apply - Configure the MCP client connection"
text: |
  spring.ai.mcp.client.streamable-http.connections.spring-releases.url=http://localhost:8090

  # Verbose protocol logging while you learn. Turn these off in production.
  logging.level.io.modelcontextprotocol.client=DEBUG
  logging.level.io.modelcontextprotocol.spec=DEBUG
```

The `spring-releases` segment is the connection name. It becomes a prefix on the imported tools, for example `spring-releases_fetchReleasesInfo`. This way the model can tell remote tools apart from each other and from the in-process ones.

## Register the Remote Tools With the ChatClient

You could pass the callbacks into every `chatClient.prompt()` call or, as in this case, once as default tools on the `ChatClient` bean in `SupportAssistantConfiguration.java`.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "public ChatClient chatClient(ChatClient.Builder builder,"
description: "Register the MCP tools as default tools"
before: 1
after: 10
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
                                   ToolCallbackProvider tools) {
          return builder
                  .defaultSystem(systemPrompt)
                  .defaultAdvisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                  .defaultAdvisors(
                          new SimpleLoggerAdvisor(Ordered.LOWEST_PRECEDENCE),
                          MessageChatMemoryAdvisor.builder(chatMemory).build())
                  .defaultTools(tools)
                  .build();
      }
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
hidden: true
line: 3
text: import org.springframework.ai.tool.ToolCallbackProvider;
```

There are two changes.

- **Inject `ToolCallbackProvider tools`**. Spring AI's MCP client auto-configuration provides this bean for you, and it wraps every connection from your configuration.
- **`.defaultTools(tools)`**. Every call through this `ChatClient` now sees the MCP tools, with no extra wiring. `SupportAssistantService` does not change.

The in-process ticket tools are still added per call with `.tools(supportTicketService)` in `generateResponse`. Default tools and per-call tools are combined, so the model sees both groups.

## Start the Support Assistant

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

In the logs you will see the MCP client connect to `http://localhost:8090/mcp` on startup.

## Test It via the Assistant

```terminal:execute
command: curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=What is the latest stable release of Spring Boot?"
session: 1
```

In the assistant's logs you will see the model make a `spring-releases_fetchReleasesInfo` tool call with `{"projectSlug": "spring-boot"}`. The result is then fed back into the model for the final answer.

Now try a query that uses both the remote MCP tool and an in-process tool in a single conversation turn.

```terminal:execute
command: curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=What is the latest release of Spring Boot? Please also open a high-priority ticket to request access to Spring Application Advisor to accelerate upgrading our application to that version."
session: 1
```

The model calls `fetchReleasesInfo`, the remote MCP tool, and `createTicket`, the in-process tool, in one turn.
