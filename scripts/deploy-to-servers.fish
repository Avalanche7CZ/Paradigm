#!/usr/bin/env fish
#
# Paradigm - Deployment Script for PufferPanel Servers (Fish Shell)
# Automatically deploys built JAR files to all Forge servers
#
# Usage: ./scripts/deploy-to-servers.fish [version]
#   version: optional, specify version to deploy (1.18.2, 1.19.2, 1.20.1, 1.21.1, or 'all')
#   If no version specified, deploys all versions
#

# Colors
set GREEN (set_color green)
set BLUE (set_color blue)
set YELLOW (set_color yellow)
set RED (set_color red)
set NC (set_color normal)

# PufferPanel base path
set PUFFERPANEL_BASE "/var/lib/pufferpanel/servers"

# Project paths
set PROJECT_ROOT (dirname (status -f))/..

echo "$BLUE================================================$NC"
echo "$BLUE""Paradigm - Server Deployment Tool$NC"
echo "$BLUE================================================$NC"
echo ""


# Function to deploy a single version
function deploy_version
    set -l mc_version $argv[1]

    # Get server ID directly
    set -l server_id ""
    switch $mc_version
        case "1.18.2"
            set server_id "60accda3"
        case "1.19.2"
            set server_id "da861011"
        case "1.20.1"
            set server_id "aad9b166"
        case "1.21.1"
            set server_id "74da6189"
    end

    if test -z "$server_id"
        echo "$RED  ✗ Invalid version: $mc_version$NC"
        return 1
    end

    set -l jar_file "paradigm-$mc_version-2.0.0b.jar"
    set -l source_path "$PROJECT_ROOT/forge-$mc_version/build/libs/$jar_file"
    set -l dest_path "$PUFFERPANEL_BASE/$server_id/mods/$jar_file"

    echo "$YELLOW""Deploying $mc_version...$NC"

    # Check if JAR exists
    if not test -f "$source_path"
        echo "$RED  ✗ JAR file not found: $source_path$NC"
        echo "$YELLOW  → Run './gradlew build' first$NC"
        return 1
    end

    # Check if server directory exists
    if not test -d "$PUFFERPANEL_BASE/$server_id"
        echo "$RED  ✗ Server directory not found: $PUFFERPANEL_BASE/$server_id$NC"
        return 1
    end

    # Create mods directory if it doesn't exist
    sudo mkdir -p "$PUFFERPANEL_BASE/$server_id/mods"

    # Copy JAR file
    echo "  → Copying to server $server_id..."
    sudo cp "$source_path" "$dest_path"

    # Set proper permissions
    sudo chown -R pufferpanel:pufferpanel "$PUFFERPANEL_BASE/$server_id/mods"

    # Get file size
    set -l size (du -h "$source_path" | cut -f1)

    echo "$GREEN  ✓ Deployed successfully! ($size)$NC"
    echo ""

    return 0
end

# Parse arguments
set VERSION_TO_DEPLOY $argv[1]
if test -z "$VERSION_TO_DEPLOY"
    set VERSION_TO_DEPLOY "all"
end

if test "$VERSION_TO_DEPLOY" = "all"
    echo "$BLUE""Deploying all versions...$NC"
    echo ""

    set FAILED 0
    set SUCCEEDED 0

    # Deploy each version
    for mc_version in "1.18.2" "1.19.2" "1.20.1" "1.21.1"
        if deploy_version $mc_version
            set SUCCEEDED (math $SUCCEEDED + 1)
        else
            set FAILED (math $FAILED + 1)
        end
    end

    echo "$BLUE================================================$NC"
    echo "$GREEN""Deployment complete!$NC"
    echo "  Succeeded: $GREEN$SUCCEEDED$NC"
    if test $FAILED -gt 0
        echo "  Failed:    $RED$FAILED$NC"
    end
    echo "$BLUE================================================$NC"

    if test $FAILED -gt 0
        exit 1
    end
else
    # Deploy specific version
    deploy_version $VERSION_TO_DEPLOY
end

echo ""
echo "$YELLOW""Note: Remember to restart the servers to load the new JAR files!$NC"
echo "$YELLOW""You can restart servers via PufferPanel web interface.$NC"

