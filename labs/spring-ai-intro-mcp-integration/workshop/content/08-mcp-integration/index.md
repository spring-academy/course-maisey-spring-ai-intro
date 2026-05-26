---
title: MCP Integration
---

## Optional: Configure AI Provider

By default this lab uses the built-in **mock** provider — no API key needed.

To switch to real OpenAI models, set your API key:

```terminal:input
text: export OPENAI_API_KEY=
endl: false
session: 1
```

Then activate the OpenAI profile:

```terminal:execute
command: export SPRING_PROFILES_ACTIVE=openai
session: 1
```

---

## Add the MCP Server Dependency

```editor:select-matching-text
file: ~/sample-app/pom.xml
text: "</dependencies>"
description: Add MCP server dependency
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
              <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
          </dependency>
      </dependencies>
```

## Configure the MCP Server

```editor:select-matching-text
file: ~/sample-app/src/main/resources/application.yaml
text: "spring:"
description: Add MCP server configuration
before: 0
after: 0
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/resources/application.yaml
hidden: true
text: |
  spring:
    ai:
      mcp.server:
        name: ${spring.application.name}
        version: 1.0.0
        protocol: STREAMABLE
```

## Expose Existing Tools via MCP

Register the `DateTimeTool` and `TicketTool` with the MCP server using a `ToolCallbackProvider` bean:

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/supportassistant/SupportAssistantConfiguration.java
text: "import org.springframework.ai.vectorstore.VectorStore;"
description: Add ToolCallbackProvider imports
before: 0
after: 0
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/supportassistant/SupportAssistantConfiguration.java
hidden: true
text: |
  import org.springframework.ai.vectorstore.VectorStore;
  import org.springframework.ai.tool.ToolCallbackProvider;
  import org.springframework.ai.tool.method.MethodToolCallbackProvider;
  import com.example.supportassistant.tools.DateTimeTool;
  import com.example.supportassistant.tools.TicketTool;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/supportassistant/SupportAssistantConfiguration.java
text: "return SimpleVectorStore.builder(embeddingModel).build();"
description: Add ToolCallbackProvider bean
before: 0
after: 0
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/supportassistant/SupportAssistantConfiguration.java
hidden: true
text: |2
          return SimpleVectorStore.builder(embeddingModel).build();
      }

      @Bean
      public ToolCallbackProvider mcpToolProvider(DateTimeTool dateTimeTool, TicketTool ticketTool) {
		      return MethodToolCallbackProvider.builder().toolObjects(dateTimeTool, ticketTool).build();
```

## Start the MCP Server

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

The MCP server is available at `http://localhost:8080/mcp`.

## Test the MCP Endpoint

Initialize an MCP session:

```execute
SESSION_ID=$(curl -v -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream, application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test-client","version":"1.0.0"}}}' \
  2>&1 | grep -i "Mcp-Session-Id:" | awk '{print $3}' | tr -d '\r')

echo "Session ID: $SESSION_ID"
```

List the registered tools:

```execute
curl -X POST http://localhost:8080/mcp \
  -H "mcp-session-id: $SESSION_ID" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream, application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
```

You should see `getCurrentDateTime`, `createTicket`, and `listOpenTickets` listed.

Call the date/time tool via MCP:

```execute
curl -X POST http://localhost:8080/mcp \
  -H "mcp-session-id: $SESSION_ID" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream, application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getCurrentDateTime","arguments":{"timezone":"Europe/London"}}}'
```

## Stop the Application

```terminal:interrupt
session: 2
```
