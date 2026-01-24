#!/bin/bash

# Script to view active background collection jobs
# Usage: ./scripts/stop-collection.sh

echo "Active background collection jobs:"
echo ""
echo "Use the /tasks command in Claude Code to see all active background processes."
echo "To kill a specific process, note its ID and let Claude know."
echo ""
echo "Checking current collection progress..."
./scripts/railway.sh db-stats
