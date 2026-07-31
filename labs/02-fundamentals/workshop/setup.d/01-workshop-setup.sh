#!/bin/bash

set -x
set -eo pipefail

jq ". + { \"editor.fontSize\": 14, \"files.exclude\": { \".**\": true}}" /home/eduk8s/.local/share/code-server/User/settings.json > /home/eduk8s/.local/share/code-server/User/settings.json.tmp && mv /home/eduk8s/.local/share/code-server/User/settings.json.tmp /home/eduk8s/.local/share/code-server/User/settings.json

chmod +x sample-app/mvnw

code-server --install-extension redhat.java
curl ${WEBSERVER}/m2-repository.tar.gz | tar -xzvf - -C ${HOME}/.m2
