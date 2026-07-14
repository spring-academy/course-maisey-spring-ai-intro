---
title: Tool Calling
---

Now you will use Spring AI's tool calling. You add the `@Tool` annotation to your methods and the framework does four things for you.

1. It reads the method signature and builds a JSON schema that describes the tool.
2. It sends these schemas to the model with every call.
3. When the model asks to call a tool, the framework runs the method with the arguments the model provided.
4. It sends the return value back to the model so the model can finish its answer.

## Implement the Service that Provides the Tools

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

Two annotations do most of the work.

- **`@Tool(description = "...")`** explains what the tool does, written for the model to read. This is the most important text in the whole step. The model reads it to decide if it should call the tool, so say clearly when the tool should be used. In this example the model should call it when the user "explicitly requests to create, open, or file a support ticket".
- **`@ToolParam(description = "...")`** explains each parameter. The model reads these to fill in the arguments correctly. Spring AI turns enum types into the choices the model can pick from.

## Register the Tools on the ChatClient

Inject `SupportTicketService` into the `SupportAssistantService`:

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "private final VectorStore vectorStore;"
before: 0
after: 0
description: Inject the SupportTicketService
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
hidden: true
text: |2
      SupportAssistantService(ChatClient chatClient, VectorStore vectorStore, SupportTicketService supportTicketService) {
          this.chatClient = chatClient;
          this.vectorStore = vectorStore;
          this.supportTicketService = supportTicketService;
      }
```

Then register the `SupportTicketService` instance's tools on the `ChatClient`:
```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: ".advisors(ragAdvisor)"
before: 0
after: 0
description: Register the tools on the ChatClient
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
hidden: true
text: |2
              .advisors(ragAdvisor)
              .tools(supportTicketService)
```

`.tools(Object)` registers every method on the bean that has the `@Tool` annotation. Spring AI handles all the steps for you. It sends the schemas, the model returns tool calls, Spring AI runs them, sends the results back, and the model gives the final answer. You do not need to write any of this yourself.

To see these steps in the logs, turn on debug logging for Spring AI.

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/application.properties
description: Enable Spring AI debug logging
text: |

  logging.level.org.springframework.ai=debug
```

## Try It Out

Ask the assistant to file a ticket:

```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Open a high priority ticket to request a trial for VMware Tanzu Spring"
```

You should see a response that confirms the ticket was created. If you turned on debug logging, the logs in the second terminal show the `createTicket` call with the arguments the model chose.

Then list the open tickets through the assistant.
```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Provide me an overview of all open support tickets"
```

The model picks `retrieveOpenTickets`, gets the rows from the database, and returns them in the response.

Finally, combine this with RAG. Ask a question that needs both an answer and an action.

```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Does VMware Tanzu Spring provide support for Spring Boot 2.7? If yes, open a ticket to request a trial"
```

The advisor pulls the answer from the knowledge base. If the model decides a ticket is needed, the tool call files it.