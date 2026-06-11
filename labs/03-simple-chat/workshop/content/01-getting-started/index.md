---
title: Getting Started
---

# Getting Started

In this lab, you'll build a **support assistant** that answers customer questions via a REST API — your first hands-on Spring AI integration. You'll start with the low-level `ChatModel` API, move to the fluent `ChatClient`, add streaming, and finish with structured output.

## How the Project Was Generated

The starter project in `~/sample-app` was generated with [Spring Initializr](https://start.spring.io). Besides `web` and `actuator`, it only needs one extra dependency: the Spring AI starter for the AI provider you want to talk to.

This workshop uses **OpenAI**, but Spring AI ships a starter per provider — switching the `PROVIDER` variable to `anthropic`, `ollama`, or `bedrock-converse` would pull the matching starter instead:

```bash
PROVIDER=openai   # or: anthropic | ollama | bedrock-converse

curl https://start.spring.io/starter.zip \
  -d dependencies=web,actuator,spring-ai-${PROVIDER} \
  -d bootVersion=4.1.0 \
  -d type=maven-project \
  -d groupId=com.example \
  -d artifactId=support-assistant \
  -d javaVersion=25 \
  -o sample-app.zip
```

{{< note >}}
You don't have to run this — the generated project is already available in `~/sample-app`.
{{< /note >}}

Let's look at what the starter brought in. The provider-specific dependency:

```editor:select-matching-text
file: ~/sample-app/pom.xml
text: "spring-ai-starter-model-openai"
```

And the Spring AI BOM (Bill of Materials), which ensures all Spring AI artifacts resolve to consistent, compatible versions:

```editor:select-matching-text
file: ~/sample-app/pom.xml
text: "spring-ai-bom"
```

The starter pulls in the provider implementation plus the Spring Boot auto-configuration, which wires up a `ChatModel` and a `ChatClient.Builder` bean for you.

## Configure Spring AI

Spring AI configuration is purely declarative. Each provider has its own property namespace for API key, base URL, default model, sampling options, and so on:

| Provider | Starter | Property namespace |
|----------|---------|--------------------|
| **OpenAI** (this lab) | `spring-ai-starter-model-openai` | `spring.ai.openai.*` |
| Anthropic | `spring-ai-starter-model-anthropic` | `spring.ai.anthropic.*` |
| Amazon Bedrock | `spring-ai-starter-model-bedrock-converse` | `spring.ai.bedrock.*` |
| Ollama | `spring-ai-starter-model-ollama` | `spring.ai.ollama.*` |

The shape is the same everywhere — for example, Anthropic uses `spring.ai.anthropic.api-key` and `spring.ai.anthropic.chat.model`, while a local Ollama needs no API key at all, just `spring.ai.ollama.chat.model`.

Have a look at the existing `application.properties`:

```editor:select-matching-text
file: ~/sample-app/src/main/resources/application.properties
text: "spring.mvc.apiversion"
before: 0
after: 2
```

The three `spring.mvc.apiversion.*` lines enable path-segment API versioning — a new feature in Spring Framework 7/Spring Boot 4 that we'll use for our REST endpoints (`/api/1.0/...`).

Now append the OpenAI configuration:

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/application.properties
description: Add the OpenAI configuration
text: |

  spring.ai.openai.api-key=${OPENAI_API_KEY}
  spring.ai.openai.chat.model=gpt-5.4-mini
  spring.ai.openai.chat.temperature=0.7
```

Because the API key, model, and other options are externalized, you can tune behavior or switch models without touching code.

## Set the API Key and Run the App

Set your OpenAI API key (use your own or the one provided by your instructor) — paste it after the `=` and press Enter:

```terminal:input
text: export OPENAI_API_KEY=
endl: false
session: 2
```

Start the application:

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

{{< note >}}
Wait for "Started SupportAssistantApplication" in the logs before continuing.
{{< /note >}}

## Verify the App Is Up

Smoke-check the actuator to confirm everything wired up:

```execute
curl http://localhost:8080/actuator/health
```

You should see `{"status":"UP"}`.

Keep the app running in the second terminal. From here on, each step is a small edit + a restart + a `curl`.

## Summary

You've explored the Spring AI starter and BOM, configured the OpenAI provider declaratively, and verified the application starts. Time to talk to the model!
