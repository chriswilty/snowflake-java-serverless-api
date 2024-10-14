#!/bin/sh
shift
java --add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED "$@"
