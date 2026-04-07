#!/bin/bash
# SessionStart hook: warn if JIRA_PERSONAL_TOKEN is not set.
# create-pr, todo-scan, and solve-jira skills call jira.navercorp.com and will fail without it.
if [ -z "$JIRA_PERSONAL_TOKEN" ]; then
    echo "WARNING: JIRA_PERSONAL_TOKEN is not set."
    echo "  Skills that call jira.navercorp.com (create-pr, todo-scan, solve-jira) will fail."
    echo "  Set it with: export JIRA_PERSONAL_TOKEN=<your-personal-token>"
fi
