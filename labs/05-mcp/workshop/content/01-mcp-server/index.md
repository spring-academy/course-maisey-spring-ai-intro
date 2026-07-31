---
title: Exposing Tools With an MCP Server
---

Your support assistant already uses tool calling. But the `SupportTicketService` tools run **in the same process** as the assistant. The **Model Context Protocol (MCP)** is a standard way for one process to expose tools, resources, and prompts. An AI application in another process can then use them.

In this lab you build a small, separate **Spring Releases MCP server**. It fetches live release data from `api.spring.io`. Then you connect the support assistant to it as an MCP **client**. After that, a question like *"What's the latest release of Spring Boot?"* is answered from live data instead of the model's old training knowledge.

## Build the MCP Server

The MCP server is a **second, separate** Spring Boot application. You can generate the project with the [Spring Initializr](https://start.spring.io) using the following command.
{{< note >}}
You don't have to run this. The generated project is already prepared for you in `~/spring-releases-mcp-server`, and the rest of this lab edits the files in that folder.
{{< /note >}}

```bash
  curl https://start.spring.io/starter.zip \
    -d dependencies=web,spring-ai-mcp-server,devtools \
    -d type=maven-project \
    -d groupId=com.example \
    -d artifactId=spring-releases-mcp-server \
    -d name=spring-releases \
    -d packageName=com.example.spring_releases \
    -d javaVersion=21 \
    -o spring-releases-mcp-server.zip
```

Because you also selected `web`, the Initializr id `spring-ai-mcp-server` resolves to the WebMVC starter `spring-ai-starter-mcp-server-webmvc`. 

Now look at what the starter added to the project. First the provider-specific dependency.

```editor:select-matching-text
file: ~/spring-releases-mcp-server/pom.xml
text: "spring-ai-starter-mcp-server-webmvc"
```

That starter provides the HTTP Streamable transport. Unlike the support assistant, this server does not need a model provider starter such as OpenAI, Anthropic, Amazon Bedrock, or Ollama. It only *exposes* tools over the protocol and never calls an LLM itself.

### Configure the Server

The generated project ships with an almost empty `application.properties` file. Spring AI can already run an MCP server with those defaults, but a few properties are worth setting yourself. They control the identity the server shows to clients, the transport it speaks, and more.

```editor:append-lines-to-file
file: ~/spring-releases-mcp-server/src/main/resources/application.properties
description: "Configure the MCP server"
text: |

  spring.ai.mcp.server.name=${spring.application.name}
  spring.ai.mcp.server.protocol=STREAMABLE
  spring.ai.mcp.server.version=1.0.0
  server.port=8090

  logging.level.io.modelcontextprotocol.server=DEBUG
```

The server will be reachable at `http://localhost:8090/mcp`. The `STREAMABLE` protocol matches what the support assistant client will call. The server name `spring-releases` is what clients see when they inspect the connection.
We also turn on debug logging so you can watch every JSON-RPC message the server sends and receives. That logging is helpful while you learn the protocol, but you should switch it off in production.

### The MCP Tool Service

Create a record that maps directly to what `api.spring.io` returns for a single release.

```editor:append-lines-to-file
file: ~/spring-releases-mcp-server/src/main/java/com/example/spring_releases/SpringRelease.java
description: "Create the SpringRelease record"
text: |
  package com.example.spring_releases;

  record SpringRelease(String version, String status, boolean current) {
  }
```

Now create the service that exposes the release lookup as an MCP tool.

```editor:append-lines-to-file
file: ~/spring-releases-mcp-server/src/main/java/com/example/spring_releases/SpringReleasesInfoService.java
description: "Create the SpringReleasesInfoService"
cascade: true
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

```editor:select-matching-text
file: ~/spring-releases-mcp-server/src/main/java/com/example/spring_releases/SpringReleasesInfoService.java
hidden: true
text: "@McpTool(description"
```

The `@McpTool` annotation is the **MCP-specific** version of the in-process `@Tool` you used in `SupportTicketService`. At startup the server scans every bean for methods that carry it and registers them with the protocol. The description is written for the model, because a client hands exactly this text to the LLM when it decides which tool fits a question.

The parameter gets its own annotation.

```editor:select-matching-text
file: ~/spring-releases-mcp-server/src/main/java/com/example/spring_releases/SpringReleasesInfoService.java
text: "@McpToolParam(description"
```

`@McpToolParam` is the MCP counterpart of `@ToolParam`. Spring AI builds the JSON schema of the tool from the method signature, and this text tells the model what a valid project slug looks like. You will see that schema later in this lab when you ask the server for its tool list.

Everything below the annotations is plain Spring.

```editor:select-matching-text
file: ~/spring-releases-mcp-server/src/main/java/com/example/spring_releases/SpringReleasesInfoService.java
text: "RestClient.create"
```

The `RestClient` pulls live data from Spring's public release API, and the method simply returns a `List<SpringRelease>`. Spring AI turns that list into JSON before it goes back over the protocol, so you never touch the wire format yourself. A production tool would add error handling, caching, and retries here. This version stays simple so the protocol is what stands out.

The last two records are only plumbing.

```editor:select-matching-text
file: ~/spring-releases-mcp-server/src/main/java/com/example/spring_releases/SpringReleasesInfoService.java
text: "private record ReleasesResponse"
before: 0
after: 3
```

`api.spring.io` wraps its results in an `_embedded` object, so these nested records exist only to unwrap the response for Jackson. They have nothing to do with MCP.

### Run It

```terminal:execute
command: cd ~/spring-releases-mcp-server && ./mvnw spring-boot:run
session: 3
```

You should see the embedded MCP server start on port 8090 and log one registered tool at startup.

## Test the MCP Server Directly

The Streamable HTTP transport speaks JSON-RPC over HTTP. The first request must be `initialize`. It returns the session id in the `Mcp-Session-Id` response header. Reuse that header on the calls that follow.

```terminal:execute
description: "Open an MCP session and keep its session id"
command: |-
  SESSION_ID=$(curl -sS -D - -o /dev/null -X POST http://localhost:8090/mcp \
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

List the tools the server advertises.

```terminal:execute
description: "List the tools the server offers"
command: |-
  curl -sS -X POST http://localhost:8090/mcp \
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

You should see one entry. It is `fetchReleasesInfo`, with its description and the JSON schema for the `projectSlug` parameter.

Call the tool directly.

```terminal:execute
description: "Call the fetchReleasesInfo tool"
command: |-
  curl -sS -X POST http://localhost:8090/mcp \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -H "Mcp-Session-Id: $SESSION_ID" \
    -d '{
          "jsonrpc": "2.0",
          "id": 3,
          "method": "tools/call",
          "params": {
            "name": "fetchReleasesInfo",
            "arguments": { "projectSlug": "spring-ai" }
          }
        }'
session: 1
```

You get back the current Spring Boot releases array from `api.spring.io`, wrapped in MCP's content envelope.