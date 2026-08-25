---
title: Getting Started
---

In the previous lab you worked with the Tool Search Tool, which is generally available since Spring AI 2.0. This lab goes one step further and works with the agentic patterns that are still **experimental**.

Please keep in mind what the article already said about them. They are a first impression and not a stable API.

Your starting point in `~/sample-app` is the support assistant from the previous labs.
One thing changed compared to the previous lab. The Tool Search advisor that you enabled there is turned off again in `application.properties`.

```editor:select-matching-text
file: ~/sample-app/src/main/resources/application.properties
text: "spring.ai.chat.client.tool-search-advisor.enabled=false"
description: The Tool Search advisor is turned off
```

Every tool is therefore sent with every request again. That costs more tokens, but it keeps the logs short, so the patterns of this lab are easier to follow. Turn it back on whenever you want to see the two work together.

## Add the Agent Utils Dependency 

Most of the experimental patterns are maintained in the `spring-ai-agent-utils` repository of the `spring-ai-community` project. 

One dependency brings in all the patterns of the library. 
```editor:select-matching-text
file: ~/sample-app/pom.xml
text: "<artifactId>spring-ai-starter-tool-search-advisor</artifactId>"
description: Add the spring-ai-agent-utils dependency
before: 2
after: 1
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/pom.xml
hidden: true
text: |2
  		<dependency>
  			<groupId>org.springframework.ai</groupId>
  			<artifactId>spring-ai-starter-tool-search-advisor</artifactId>
  		</dependency>

  		<dependency>
  			<groupId>org.springaicommunity</groupId>
  			<artifactId>spring-ai-agent-utils</artifactId>
  		</dependency>
```

You do not write a version, because there is a BOM that provides it.
Import it next to the Spring AI BOM you already have.

```editor:select-matching-text
file: ~/sample-app/pom.xml
text: "<artifactId>spring-ai-bom</artifactId>"
description: Add the spring-ai-agent-utils BOM
before: 2
after: 4
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/pom.xml
hidden: true
text: |2
  			<dependency>
  				<groupId>org.springframework.ai</groupId>
  				<artifactId>spring-ai-bom</artifactId>
  				<version>${spring-ai.version}</version>
  				<type>pom</type>
  				<scope>import</scope>
  			</dependency>
  			<dependency>
  				<groupId>org.springaicommunity</groupId>
  				<artifactId>spring-ai-agent-utils-bom</artifactId>
  				<version>0.11.0</version>
  				<type>pom</type>
  				<scope>import</scope>
  			</dependency>
```

## Not Every Pattern Comes From This Dependency

As the article already pointed out, this one dependency does not cover every experimental pattern. Two of them sit outside of it.

- The **Agent-to-Agent** support ships as artifacts of its own. On the server side that is `spring-ai-a2a-server-autoconfigure`, and on the client side the `a2a-java-sdk-client`. Neither is managed by the BOM you just imported, so both need an explicit version.
- The **evaluator optimizer** advisor of the next section is not part of the library at all. It is a sample implementation from the `spring-ai-examples` repository, built on the experimental recursive advisors of Spring AI itself, and you copy it into your own project.

## Start the Spring Releases MCP Server

The support assistant is configured as an MCP client of the Spring Releases server, so start that server first.

```terminal:execute
command: cd ~/spring-releases-mcp-server && ./mvnw spring-boot:run
session: 3
```

You should see the embedded MCP server start on port 8090 and log one registered tool at startup.

You start the support assistant itself in the next section, once the new code is in place.
