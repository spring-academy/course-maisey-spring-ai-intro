---
title: Getting Started and Ticket Persistence
---

In this lab, you'll teach the model to **act**, not just answer. Using Spring AI's tool-calling support, the support assistant will be able to file new support tickets and read existing ones from a relational database. The LLM decides when to call each tool based on the user's request, runs it, and feeds the result back into the response.

Your starting point in `~/sample-app` is the assistant from the previous lab, a `ChatClient` with a default system prompt, a `QuestionAnswerAdvisor` plugged into the chain, and a Markdown knowledge base indexed at startup.

## Add the Persistence Dependencies

The tickets need to be persisted somewhere. We use Spring Data JDBC plus the in-memory H2 database:

```editor:insert-lines-before-line
file: ~/sample-app/pom.xml
line: 55
description: Add Spring Data JDBC and H2 to pom.xml
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
```

## Configure the Datasource

Configure the in-memory H2 database instance:

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/application.properties
description: Configure the H2 datasource
text: |

  spring.datasource.url=jdbc:h2:mem:supportdb;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE
  spring.datasource.driver-class-name=org.h2.Driver
```

The two H2 flags make column matching case-insensitive, so the snake-case column names match the camelCase record components Spring Data infers.

## Create the Schema

Spring Boot runs `schema.sql` automatically against the configured datasource on startup:

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/schema.sql
description: Create schema.sql
text: |
  CREATE TABLE IF NOT EXISTS support_ticket (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        summary VARCHAR(255) NOT NULL,
        category VARCHAR(50) NOT NULL,
        priority VARCHAR(20) NOT NULL,
        status VARCHAR(20) NOT NULL,
        created_at TIMESTAMP NOT NULL);
```

## The SupportTicket Entity

Before the model can file tickets, we need some plain Spring Data building blocks. We need an entity mapped to the `support_ticket` table and a repository to read and write it. There is nothing AI-specific yet, but the design choices here matter for the tool calls later.

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportTicket.java
description: Create SupportTicket record
text: |
  package com.example.support_assistant;

  import org.jspecify.annotations.Nullable;
  import org.springframework.data.annotation.Id;
  import org.springframework.data.annotation.PersistenceCreator;
  import org.springframework.data.relational.core.mapping.Table;

  import java.time.LocalDateTime;

  @Table("support_ticket")
  record SupportTicket(@Nullable @Id Long id, String summary, SupportCategory category, Priority priority,
                       Status status, LocalDateTime createdAt) {

      @PersistenceCreator
      SupportTicket { }

      SupportTicket(String summary, SupportCategory category, Priority priority) {
          this(null, summary, category, priority, Status.OPEN, LocalDateTime.now());
      }

      SupportTicket withId(Long id) {
          return new SupportTicket(id, summary, category, priority, status, createdAt);
      }

      enum Status {
          OPEN, IN_PROGRESS, CLOSED
      }

      enum Priority {
          LOW, MEDIUM, HIGH, CRITICAL
      }
  }
```

Here are a few things worth pointing out.

- **`@PersistenceCreator`** on the compact canonical constructor. This tells Spring Data which constructor to use, so it always uses the 6-arg one when it reads rows.
- **Convenience constructor** for tool calls. The LLM only needs to give `summary`, `category`, and `priority`. `id` is `null` because the database creates it, `status` starts as `OPEN`, and `createdAt` is set to now.
- **`withId(...)` wither**. Spring Data calls it after the INSERT to put the database-generated id back into a new record instance.
- **Nested enums** for `Status` and `Priority`. They are closely tied to the ticket, so they live inside it. The `category` reuses the `SupportCategory` enum you already know from the structured `SupportResponse`.

## The Repository

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportTicketRepository.java
description: Create SupportTicketRepository
text: |
  package com.example.support_assistant;

  import org.springframework.data.repository.ListCrudRepository;
  import org.springframework.stereotype.Repository;

  import java.util.List;

  @Repository
  interface SupportTicketRepository extends ListCrudRepository<SupportTicket, Long> {
      List<SupportTicket> findByStatus(String status);
      List<SupportTicket> findByCategory(String category);
  }
```

`ListCrudRepository` gives us `save`, `findAll`, `findById`, and more. The two derived query methods cover the lookups we want to expose as tools.

## Run the App

Start the application (the first run downloads the new dependencies):

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

{{< note >}}
Wait for "Started SupportAssistantApplication" in the logs before continuing.
{{< /note >}}

Smoke-check the actuator to confirm everything wired up, including the new datasource:

```execute
curl http://localhost:8080/actuator/health
```

You should see `{"status":"UP"}`. Keep the app running in the second terminal.