#!/bin/bash
#
# Paradigm - Deployment Script for PufferPanel Servers
# Automatically deploys built JAR files to all Forge servers
#
# Usage: ./scripts/deploy-to-servers.sh [version]
#   version: optional, specify version to deploy (1.18.2, 1.19.2, 1.20.1, 1.21.1, or 'all')
#   If no version specified, deploys all versions
#

set -e  # Exit on error

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# PufferPanel server IDs
declare -A SERVERS=(
    ["1.18.2"]="60accda3"
    ["1.19.2"]="da861011"
    ["1.20.1"]="aad9b166"
    ["1.21.1"]="74da6189"
)

# PufferPanel base path
PUFFERPANEL_BASE="/var/lib/pufferpanel/servers"

# Project paths
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo -e "${BLUE}================================================${NC}"
echo -e "${BLUE}Paradigm - Server Deployment Tool${NC}"
echo -e "${BLUE}================================================${NC}"
echo ""

# Function to deploy a single version
deploy_version() {
    local version=$1
    local server_id=${SERVERS[$version]}
    local jar_file="paradigm-${version}-2.0.0b.jar"
    local source_path="$PROJECT_ROOT/forge-${version}/build/libs/$jar_file"
    local dest_path="$PUFFERPANEL_BASE/$server_id/mods/$jar_file"

    echo -e "${YELLOW}Deploying ${version}...${NC}"

    # Check if JAR exists
    if [ ! -f "$source_path" ]; then
        echo -e "${RED}  ✗ JAR file not found: $source_path${NC}"
        echo -e "${YELLOW}  → Run './gradlew build' first${NC}"
        return 1
    fi

    # Check if server directory exists
    if [ ! -d "$PUFFERPANEL_BASE/$server_id" ]; then
        echo -e "${RED}  ✗ Server directory not found: $PUFFERPANEL_BASE/$server_id${NC}"
        return 1
    fi

    # Create mods directory if it doesn't exist
    sudo mkdir -p "$PUFFERPANEL_BASE/$server_id/mods"

    # Copy JAR file
    echo -e "  → Copying to server $server_id..."
    sudo cp "$source_path" "$dest_path"

    # Set proper permissions
    sudo chown -R pufferpanel:pufferpanel "$PUFFERPANEL_BASE/$server_id/mods"

    # Get file size
    local size=$(du -h "$source_path" | cut -f1)

    echo -e "${GREEN}  ✓ Deployed successfully! (${size})${NC}"
    echo ""

    return 0
}

# Parse arguments
VERSION_TO_DEPLOY="${1:-all}"

if [ "$VERSION_TO_DEPLOY" = "all" ]; then
    echo -e "${BLUE}Deploying all versions...${NC}"
    echo ""

    FAILED=0
    SUCCEEDED=0

    for version in "${!SERVERS[@]}"; do
        if deploy_version "$version"; then
            ((SUCCEEDED++))
        else
            ((FAILED++))
        fi
    done

    echo -e "${BLUE}================================================${NC}"
    echo -e "${GREEN}Deployment complete!${NC}"
    echo -e "  Succeeded: ${GREEN}${SUCCEEDED}${NC}"
    if [ $FAILED -gt 0 ]; then
        echo -e "  Failed:    ${RED}${FAILED}${NC}"
    fi
    echo -e "${BLUE}================================================${NC}"

    if [ $FAILED -gt 0 ]; then
        exit 1
    fi
else
    # Deploy specific version
    if [ -z "${SERVERS[$VERSION_TO_DEPLOY]}" ]; then
        echo -e "${RED}Invalid version: $VERSION_TO_DEPLOY${NC}"
        echo -e "${YELLOW}Available versions: ${!SERVERS[@]}${NC}"
        exit 1
    fi

    deploy_version "$VERSION_TO_DEPLOY"
fi

echo ""
echo -e "${YELLOW}Note: Remember to restart the servers to load the new JAR files!${NC}"
echo -e "${YELLOW}You can restart servers via PufferPanel web interface.${NC}"

