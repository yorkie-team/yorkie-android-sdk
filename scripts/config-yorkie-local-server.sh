#!/bin/bash

# Use ifconfig or networksetup or hostname based on platform
if command -v ifconfig >/dev/null 2>&1; then
  PRIVATE_IP=$(ifconfig | awk '/inet / && $2 != "127.0.0.1" { print $2; exit }')
elif command -v hostname >/dev/null 2>&1; then
  PRIVATE_IP=$(hostname -I | awk '{print $1}')
else
  echo "No supported command to get local IP found"
  exit 1
fi

# Confirm IP was found
if [[ -z "$PRIVATE_IP" ]]; then
  echo "Failed to determine local IP address."
  exit 1
fi

# Path to local.properties
LOCAL_PROPS_FILE="./local.properties"

# Create file if it doesn't exist
touch "$LOCAL_PROPS_FILE"

YORKIE_SERVER_URL="http://$PRIVATE_IP:8080"

# Update or insert YORKIE_SERVER_URL
if grep -q "^YORKIE_SERVER_URL=" "$LOCAL_PROPS_FILE"; then
  if [[ "$OSTYPE" == "darwin"* ]]; then
    sed -i '' "s|^YORKIE_SERVER_URL=.*|YORKIE_SERVER_URL=$YORKIE_SERVER_URL|" "$LOCAL_PROPS_FILE"
  else
    sed -i "s|^YORKIE_SERVER_URL=.*|YORKIE_SERVER_URL=$YORKIE_SERVER_URL|" "$LOCAL_PROPS_FILE"
  fi
else
  echo "YORKIE_SERVER_URL=$YORKIE_SERVER_URL" >> "$LOCAL_PROPS_FILE"
fi
