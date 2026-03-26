#!/usr/bin/env bash

curl -s -X POST http://localhost:3592/access/v1/evaluation \
  -H "Content-Type: application/json" \
  -d '{
    "subject": {
      "type": "user",
      "id": "bob",
      "properties": {
        "roles": ["manager"],
        "team": "marketing"
      }
    },
    "resource": {
      "type": "user_profile",
      "id": "alice",
      "properties": {
        "team": "engineering"
      }
    },
    "action": {
      "name": "view"
    }
  }' | jq .
