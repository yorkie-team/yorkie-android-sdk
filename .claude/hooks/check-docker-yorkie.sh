#!/bin/bash
# SessionStart hook: warn if Docker or the Yorkie server container is not running.
# Instrumented tests (yorkie:connectedDebugAndroidTest) require a live Yorkie server.

if ! docker info > /dev/null 2>&1; then
    echo "WARNING: Docker is not running."
    echo "  Instrumented tests require the Yorkie server container."
    echo "  Start Docker, then run: docker compose -f docker/docker-compose.yml up --build -d"
    exit 0
fi

if ! docker ps --format '{{.Names}}' | grep -q 'yorkie'; then
    echo "WARNING: Yorkie server container is not running."
    echo "  Instrumented tests (connectedDebugAndroidTest) will fail without it."
    echo "  Start it with: docker compose -f docker/docker-compose.yml up --build -d"
fi
