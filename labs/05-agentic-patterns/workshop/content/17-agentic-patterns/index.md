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
  			<artifactId>spring-ai-starter-tool-search-advisor</artifactId>
  		</dependency>
```

The starter brings the `ToolSearchToolCallingAdvisor` together with its auto configuration, plus the `ToolIndex` interface and `VectorToolIndex`, the index implementation that searches by meaning.

## Enable Tool Search

With the starter you do not write any wiring code. Three properties are enough.

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/application.properties
description: Enable the Tool Search advisor
text: |

  spring.ai.chat.client.tool-search-advisor.enabled=true
  spring.ai.chat.client.tool-search-advisor.tool-index-type=vector
  spring.ai.chat.client.tool-search-advisor.max-results=5
```

Here is what each of them does.

```editor:select-matching-text
file: ~/sample-app/src/main/resources/application.properties
text: "spring.ai.chat.client.tool-search-advisor.enabled=true"
before: 0
after: 0
description: Turn the advisor on
```

The auto configuration creates an instance and adds the  `ToolSearchToolCallingAdvisor`to the `ChatClient.Builder` for you, so the `chatClient` bean in `SupportAssistantConfiguration.java` stays exactly as it is and every `chatClient.prompt()` chain picks the advisor up. From now on `createTicket`, `retrieveTickets`, `retrieveOpenTickets`, and `fetchReleasesInfo` are no longer part of every prompt.

```editor:select-matching-text
file: ~/sample-app/src/main/resources/application.properties
text: "spring.ai.chat.client.tool-search-advisor.tool-index-type=vector"
before: 0
after: 0
description: Search the tools by meaning
```

The `vector` type creates a `VectorToolIndex` bean that works with the `VectorStore` you already have. The same `SimpleVectorStore` that holds your knowledge base documents now also holds the tool descriptions, stored as separate vectors. At startup the auto configuration walks through every available `ToolCallback`, the local ones as well as the ones from your MCP servers, and adds the name and description of each of them to the index.

```editor:select-matching-text
file: ~/sample-app/src/main/resources/application.properties
text: "spring.ai.chat.client.tool-search-advisor.max-results=5"
before: 0
after: 0
description: Cap the search at 5 results per turn
```

Only the five best matching tools are added to the next model call. Pick that number based on how many tools you have and how much context you want to spend on them.

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
