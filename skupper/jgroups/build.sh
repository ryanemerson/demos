#!/bin/bash
set -e

echo "Building Quarkus JGroups Demo..."
mvn clean package -DskipTests

echo ""
echo "Build successful! Docker image can be built with:"
echo "  docker build -t quarkus-jgroups-demo ."
echo ""
echo "Run locally with:"
echo "  java -jar target/quarkus-app/quarkus-run.jar"
