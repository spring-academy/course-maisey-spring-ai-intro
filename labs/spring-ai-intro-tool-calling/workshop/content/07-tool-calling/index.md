---
title: Tool Calling
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

## Create the DateTimeTool

Create the tools module directory:

```terminal:execute
command: mkdir -p ~/sample-app/src/main/java/com/example/supportassistant/tools
session: 1
description: Create tools package
cascade: true
```

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/supportassistant/tools/DateTimeTool.java
hidden: true
text: |
  package com.example.supportassistant.tools;

  import org.springframework.ai.tool.annotation.Tool;
  import org.springframework.ai.tool.annotation.ToolParam;
  import org.springframework.stereotype.Component;

  import java.time.ZoneId;
  import java.time.ZonedDateTime;
  import java.time.format.DateTimeFormatter;

  @Component
  public class DateTimeTool {

      @Tool(description = "Get the current date and time in a specific timezone. Use this when the user asks about the current time or date.")
      public String getCurrentDateTime(
              @ToolParam(description = "The timezone, e.g., 'America/New_York', 'Europe/London', 'Asia/Tokyo'")
              String timezone) {
          ZonedDateTime now = ZonedDateTime.now(ZoneId.of(timezone));
          return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
      }
  }
```

## Set Up the Database

Add Spring Data JDBC and H2:

```editor:select-matching-text
file: ~/sample-app/pom.xml
text: "</dependencies>"
description: Add Spring Data JDBC and H2 dependencies
before: 0
after: 0
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/pom.xml
hidden: true
text: |2
          <dependency>
              <groupId>org.springframework.boot</groupId>
              <artifactId>spring-boot-starter-data-jdbc</artifactId>
          </dependency>

          <dependency>
              <groupId>com.h2database</groupId>
              <artifactId>h2</artifactId>
              <scope>runtime</scope>
          </dependency>
      </dependencies>
```

Configure the H2 datasource:

```editor:select-matching-text
file: ~/sample-app/src/main/resources/application.yaml
text: "spring:"
description: Add H2 database configuration
before: 0
after: 0
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/resources/application.yaml
hidden: true
text: |
  spring:
    datasource:
      url: jdbc:h2:mem:supportdb;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE
      driver-class-name: org.h2.Driver
```

Create the database schema:

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/schema.sql
description: Create support ticket schema
text: |
  CREATE TABLE IF NOT EXISTS support_ticket (
      id BIGINT AUTO_INCREMENT PRIMARY KEY,
      ticket_id VARCHAR(20) NOT NULL UNIQUE,
      summary VARCHAR(255) NOT NULL,
      category VARCHAR(50) NOT NULL,
      priority VARCHAR(20) NOT NULL,
      status VARCHAR(20) NOT NULL,
      created_at TIMESTAMP NOT NULL
  );
```

## Create the Ticket Entity and Repository

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/supportassistant/tools/SupportTicket.java
description: Create SupportTicket entity
text: |
  package com.example.supportassistant.tools;

  import org.springframework.data.annotation.Id;
  import org.springframework.data.relational.core.mapping.Table;

  import java.time.LocalDateTime;

  @Table("support_ticket")
  public record SupportTicket(
          @Id Long id,
          String ticketId,
          String summary,
          String category,
          String priority,
          String status,
          LocalDateTime createdAt
  ) {
      public SupportTicket(String ticketId, String summary, String category, String priority) {
          this(null, ticketId, summary, category, priority, "OPEN", LocalDateTime.now());
      }
  }
```

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/supportassistant/tools/SupportTicketRepository.java
description: Create SupportTicketRepository
text: |
  package com.example.supportassistant.tools;

  import org.springframework.data.repository.CrudRepository;
  import org.springframework.stereotype.Repository;

  import java.util.List;
  import java.util.Optional;

  @Repository
  public interface SupportTicketRepository extends CrudRepository<SupportTicket, Long> {
      Optional<SupportTicket> findByTicketId(String ticketId);
      List<SupportTicket> findByStatus(String status);
      List<SupportTicket> findByCategory(String category);
  }
```

