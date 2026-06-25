---
title: MCP Client
---

With the Spring Releases MCP server running on port 8081, it's time to connect the Support Assistant to it as an MCP **client**, so the model can call the remote `fetchReleasesInfo` tool alongside its in-process ticket tools.

## Configure the AI Provider

Spring AI supports many chat model providers, such as OpenAI, Anthropic, Amazon Bedrock, and Ollama. In this workshop, we use **OpenAI**. The sample application also ships Spring profiles for the alternatives (`application-anthropic.properties`, `application-ollama.properties`, `application-bedrock-converse.properties`) — set the related credentials and `SPRING_PROFILES_ACTIVE` accordingly if you want to try one of them instead.

Set your OpenAI API key in the terminal session the Support Assistant will run in:

```terminal:input
text: export OPENAI_API_KEY=
endl: false
session: 2
```

## Add the MCP Client Dependency

In the Support Assistant project, add the MCP client starter to the `pom.xml`:

```editor:select-matching-text
file: ~/sample-app/pom.xml
text: "</dependencies>"
description: "Apply - Add the Spring AI MCP client starter"
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
  			<artifactId>spring-ai-starter-mcp-client</artifactId>
  		</dependency>
  	</dependencies>
```

The starter auto-configures one MCP client per named connection it finds in the configuration and exposes a single `ToolCallbackProvider` bean that aggregates every remote tool.

## Configure the MCP Client

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/application.properties
description: "Apply - Configure the MCP client connection"
text: |
  spring.ai.mcp.client.streamable-http.connections.spring-releases.url=http://localhost:8081

  # Verbose protocol logging while you're learning - drop these in production
  logging.level.io.modelcontextprotocol.client=DEBUG
  logging.level.io.modelcontextprotocol.spec=DEBUG
```

The `spring-releases` segment is the connection name. It shows up as a prefix on the imported tools (e.g. `spring-releases_fetchReleasesInfo`) so the model can tell remote tools apart from each other and from in-process ones.

## Register the Remote Tools With the ChatClient

Instead of threading the callbacks into every `chatClient.prompt()` call site, register them once as **default tools** on the `ChatClient` bean in `SupportAssistantConfiguration.java`:

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder, ToolCallbackProvider tools) {
    return builder
            .defaultSystem("You are a support agent for the Spring framework. Answer clearly and always include a link to the relevant official docs when one exists, never inventing URLs.")
            .defaultTools(tools)
            .build();
}
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "ChatClient chatClient(ChatClient.Builder builder) {"
description: "Apply - Register the MCP tools as default tools"
before: 0
after: 1
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
hidden: true
cascade: true
text: |2
      ChatClient chatClient(ChatClient.Builder builder, ToolCallbackProvider tools) {
          return builder
                  .defaultSystem("You are a support agent for the Spring framework. Answer clearly and always include a link to the relevant official docs when one exists, never inventing URLs.")
                  .defaultTools(tools)
                  .build();
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
hidden: true
line: 3
text: import org.springframework.ai.tool.ToolCallbackProvider;
```

Two changes:

- **Inject `ToolCallbackProvider tools`** — Spring AI's MCP client auto-configuration provides this bean automatically; it wraps every connection from your configuration.
- **`.defaultTools(tools)`** — every call through this `ChatClient` now sees the MCP tools without further wiring. `SupportAssistantService` doesn't change.

The in-process ticket tools are still attached per-call via `.tools(supportTicketService)` in `generateResponse`. Defaults and per-call tools merge — the model sees both groups.

## Start the Support Assistant

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

In the logs, you'll see the MCP client connecting to `http://localhost:8081/mcp` on startup.

## Test It via the Assistant

```terminal:execute
command: curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=What is the latest stable release of Spring Boot?"
session: 1
```

In the assistant's logs you'll see the model emitting a `spring-releases_fetchReleasesInfo` tool call with `{"projectSlug": "spring-boot"}`, and the result being fed back into the model for the final answer.

Now try a query that exercises both the remote MCP tool and an in-process tool in a single conversational turn:

```terminal:execute
command: curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Check the latest Spring AI release, and if we're behind the current GA, please open a high-priority ticket about upgrading."
session: 1
```

The model calls `fetchReleasesInfo` (MCP, remote) and `createTicket` (in-process tool) in one turn.

## Stop the Applications

Stop the Support Assistant:

```terminal:interrupt
session: 2
```

And the MCP server:

```terminal:interrupt
session: 3
```
