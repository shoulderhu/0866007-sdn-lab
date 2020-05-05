#!/bin/bash

set -x
set -e

onos localhost app activate proxyarp
mvn clean install -DskipTests
onos-app localhost reinstall! target/path-app-1.0-SNAPSHOT.oar
