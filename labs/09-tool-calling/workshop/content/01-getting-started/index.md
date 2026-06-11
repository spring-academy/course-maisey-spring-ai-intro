---
title: Getting Started
---

# Getting Started

In this lab, you'll teach the model to **act**, not just answer. Using Spring AI's tool-calling support, the support assistant will be able to file new support tickets and read existing ones from a relational database. The LLM decides when to call each tool based on the user's request, runs it, and feeds the result back into the response.

Your starting point in `~/sample-app` is the assistant from the **Embeddings & RAG** lab: a `ChatClient` with a default system prompt, a `QuestionAnswerAdvisor` plugged into the chain, and a Markdown knowledge base indexed at startup.

{{< note >}}
This lab uses **OpenAI**. The sample app also bundles the starters for Anthropic, Amazon Bedrock, and Ollama — you could switch the chat provider via Spring profiles (e.g. `SPRING_PROFILES_ACTIVE=anthropic`). The default configuration uses OpenAI.
{{< /note >}}

## Add the Persistence Dependencies

The tickets need to be persisted somewhere. We use Spring Data JDBC plus the in-memory H2 database:

```editor:insert-lines-before-line
file: ~/sample-app/pom.xml
line: 88
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

{{< note >}}
The sample app also ships a (commented-out) PostgreSQL/pgvector setup with Docker Compose. If you used that as the vector store, you'd skip the H2 driver — `spring-boot-starter-data-jdbc` would simply pick up the existing PostgreSQL `DataSource`.
{{< /note >}}

## Configure the Datasource

Point Spring at an in-memory H2 database:

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

On PostgreSQL, the only difference would be the auto-increment syntax (`id BIGSERIAL PRIMARY KEY`) — the column names stay the same.

## Set the API Key and Run the App

Set your OpenAI API key (use your own or the one provided by your instructor) — paste it after the `=` and press Enter:

```terminal:input
text: export OPENAI_API_KEY=
endl: false
session: 2
```

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

## Summary

You've added Spring Data JDBC with an in-memory H2 database and a `support_ticket` table created at startup. Next: the entity and repository to work with it.
