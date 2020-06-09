#!/bin/bash

set -x
set -e

mvn clean install -DskipTests
onos-app localhost reinstall! target/unicastdhcp-1.0-SNAPSHOT.oar
onos-netcfg localhost unicastdhcp.json