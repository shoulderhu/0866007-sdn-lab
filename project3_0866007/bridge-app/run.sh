#!/bin/bash

set -x
set -e

mvn clean install -DskipTests
onos-app localhost reinstall! target/bridge-app-1.0-SNAPSHOT.oar
