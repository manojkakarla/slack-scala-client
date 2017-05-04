#!/usr/bin/env bash
sbt clean package
cp  target/scala-2.11/*.jar ~/okroger/roger-server/lib/