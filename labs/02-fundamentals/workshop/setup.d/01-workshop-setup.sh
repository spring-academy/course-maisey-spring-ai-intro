#!/bin/bash

set -x
set -eo pipefail

jq ". + { \"editor.fontSize\": 14, \"files.exclude\": { \".**\": true}}" /home/eduk8s/.local/share/code-server/User/settings.json > /home/eduk8s/.local/share/code-server/User/settings.json.tmp && mv /home/eduk8s/.local/share/code-server/User/settings.json.tmp /home/eduk8s/.local/share/code-server/User/settings.json

chmod +x sample-app/mvnw

code-server --install-extension redhat.java
(cd sample-app && ./mvnw dependency:go-offline)

rm -rf sample-app/src/test/java/com/example/support_assistant/mock
rm -rf sample-app/src/test/resources/prompts
rm sample-app/src/test/resources/junit-platform.properties
