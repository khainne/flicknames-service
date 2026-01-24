#!/bin/bash

# Railway helper script for Flicknames service
# Usage: ./scripts/railway.sh [command]

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TOKEN_FILE="$PROJECT_ROOT/.railway/token"

# Load Railway token
if [ ! -f "$TOKEN_FILE" ]; then
    echo "Error: Railway token not found at $TOKEN_FILE"
    exit 1
fi

RAILWAY_TOKEN=$(cat "$TOKEN_FILE")
RAILWAY_API="https://backboard.railway.com/graphql/v2"

# Service and deployment IDs (update these if needed)
SERVICE_ID="flicknames-service-production"

# Helper function to make GraphQL requests
railway_graphql() {
    local query="$1"
    curl -s -X POST "$RAILWAY_API" \
        -H "Authorization: Bearer $RAILWAY_TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"query\": \"$query\"}"
}

# Commands
case "${1:-help}" in
    logs)
        echo "Fetching recent deployment logs..."
        # This is a simplified version - you may need to adjust the query
        railway_graphql "query { me { projects(first: 1) { edges { node { name } } } } }" | jq .
        echo ""
        echo "Note: For full logs, use Railway CLI or dashboard"
        ;;

    status)
        echo "Checking service status..."
        curl -s "https://$SERVICE_ID.up.railway.app/api/v1/health" | jq .
        ;;

    db-stats)
        echo "Fetching database statistics..."
        curl -s "https://$SERVICE_ID.up.railway.app/api/v1/admin/db-stats" | jq .
        ;;

    collection-stats)
        echo "Fetching collection statistics..."
        curl -s "https://$SERVICE_ID.up.railway.app/api/v1/admin/collection-stats" | jq .
        ;;

    professions)
        echo "Fetching professions (top 20)..."
        curl -s "https://$SERVICE_ID.up.railway.app/api/v1/professions" | jq '.[0:20]'
        ;;

    name-professions)
        if [ -z "$2" ]; then
            echo "Usage: $0 name-professions <firstName>"
            exit 1
        fi
        echo "Fetching profession breakdown for name: $2"
        curl -s "https://$SERVICE_ID.up.railway.app/api/v1/professions/for-name/$2" | jq .
        ;;

    trending)
        echo "Fetching trending names (weekly)..."
        curl -s "https://$SERVICE_ID.up.railway.app/api/v1/all-names/trending/weekly?limit=20" | jq .
        ;;

    help|*)
        echo "Railway Helper Script for Flicknames"
        echo ""
        echo "Usage: $0 [command]"
        echo ""
        echo "Commands:"
        echo "  status              - Check service health"
        echo "  db-stats            - Get database statistics"
        echo "  collection-stats    - Get collection statistics by year"
        echo "  professions         - List all professions (top 20)"
        echo "  name-professions <name> - Get profession breakdown for a name"
        echo "  trending            - Get trending names this week"
        echo "  logs                - Fetch deployment logs (basic)"
        echo "  help                - Show this help message"
        echo ""
        echo "Examples:"
        echo "  $0 status"
        echo "  $0 db-stats"
        echo "  $0 name-professions Emma"
        ;;
esac
