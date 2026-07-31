---
title: Consuming Tools With an MCP Client
---

The Spring Releases MCP server is now running on port 8090. Next you connect the support assistant to it as an MCP **client**. The model can then call the remote `fetchReleasesInfo` tool next to its in-process ticket tools.

## Connect to the MCP Server

In the support assistant project, add the MCP client starter to the `pom.xml`.

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

Now declare the connection itself.

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/application.properties
description: "Configure the MCP client connection"
text: |

  spring.ai.mcp.client.streamable-http.connections.spring-releases.url=http://localhost:8090

  # Verbose protocol logging while you learn. Turn these off in production.
  logging.level.io.modelcontextprotocol.client=DEBUG
  logging.level.io.modelcontextprotocol.spec=DEBUG
```

The `spring-releases` segment is the custom connection name.

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

Two things changed in the bean. The first one is a new constructor parameter.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "ToolCallbackProvider tools) {"
```

The MCP client auto-configuration contributes this `ToolCallbackProvider` bean for you, and it already gathers the tools of every connection from your configuration. All you have to do is inject it.

The second change hands those callbacks to the builder.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: ".defaultTools(tools)"
```

Every call through this `ChatClient` can now reach the remote tools without any further wiring, which is why `SupportAssistantService` stays untouched.

The in-process ticket tools are still added for a single call.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: ".tools(supportTicketService)"
```

Default tools and per-call tools are combined, so the model sees both groups in the same request.

## Try It Out

Start the support assistant.

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

In the logs you will see the MCP client connect to `http://localhost:8090/mcp` on startup. Now ask it a question that only the remote tool can answer.

```terminal:execute
command: curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=What is the latest stable release of Spring AI?"
session: 1
```

In the assistant's logs you will see the model make a `fetchReleasesInfos` tool call with `{"projectSlug": "spring-ai"}`. The result is then fed back into the model for the final answer.

Now try a query that uses both the remote MCP tool and an in-process tool in a single conversation turn.

```terminal:execute
command: curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=What is the latest stable release of Spring AI? Please also open a high-priority ticket to request access to Spring Application Advisor to accelerate upgrading our application to that version."
session: 1
```

The model calls `fetchReleasesInfo`, the remote MCP tool, and `createTicket`, the in-process tool, in one turn.
