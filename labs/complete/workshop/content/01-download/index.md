---
title: Download the Sample Code
---

This environment contains the finished code of all labs. There are two projects in your home directory, the `sample-app` with the support assistant and the `spring-releases-mcp-server` with the MCP server. Take them with you to keep working on them after the workshop.

## Create the Archive

Pack both projects into a single zip file. The build output in the `target` directories is left out, so the archive stays small.

```terminal:execute
command: zip -qr support-assistant-sample-code.zip sample-app spring-releases-mcp-server -x "*/target/*"
session: 1
description: Zip both projects into support-assistant-sample-code.zip
```

Check that the archive was created.

```terminal:execute
command: ls -lh ~/support-assistant-sample-code.zip
session: 1
description: Verify the archive
```

## Download the Archive

Click the action below to download `support-assistant-sample-code.zip` to your own machine.

```files:download-file
path: support-assistant-sample-code.zip
```

Unpack the file locally and open either project in your IDE. Both build with `./mvnw` and need Java 21 or later. 

Don't forget to adjust the configuration in `sample-app/src/main/resources/application.properties`, if you want to use the real OpenAI service instead of the mock server.
