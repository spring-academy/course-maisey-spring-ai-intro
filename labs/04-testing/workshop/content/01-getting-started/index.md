---
title: Getting Started
---

In this lab you will write automated tests for the support assistant. Code that calls an LLM cannot be tested like normal code. The model rewords answers, picks different synonyms, and sometimes refuses to answer at all. Two patterns get you most of the way there.

1. **Semantic assertions** These are cheap and mostly deterministic checks. You assert on the meaning of the answer, so you look for the key concepts and not the exact text.
2. **LLM as judge** For harder questions like "is this answer really relevant to the retrieved context?", you use a second model call to grade the output.

Both patterns come built into Spring AI. You only wire them into a normal `@SpringBootTest`.

Your starting point in `~/sample-app` is the support assistant you implemented in the previous labs.

## Test Dependencies

There is nothing to add. The project already ships `spring-boot-starter-webmvc-test`, which pulls in JUnit 5, AssertJ, and `@SpringBootTest`. It also ships `spring-boot-starter-actuator-test`. Both were part of the project from the start. The `RelevancyEvaluator` you will use later lives in `spring-ai-client-chat`, which the OpenAI chat starter already brings in for you. The other provider starters do the same.

So you do not need to add any new dependencies.

## Summary

Everything you need for testing is already on the classpath. In the next step you will write a first test that checks response quality without depending on the exact wording.
