#!/usr/bin/env bash

curl -s -X POST http://localhost:3592/access/v1/evaluation \
  -H "Content-Type: application/json" \
  -d '{
    "subject": {
      "type": "user",
      "id": "charlie",
      "properties": {
        "roles": ["user","manager"],
        "team": "engineering"
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