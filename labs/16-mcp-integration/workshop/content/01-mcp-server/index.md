---
title: MCP Server
---

Your Support Assistant already uses tool calling — but the `SupportTicketService` tools live **in the same process** as the assistant. The **Model Context Protocol (MCP)** standardizes how one process exposes tools, resources, and prompts so that an AI application in another process can consume them.

In this lab, you'll build a small, separate **Spring Releases MCP server** that fetches live release data from `api.spring.io`, and then connect the Support Assistant to it as an MCP **client**. Questions like *"What's the latest release of Spring Boot?"* will then be answered from live data instead of the model's stale training knowledge.

## Build the MCP Server

The MCP server is a **second, separate** Spring Boot application. Scaffold it via Spring Initializr:

```terminal:execute
command: |-
  curl https://start.spring.io/starter.zip \
    -d dependencies=web,spring-ai-mcp-server \
    -d type=maven-project \
    -d groupId=com.example \
    -d artifactId=spring-releases-mcp-server \
    -d name=spring-releases \
    -d packageName=com.example.spring_releases \
    -d javaVersion=25 \
    -o spring-releases-mcp-server.zip && \
  unzip spring-releases-mcp-server.zip -d ~/spring-releases-mcp-server
session: 3
```

Because `web` is selected alongside it, the Initializr id `spring-ai-mcp-server` resolves to the WebMVC starter `spring-ai-starter-mcp-server-webmvc`, which provides the HTTP/Streamable transport. Note that, unlike the Support Assistant, the server doesn't need a model provider starter (OpenAI, Anthropic, Amazon Bedrock, or Ollama) — it only *exposes* tools over the protocol and never calls an LLM itself.

### Configure the Server

```editor:select-matching-text
file: ~/spring-releases-mcp-server/src/main/resources/application.properties
text: "spring.application.name=spring-releases"
description: "Apply - Configure the MCP server"
before: 0
after: 0
cascade: true
```

```editor:replace-text-selection
file: ~/spring-releases-mcp-server/src/main/resources/application.properties
hidden: true
text: |
  spring.application.name=spring-releases

  spring.ai.mcp.server.name=${spring.application.name}
  spring.ai.mcp.server.protocol=STREAMABLE
  spring.ai.mcp.server.version=1.0.0
  server.port=8081

  logging.level.io.modelcontextprotocol.server=DEBUG
```

The server will be reachable at `http://localhost:8081/mcp`. The `STREAMABLE` protocol matches what the Support Assistant client will be configured to call, and the server name `spring-releases` is what clients see when they introspect the connection.

### The Domain Record

Create a record that is a direct projection of what `api.spring.io` returns for a single release:

```editor:append-lines-to-file
file: ~/spring-releases-mcp-server/src/main/java/com/example/spring_releases/SpringRelease.java
description: "Create the SpringRelease record"
text: |
  package com.example.spring_releases;

  record SpringRelease(String version, String status, boolean current) {
  }
```

### The MCP Tool Service

Now create the service that exposes the release lookup as an MCP tool:

```editor:append-lines-to-file
file: ~/spring-releases-mcp-server/src/main/java/com/example/spring_releases/SpringReleasesInfoService.java
description: "Create the SpringReleasesInfoService"
text: |
  package com.example.spring_releases;

  import com.fasterxml.jackson.annotation.JsonProperty;
  import org.slf4j.Logger;
  import org.slf4j.LoggerFactory;
  import org.springframework.ai.mcp.annotation.McpTool;
  import org.springframework.ai.mcp.annotation.McpToolParam;
  import org.springframework.stereotype.Service;
  import org.springframework.web.client.RestClient;

  import java.util.List;

  @Service
  class SpringReleasesInfoService {

      private static final Logger log = LoggerFactory.getLogger(SpringReleasesInfoService.class);

      private final RestClient client = RestClient.create("https://api.spring.io");

      @McpTool(description = "Get all releases for a Spring project, including version and support status.")
      List<SpringRelease> fetchReleasesInfo(
              @McpToolParam(description = "The project slug, e.g. 'spring-boot', 'spring-framework', 'spring-ai'") String projectSlug) {
          log.info("Fetch spring release info for project {} called", projectSlug);

          return client.get()
                  .uri("/projects/{slug}/releases", projectSlug)
                  .retrieve()
                  .body(ReleasesResponse.class)
                  .embedded()
                  .releases();
      }

      private record ReleasesResponse(@JsonProperty("_embedded") Embedded embedded) {
          record Embedded(List<SpringRelease> releases) {
          }
      }
  }
```

Three things worth flagging:

- **`@McpTool` / `@McpToolParam`**, from `org.springframework.ai.mcp.annotation`, are the **MCP-specific** annotations — not the in-process `@Tool` / `@ToolParam` used in `SupportTicketService`. The MCP server auto-discovers any bean with `@McpTool` methods and registers them with the protocol.
- **`RestClient`** pulls live data from Spring's public release API. A real production tool would add error handling, caching, and retry; this version stays minimal so the protocol is what stands out.
- **The inner `ReleasesResponse` / `Embedded` records** model HAL's `_embedded` envelope — just Jackson plumbing for the API response.

### Run It

```terminal:execute
command: cd ~/spring-releases-mcp-server && ./mvnw spring-boot:run
session: 3
```

You should see the embedded MCP server start on port 8081 and log one registered tool at startup.

## Test the MCP Server Directly

The Streamable HTTP transport speaks JSON-RPC over HTTP. The first request must be `initialize`, which returns the session id in the `Mcp-Session-Id` response header; reuse that header on follow-up calls.

```terminal:execute
command: |-
  SESSION_ID=$(curl -sS -D - -o /dev/null -X POST http://localhost:8081/mcp \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -d '{
          "jsonrpc": "2.0",
          "id": 1,
          "method": "initialize",
          "params": {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": { "name": "curl", "version": "1" }
          }
        }' | grep -i '^mcp-session-id:' | awk '{print $2}' | tr -d '\r')

  echo "Session: $SESSION_ID"
session: 1
```

List the tools the server advertises:

```terminal:execute
command: |-
  curl -sS -X POST http://localhost:8081/mcp \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -H "Mcp-Session-Id: $SESSION_ID" \
    -d '{
          "jsonrpc": "2.0",
          "id": 2,
          "method": "tools/list",
          "params": {}
        }'
session: 1
```

You should see one entry — `fetchReleasesInfo`, its description, and the JSON schema for the `projectSlug` parameter.

Call the tool directly:

```terminal:execute
command: |-
  curl -sS -X POST http://localhost:8081/mcp \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -H "Mcp-Session-Id: $SESSION_ID" \
    -d '{
          "jsonrpc": "2.0",
          "id": 3,
          "method": "tools/call",
          "params": {
            "name": "fetchReleasesInfo",
            "arguments": { "projectSlug": "spring-boot" }
          }
        }'
session: 1
```

You'll get back the current Spring Boot releases array from `api.spring.io`, wrapped in MCP's content envelope. For further exploration, the MCP Inspector (`npx @modelcontextprotocol/inspector`) handles session management and pretty-prints the protocol — much nicer than raw curl.

## Summary

Your Spring Releases MCP server is up, exposing `fetchReleasesInfo` over the Streamable HTTP transport — and you've spoken the protocol to it directly. Keep it running: next, the Support Assistant connects to it as an MCP client.
