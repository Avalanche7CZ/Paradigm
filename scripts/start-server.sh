#!/bin/bash
# ====================================================================
# Paradigm Minecraft Server Startup Script (Linux/Unix)
# ====================================================================
# This script ensures your server restarts automatically after shutdown
# Required for Paradigm's Restart module to work properly
# ====================================================================

# ====================================================================
# Configuration - Adjust these settings for your server
# ====================================================================

# Memory allocation (in MB)
MIN_RAM=2048
MAX_RAM=4096

# Server jar file name
SERVER_JAR="forge-server.jar"

# Java arguments (advanced users only)
JAVA_ARGS="-Xms${MIN_RAM}M -Xmx${MAX_RAM}M -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1"

# Server startup mode (nogui for headless, remove for GUI)
SERVER_MODE="nogui"

# Restart delay (in seconds)
RESTART_DELAY=10

# Log file
LOG_FILE="server-restart.log"

# ====================================================================
# Script Start - Do not modify below this line unless you know what you're doing
# ====================================================================

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored messages
print_header() {
    echo -e "${BLUE}====================================================================${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}====================================================================${NC}"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to log messages
log_message() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE"
}

# Check if running as root (not recommended)
if [ "$EUID" -eq 0 ]; then
    print_warning "Running as root is not recommended for security reasons!"
    print_warning "Consider creating a dedicated user for the Minecraft server."
    echo
fi

print_header "Paradigm Minecraft Server Launcher"
echo -e "  Server JAR: ${GREEN}$SERVER_JAR${NC}"
echo -e "  RAM: ${GREEN}${MIN_RAM}MB - ${MAX_RAM}MB${NC}"
echo -e "  Auto-restart: ${GREEN}ENABLED${NC}"
echo -e "  Log file: ${GREEN}$LOG_FILE${NC}"
print_header ""
echo

# Check if Java is installed
if ! command -v java &> /dev/null; then
    print_error "Java is not installed or not in PATH!"
    print_error "Please install Java and try again."
    echo
    echo "On Ubuntu/Debian: sudo apt install openjdk-17-jre-headless"
    echo "On CentOS/RHEL:   sudo yum install java-17-openjdk-headless"
    echo "On Arch:          sudo pacman -S jre-openjdk"
    exit 1
fi

# Display Java version
JAVA_VERSION=$(java -version 2>&1 | head -n 1)
print_success "Java found: $JAVA_VERSION"

# Check if server jar exists
if [ ! -f "$SERVER_JAR" ]; then
    print_error "Server jar file not found: $SERVER_JAR"
    print_error "Please make sure the server jar file exists in the current directory."
    exit 1
fi

print_success "Server jar found: $SERVER_JAR"
echo

# Accept EULA automatically if not exists (optional - comment out if you want manual acceptance)
if [ ! -f "eula.txt" ]; then
    print_warning "eula.txt not found. Creating with eula=true..."
    echo "# By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA)." > eula.txt
    echo "eula=true" >> eula.txt
    print_success "eula.txt created. Please review the Minecraft EULA at https://aka.ms/MinecraftEULA"
    echo
fi

# Create screen session name
SCREEN_NAME="minecraft-server"

# Check if running in screen
if [ -n "$STY" ]; then
    print_success "Running inside screen session"
else
    print_warning "Not running in a screen session"
    print_warning "Consider running this script with: screen -S $SCREEN_NAME ./start-server.sh"
    echo
fi

# Log startup
log_message "=== Server startup script started ==="

# Main restart loop
restart_count=0
while true; do
    restart_count=$((restart_count + 1))

    echo
    print_header "Starting Minecraft Server (Restart #$restart_count)"
    echo -e "  Time: ${GREEN}$(date '+%Y-%m-%d %H:%M:%S')${NC}"
    print_header ""
    echo

    log_message "Starting server (restart #$restart_count)"

    # Start the server
    java $JAVA_ARGS -jar "$SERVER_JAR" $SERVER_MODE

    # Capture exit code
    EXIT_CODE=$?

    echo
    print_header "Server Stopped"
    echo -e "  Exit code: ${YELLOW}$EXIT_CODE${NC}"
    echo -e "  Time: ${GREEN}$(date '+%Y-%m-%d %H:%M:%S')${NC}"
    print_header ""
    echo

    log_message "Server stopped with exit code: $EXIT_CODE"

    # Check if server stopped due to error
    if [ $EXIT_CODE -ne 0 ]; then
        print_warning "Server stopped with non-zero exit code!"
        print_warning "This might indicate a crash or error."
        log_message "WARNING: Non-zero exit code detected"
        echo
    fi

    # Check if stop file exists (used to stop the restart loop)
    if [ -f "STOP_RESTART" ]; then
        print_success "STOP_RESTART file detected. Exiting restart loop."
        log_message "Restart loop stopped by STOP_RESTART file"
        rm -f "STOP_RESTART"
        exit 0
    fi

    echo -e "${YELLOW}Restarting in $RESTART_DELAY seconds...${NC}"
    echo -e "${YELLOW}Press Ctrl+C to cancel restart or create STOP_RESTART file to prevent restart.${NC}"

    # Countdown
    for i in $(seq $RESTART_DELAY -1 1); do
        echo -ne "\rRestarting in $i seconds... "
        sleep 1
    done
    echo

    log_message "Restarting server..."
done

