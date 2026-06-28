---
title: Tool Calling
---

# Giving the Model Tools

This is where Spring AI's tool calling shows up. Annotate methods with `@Tool` and the framework will:

1. Generate a JSON schema describing the tool from the method signature.
2. Pass those schemas to the model with each call.
3. When the model emits a tool call, invoke the method with the model-supplied arguments.
4. Feed the return value back into the model so it can finish its answer.

## The Tool-Bearing Service

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportTicketService.java
description: Create SupportTicketService
text: |
  package com.example.support_assistant;

  import org.springframework.ai.tool.annotation.Tool;
  import org.springframework.ai.tool.annotation.ToolParam;
  import org.springframework.stereotype.Service;

  import java.util.List;

  @Service
  class SupportTicketService {

      private final SupportTicketRepository ticketRepository;

      SupportTicketService(SupportTicketRepository ticketRepository) {
          this.ticketRepository = ticketRepository;
      }

      @Tool(description = "Create a new support ticket. Use this when the user explicitly requests to create, open, or file a support ticket.")
      SupportTicket createTicket(
              @ToolParam(description = "Brief summary of the issue (max 100 chars)") String summary,
              @ToolParam(description = "The category of the issue") SupportCategory category,
              @ToolParam(description = "The priority of the support ticket") SupportTicket.Priority priority) {
          var ticket = new SupportTicket(summary, category, priority);
          return ticketRepository.save(ticket);
      }

      @Tool(description = "List all support tickets")
      List<SupportTicket> retrieveTickets() {
          return ticketRepository.findAll();
      }

      @Tool(description = "List all support tickets that are not yet resolved")
      List<SupportTicket> retrieveOpenTickets() {
          return ticketRepository.findByStatus("OPEN");
      }
  }
```

Two annotations do the heavy lifting:

- **`@Tool(description = "...")`** — what the tool does, in the model's voice. This is the most important text in the whole step: the model decides whether to call the tool based on it, so be explicit about the trigger (here: "explicitly requests to create, open, or file a support ticket").
- **`@ToolParam(description = "...")`** — describes each parameter. The model uses these to fill the arguments correctly. Enum types are turned into the model's available choices automatically.

## Register the Tools on the ChatClient Chain

Inject `SupportTicketService` into `SupportAssistantService`:

```java
private final SupportTicketService supportTicketService;

SupportAssistantService(ChatClient chatClient, VectorStore vectorStore, SupportTicketService supportTicketService) {
    this.chatClient = chatClient;
    this.vectorStore = vectorStore;
    this.supportTicketService = supportTicketService;
}
```

And add `.tools(supportTicketService)` to the chain in `generateResponse`:

```java
return chatClient.prompt()
        .user(u -> u
                .text("Answer the following question with a short, well-structured explanation: {question}")
                .param("question", query))
        .advisors(ragAdvisor)
        .tools(supportTicketService)
        .call()
        .entity(SupportResponse.class);
```

Click to apply:

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "private final VectorStore vectorStore;"
before: 0
after: 0
description: Apply - register the tools on the ChatClient chain
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
cascade: true
hidden: true
text: |2
      private final VectorStore vectorStore;
      private final SupportTicketService supportTicketService;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "SupportAssistantService(ChatClient chatClient, VectorStore vectorStore) {"
before: 0
after: 3
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
cascade: true
hidden: true
text: |2
      SupportAssistantService(ChatClient chatClient, VectorStore vectorStore, SupportTicketService supportTicketService) {
          this.chatClient = chatClient;
          this.vectorStore = vectorStore;
          this.supportTicketService = supportTicketService;
      }
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: ".advisors(ragAdvisor)"
before: 0
after: 0
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
cascade: true
hidden: true
text: |2
              .advisors(ragAdvisor)
              .tools(supportTicketService)
```

`.tools(Object)` registers every `@Tool`-annotated method on the bean. Spring AI handles the multi-turn dance — emit schemas → model returns tool calls → execute → feed results back → final answer — transparently.

To watch that dance in the logs, enable debug logging for Spring AI (optional):

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/application.properties
description: Optional - enable Spring AI debug logging
text: |

  logging.level.org.springframework.ai=debug
```

## Try It Out

Restart the application:

```terminal:interrupt
session: 2
```

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

Ask the assistant to file a ticket:

```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Please open a ticket: Trial request for Tanzu Spring. Treat it as high priority."
```

You should see a response confirming the ticket was created. With debug logging enabled, the logs in the second terminal show the `createTicket` call with the model's chosen arguments.

Then list the open tickets through the assistant:

```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Show me all open support tickets."
```

The model picks `retrieveOpenTickets`, gets the rows from the DB, and returns them as part of the response.

Finally, mix it with RAG by asking something that's both informational and operational:

```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Does Tanzu Spring provide support for Spring Boot 2.7? If yes, open a ticket to request a trial of Tanzu Spring"
```

The advisor pulls the answer from the knowledge base; the tool call (if the model judges it warranted) files the ticket.

## Recap

| Step | What changed |
|------|--------------|
| 1 | Added `spring-boot-starter-data-jdbc` + H2 driver (Postgres users reuse their pgvector datasource) |
| 2 | Configured the H2 datasource |
| 3 | `schema.sql` creates `support_ticket` at startup |
| 4 | `SupportTicket` record with nested enums, `@PersistenceCreator`, and `withId(...)` |
| 5 | `SupportTicketRepository extends ListCrudRepository<SupportTicket, Long>` |
| 6 | `SupportTicketService` with `@Tool` / `@ToolParam` methods |
| 7 | `.tools(supportTicketService)` on the `ChatClient` chain |

Your support assistant doesn't just answer anymore — it acts: the model decides when to file or look up tickets, and Spring AI turns that decision into real method calls.
