---
title: The Advisors API
---

In the article you met the **advisor**, an interceptor that wraps a `ChatClient` call. It runs *before* the request reaches the model and *after* the response comes back, and several advisors form a chain.

In this lab you add advisors to the support assistant. You first write your own logging advisor, then replace it with the built-in one, and finally add conversation memory so the assistant can follow up on what was said before.

## Start the Support Assistant

Your starting point in `~/sample-app` is the assistant from the previous lab, a `ChatClient` with a default system prompt and a `/api/v1/chat` endpoint that returns a structured `SupportResponse` record.

{{< note >}}
Every call to OpenAI in this lab is mocked. The application sends its requests to a local mock server that returns predefined responses, so you do not need a real API key. The application code stays exactly the same as it would be against the real OpenAI service.
{{< /note >}}

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

{{< note >}}
Wait for "Started SupportAssistantApplication" in the logs before you continue.
{{< /note >}}

Call the endpoint to confirm that everything works.

```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about Spring AI"
```

You should get back a JSON object with a `category` and an `answer` field. Keep the app running in the second terminal. Spring Boot Developer Tools restarts it whenever the compiled classes change, so from here on each step is a small edit and a `curl`.

## Write a Custom Logging Advisor

A blocking advisor implements the `CallAdvisor` interface. There is one method to fill in, `adviseCall`, plus a name and an order. Inside `adviseCall` you do your *before* work, call `chain.nextCall(request)` to pass control down the chain toward the model, and then do your *after* work with the response.

Create the advisor class:

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/support_assistant/LoggingAdvisor.java
description: Create a custom logging advisor
text: |
  package com.example.support_assistant;

  import org.slf4j.Logger;
  import org.slf4j.LoggerFactory;
  import org.springframework.ai.chat.client.ChatClientRequest;
  import org.springframework.ai.chat.client.ChatClientResponse;
  import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
  import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
  import org.springframework.core.Ordered;

  class LoggingAdvisor implements CallAdvisor {

      private static final Logger log = LoggerFactory.getLogger(LoggingAdvisor.class);

      @Override
      public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
          log.info("Request to model: {}", request.prompt().getContents());   // before
          ChatClientResponse response = chain.nextCall(request);              // delegate down the chain
          log.info("Response from model: {}",
                  response.chatResponse().getResult().getOutput().getText()); // after
          return response;
      }

      @Override
      public String getName() {
          return "LoggingAdvisor";
      }

      @Override
      public int getOrder() {
          return Ordered.LOWEST_PRECEDENCE;
      }
  }
```

Three things are worth a closer look. Click each line below to highlight it in the editor.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/LoggingAdvisor.java
text: 'log.info("Request to model: {}", request.prompt().getContents());'
before: 0
after: 0
description: The before step, which sees the request on the way in
```

This is the *before* work. The `request.prompt()` gives you the full `Prompt` that is about to go to the model, so you can inspect it or even change it before the model sees it.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/LoggingAdvisor.java
text: "ChatClientResponse response = chain.nextCall(request);"
before: 0
after: 0
description: The call that hands the request to the rest of the chain
```

This single call invokes the rest of the chain and, eventually, the model. Everything you write above it runs on the way in, and everything below it runs on the way out.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/LoggingAdvisor.java
text: "public int getOrder() {"
before: 0
after: 2
description: The order that decides the position in the chain
```

`getOrder()` decides where this advisor sits in the chain, where a lower value runs earlier on the way in. We return `Ordered.LOWEST_PRECEDENCE` so the logger runs last on the way in and logs the final request, after every other advisor has changed it.

## Register the Advisor

An advisor does nothing until it is on a `ChatClient`. Add it as a default on the client bean, next to the default system prompt, so every call through that client passes through it.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: ".defaultSystem(systemPrompt)"
before: 0
after: 0
description: Register the logging advisor on the ChatClient
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
hidden: true
text: |2
                  .defaultSystem(systemPrompt)
                  .defaultAdvisors(new LoggingAdvisor())
```

Call the endpoint again:
```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about Spring AI"
```

Now check the application logs in the second terminal. You'll see two lines from `LoggingAdvisor`, one with the request sent to the model and one with the response that came back. The advisor ran around the call without any change to the service or controller code.

## Swap in the Built-in `SimpleLoggerAdvisor`

Logging a request and response is such a common need that Spring AI already ships an advisor for it, the **`SimpleLoggerAdvisor`**. It does exactly what your custom advisor does, so there is no reason to maintain your own. Replace your advisor with the built-in one.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: ".defaultAdvisors(new LoggingAdvisor())"
before: 0
after: 0
description: Use the built-in SimpleLoggerAdvisor
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
cascade: true
hidden: true
text: |2
                  .defaultAdvisors(new SimpleLoggerAdvisor(Ordered.LOWEST_PRECEDENCE))
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "import org.springframework.ai.chat.client.ChatClient;"
before: 0
after: 0
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
hidden: true
text: |
  import org.springframework.ai.chat.client.ChatClient;
  import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
  import org.springframework.core.Ordered;
```

The `SimpleLoggerAdvisor` logs at `DEBUG` level, so it stays quiet in production by default. Turn on `DEBUG` for the advisor package so you can see its output:

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/application.properties
description: Enable DEBUG logging for the advisor package
text: |
  logging.level.org.springframework.ai.chat.client.advisor=DEBUG
```

Your custom class is no longer used, so remove it to keep the project clean:
```terminal:execute
command: rm ~/sample-app/src/main/java/com/example/support_assistant/LoggingAdvisor.java
description: Delete the custom logging advisor
```

Call the endpoint one more time:
```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about Spring AI"
```

Check the second terminal again. This time the `request:` and `response:` lines come from `SimpleLoggerAdvisor`, with the full request and response formatted for you. Same behavior, no custom code to maintain.
