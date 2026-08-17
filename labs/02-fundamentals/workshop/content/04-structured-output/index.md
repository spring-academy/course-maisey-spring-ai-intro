---
title: Structured Output
---

Your endpoint still answers with plain text. In this step you first ask for JSON with a few-shot prompt of your own, and then hand the same job to Spring AI so you get a Java object back.

## Prompt Engineering and Few-Shot Prompting

Write the format rules and two examples into the system prompt and send the question as the user message.

The prompt contains `{` and `}` characters. Spring AI normally reads `{...}` as a template variable, so this step uses the plain `.system(String)` method, which leaves the braces as normal text. Update `generateResponse`.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "String generateResponse(String query) {"
before: 0
after: 9
description: Use a hand-written few-shot prompt
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
hidden: true
text: |2
      String generateResponse(String query) {
          var chatResponse = chatClient.prompt()
                  .system("""
                    You are a Spring support classifier.
                    Reply only with JSON in this form:
                    {"category":"...","answer":"..."}
                    The category must be one of: TECHNICAL, BILLING, SECURITY, GENERAL.
                    Examples:
                    - "Why was I billed twice?"     -> {"category":"BILLING","answer":"..."}
                    - "How do I rotate my API key?" -> {"category":"SECURITY","answer":"..."}
                    """)
                  .user(query)
                  .call()
                  .chatResponse();
          log.info("Chat Response {}", chatResponse);
          return chatResponse.getResult().getOutput().getText();
      }
```

Call the endpoint with a general question:
```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about Spring AI"
```

The model replies with JSON, even though the method still returns a plain `String`. The examples did the steering, and the parsing is still your job.

## Let Spring AI Do the Work

Now ask for a Java type instead. You define the type first and then return it from the service.

### Define the Response Type

First, create an enum for the category of the support request:

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportCategory.java
description: Create SupportCategory enum
text: |
  package com.example.support_assistant;

  enum SupportCategory {
      TECHNICAL,
      BILLING,
      SECURITY,
      GENERAL
  }
```

Then a record for the structured answer. Spring AI passes the `@JsonPropertyDescription` annotations to the model as part of the generated schema, so they tell it what belongs in each field.

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportResponse.java
description: Create SupportResponse record
text: |
  package com.example.support_assistant;

  import com.fasterxml.jackson.annotation.JsonPropertyDescription;

  record SupportResponse(
          @JsonPropertyDescription("The category of the support question: TECHNICAL, BILLING, SECURITY, or GENERAL")
          SupportCategory category,

          @JsonPropertyDescription("The helpful answer to the customer's question")
          String answer
  ) { }
```

### Return the Record

Change `generateResponse` to return the record via `.entity(...)`:
```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "String generateResponse(String query) {"
before: 0
after: 16
description: Return a SupportResponse entity
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
hidden: true
text: |2
      SupportResponse generateResponse(String query) {
          return chatClient.prompt()
                  .user(u -> u
                          .text("Answer the following question with a short, well-structured explanation: {question}")
                          .param("question", query))
                  .call()
                  .entity(SupportResponse.class);
      }
```

And change the controller method to match:
```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
text: '@GetMapping(path = "/api/v{version}/chat")'
before: 0
after: 3
description: Return SupportResponse from the controller
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
hidden: true
text: |2
      @GetMapping(path = "/api/v{version}/chat") 
      SupportResponse chat(@RequestParam String query) {
          return service.generateResponse(query);
      }
```

Rerun the curl again:
```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about Spring AI"
```

### Enable Native Structured Output

Your `.entity(...)` call is still prompt based, and **native structured output** is off by default. The model behind this lab supports it, so switch it on once on the `ChatClient` bean and every `.entity(...)` call uses it from then on.

Update the bean in `SupportAssistantConfiguration`.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "import org.springframework.ai.chat.client.ChatClient;"
before: 0
after: 0
description: Enable native structured output on the ChatClient
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
cascade: true
hidden: true
text: |
  import org.springframework.ai.chat.client.ChatClient;
  import org.springframework.ai.chat.client.AdvisorParams;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: ".defaultSystem(systemPrompt)"
before: 0
after: 0
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
hidden: true
text: |2
                  .defaultSystem(systemPrompt)
                  .defaultAdvisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
```

Rerun the `curl` to verify it still works:
```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about Spring AI"
```