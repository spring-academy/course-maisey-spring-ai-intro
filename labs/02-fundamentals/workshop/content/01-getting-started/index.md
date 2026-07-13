---
title: Getting Started
---

In this lab you will start building a **support assistant** that answers customer questions through a REST API. You will begin with the low-level `ChatModel` API, move on to the fluent `ChatClient`, and finish with structured output.

## Scaffold the Application

Before we write any code we need a Spring Boot application to build on. Every Spring Boot project starts from a small set of dependencies, and for this lab we need four of them.

- **Spring Web** lets us expose a REST API so clients can send their questions over HTTP.
- **Spring Boot Actuator** adds health and monitoring endpoints so we can check that the application is running.
- The **Spring AI starter** brings in the client that talks to our AI provider.
- **Spring Boot DevTools** restarts the running application automatically when the compiled classes change, so we can iterate quickly without stopping and starting the app by hand.

We generate the project with the [Spring Initializr](https://start.spring.io). The command below asks the Initializr for exactly those dependencies and downloads the project as a zip file.

{{< note >}}
You don't have to run this. The generated project is already prepared for you in `~/sample-app`, and the rest of this lab edits the files in that folder.
{{< /note >}}

```bash
curl https://start.spring.io/starter.zip \
  -d dependencies=web,actuator,spring-ai-openai,devtools \
  -d bootVersion=4.1.0 \
  -d type=maven-project \
  -d groupId=com.example \
  -d artifactId=support-assistant \
  -d javaVersion=21 \
  -o sample-app.zip
```

This workshop uses an **OpenAI compatible mock API**, but Spring AI ships a starter for every common provider. Swapping `spring-ai-openai` for `spring-ai-anthropic`, `spring-ai-ollama`, or `spring-ai-bedrock-converse` would pull the matching starter instead.

Let's look at what the starter added to the project. First the provider-specific dependency.

```editor:select-matching-text
file: ~/sample-app/pom.xml
text: "spring-ai-starter-model-openai"
```

Next the Spring AI BOM (Bill of Materials), which makes sure all Spring AI artifacts resolve to consistent and compatible versions.

```editor:select-matching-text
file: ~/sample-app/pom.xml
text: "spring-ai-bom"
```

The starter pulls in the provider implementation together with the Spring Boot auto-configuration. That auto-configuration wires up a `ChatModel` and a `ChatClient.Builder` bean for you.

## Configure Spring AI

Spring AI configuration is fully declarative. Each provider has its own property namespace for the API key, base URL, default model, sampling options, and so on.

The shape is the same everywhere. For example Anthropic uses `spring.ai.anthropic.api-key` and `spring.ai.anthropic.chat.model`, while a local Ollama needs no API key at all and only wants `spring.ai.ollama.chat.model`.

Have a look at the existing `application.properties`.

```editor:select-matching-text
file: ~/sample-app/src/main/resources/application.properties
text: "spring.mvc.apiversion"
before: 0
after: 2
```

The three `spring.mvc.apiversion.*` lines enable path-segment API versioning. This is a new feature in Spring Framework 7 and Spring Boot 4 that we will use for our REST endpoints such as `/api/v1/...`.

You will also notice the `spring.devtools.restart.enabled=true` line.

```editor:select-matching-text
file: ~/sample-app/src/main/resources/application.properties
text: "spring.devtools.restart.enabled=true"
```

This turns on the Spring Boot Developer Tools automatic restart. Developer Tools watches the compiled classes on the classpath, and whenever those classes change it restarts the application context for you. Because the restart only reloads your own classes and keeps the unchanged libraries in memory, it is much faster than a full stop and start.

The restart is triggered by a change to the compiled output, not by editing the source file. In this workshop you edit the code in VS Code, and the Java language server extension recompiles the changed file on save by default. So saving the file is effectively your restart trigger.

Now append the OpenAI configuration.

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/application.properties
description: Add the OpenAI configuration
text: |

  spring.ai.openai.api-key=mock-api-key
  spring.ai.openai.base-url=http://localhost:8081/v1
  spring.ai.openai.chat.model=gpt-5.4-mini
  spring.ai.openai.chat.temperature=0.7
```

Because the API key, model, and other options live outside the code, you can tune behavior or switch models without changing any code.

The `base-url` points the OpenAI client at the local mock API instead of `https://api.openai.com`. The starter still requires an `api-key`, so `mock-api-key` is just a placeholder that the mock accepts.

The mock API runs inside the same application. It is an embedded WireMock server started by the `com.example.support_assistant.mock.MockOpenAiServer` class, and it listens on port 8081. Instead of hand written answers it replays real OpenAI responses that were recorded earlier and stored in the `resources/mock` folder. This keeps the lab predictable and free of charge.

## Run the App
Start the application.

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

{{< note >}}
Wait for "Started SupportAssistantApplication" in the logs before you continue.
{{< /note >}}

## Verify the App Is Up

Check the actuator endpoint to confirm everything wired up correctly.

```execute
curl http://localhost:8080/actuator/health
```

You should see `{"status":"UP"}`.

Keep the app running in the second terminal. From here on each step is a small edit and a `curl`. You do not restart the app by hand.

## Summary

We scaffolded the support assistant project, explored the Spring AI starter and BOM, configured the OpenAI provider declaratively, and verified that the application starts. Now it is time to talk to the model.