## Create the TicketTool

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/supportassistant/tools/TicketTool.java
description: Create TicketTool
text: |
  package com.example.supportassistant.tools;

  import org.springframework.ai.tool.annotation.Tool;
  import org.springframework.ai.tool.annotation.ToolParam;
  import org.springframework.stereotype.Component;

  import java.util.List;
  import java.util.concurrent.atomic.AtomicInteger;

  @Component
  public class TicketTool {

      private final SupportTicketRepository ticketRepository;
      private final AtomicInteger ticketCounter = new AtomicInteger(1000);

      public TicketTool(SupportTicketRepository ticketRepository) {
          this.ticketRepository = ticketRepository;
      }

      @Tool(description = "Create a new support ticket. Use this when the user explicitly requests to create, open, or file a support ticket.")
      public TicketResult createTicket(
              @ToolParam(description = "Brief summary of the issue (max 100 chars)")
              String summary,

              @ToolParam(description = "Category: TECHNICAL, BILLING, SECURITY, UPGRADE, or GENERAL")
              String category,

              @ToolParam(description = "Priority: LOW, MEDIUM, HIGH, or CRITICAL")
              String priority) {

          String ticketId = "TSE-" + ticketCounter.incrementAndGet();

          SupportTicket ticket = new SupportTicket(ticketId, summary, category.toUpperCase(), priority.toUpperCase());
          SupportTicket saved = ticketRepository.save(ticket);

          return new TicketResult(
                  saved.ticketId(),
                  saved.summary(),
                  saved.category(),
                  saved.priority(),
                  saved.status(),
                  saved.createdAt().toString(),
                  "Ticket created successfully"
          );
      }

      @Tool(description = "List all open support tickets. Use this when the user wants to see their tickets or check ticket status.")
      public List<SupportTicket> listOpenTickets() {
          return ticketRepository.findByStatus("OPEN");
      }

      public record TicketResult(
              String ticketId,
              String summary,
              String category,
              String priority,
              String status,
              String createdAt,
              String message
      ) {}
  }
```

## Create the ToolsController

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/supportassistant/tools/ToolsController.java
description: Create ToolsController
text: |
  package com.example.supportassistant.tools;

  import org.springframework.ai.chat.client.ChatClient;
  import org.springframework.web.bind.annotation.*;

  import java.util.List;

  @RestController
  @RequestMapping("/tools")
  public class ToolsController {

      private final ChatClient chatClient;
      private final DateTimeTool dateTimeTool;
      private final TicketTool ticketTool;
      private final SupportTicketRepository ticketRepository;

      public ToolsController(
              ChatClient chatClient,
              DateTimeTool dateTimeTool,
              TicketTool ticketTool,
              SupportTicketRepository ticketRepository) {
          this.chatClient = chatClient;
          this.dateTimeTool = dateTimeTool;
          this.ticketTool = ticketTool;
          this.ticketRepository = ticketRepository;
      }

      @GetMapping("/chat")
      public String chatWithTools(@RequestParam String message) {
          return chatClient.prompt()
                  .system("""
                          You are the Support Assistant with access to tools.
                          Use the available tools when appropriate to help the user.
                          Execute tool calls directly without asking for confirmation.
                          Always be helpful and provide context with your answers.
                          """)
                  .user(message)
                  .tools(dateTimeTool, ticketTool)
                  .call()
                  .content();
      }

      @GetMapping("/tickets")
      public List<SupportTicket> getAllTickets() {
          return (List<SupportTicket>) ticketRepository.findAll();
      }
  }
```

## Start and Test

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

Test the date/time tool:

```execute
http -b "localhost:8080/tools/chat?message=What+time+is+it+in+Tokyo?"
```

Test ticket creation:

```execute
http -b "localhost:8080/tools/chat?message=Please+create+a+high+priority+technical+ticket+about+my+Spring+Boot+application+crashing+on+startup"
```

Verify the ticket was persisted to the database:

```execute
http localhost:8080/tools/tickets
```

Let the model list open tickets:

```execute
http -b "localhost:8080/tools/chat?message=Show+me+my+open+tickets"
```

Test a request that requires both tools in one response:

```execute
http -b "localhost:8080/tools/chat?message=What+time+is+it+in+London+and+please+create+a+low+priority+general+ticket+asking+about+documentation+updates"
```

## Stop the Application

```terminal:interrupt
session: 2
```
