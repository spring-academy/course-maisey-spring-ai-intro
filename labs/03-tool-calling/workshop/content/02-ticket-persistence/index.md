---
title: Ticket Persistence
---

Before the model can file tickets, we need some plain Spring Data building blocks. We need an entity mapped to the `support_ticket` table and a repository to read and write it. There is nothing AI-specific yet, but the design choices here matter for the tool calls later.

## The SupportTicket Entity

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