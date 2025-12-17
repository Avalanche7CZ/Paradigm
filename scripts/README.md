# Paradigm Server Restart Scripts

## ⚠️ IMPORTANT: Restart vs. Shutdown

The Paradigm **Restart module** performs a **scheduled shutdown** of your Minecraft server. It does **NOT** automatically restart the server by itself - this is a limitation of how Minecraft servers work.

To enable automatic restarts, you **MUST** use one of the startup scripts provided in this directory. These scripts create a loop that automatically restarts the server after it shuts down.

---

## 📁 Available Scripts

### Windows
- `start-server.bat` - Universal Windows batch script with auto-restart

### Linux/Unix
- `start-server.sh` - Universal shell script with auto-restart
- `minecraft-server.service` - Systemd service file (recommended for production)

---

## 🚀 Quick Start

### Windows

1. **Copy the script:**
   ```batch
   copy start-server.bat C:\path\to\your\server\
   ```

2. **Edit configuration** in `start-server.bat`:
   - `SERVER_JAR` - Your forge server jar name
   - `MIN_RAM` and `MAX_RAM` - Memory allocation
   - `RESTART_DELAY` - Delay between restarts (seconds)

3. **Run the script:**
   ```batch
   start-server.bat
   ```

4. **To stop completely:** Press `Ctrl+C` when prompted

---

### Linux/Unix (Shell Script)

1. **Copy the script:**
   ```bash
   cp start-server.sh /path/to/your/server/
   chmod +x /path/to/your/server/start-server.sh
   ```

2. **Edit configuration** in `start-server.sh`:
   - `SERVER_JAR` - Your forge server jar name
   - `MIN_RAM` and `MAX_RAM` - Memory allocation
   - `RESTART_DELAY` - Delay between restarts (seconds)

3. **Run the script:**
   ```bash
   ./start-server.sh
   ```

4. **Run in background (recommended):**
   ```bash
   screen -S minecraft ./start-server.sh
   # Or with tmux:
   tmux new -s minecraft ./start-server.sh
   ```

5. **To stop completely:** Create a `STOP_RESTART` file or press `Ctrl+C`

---

### Linux (Systemd Service - RECOMMENDED)

Systemd is the **best option** for production servers as it provides:
- Automatic startup on server boot
- Better resource management
- Automatic crash recovery
- Centralized logging

#### Installation Steps:

1. **Create a dedicated user** (never run as root!):
   ```bash
   sudo useradd -r -m -d /opt/minecraft minecraft
   sudo mkdir -p /opt/minecraft
   sudo chown minecraft:minecraft /opt/minecraft
   ```

2. **Copy server files:**
   ```bash
   sudo cp forge-server.jar /opt/minecraft/
   sudo cp -r mods/ config/ /opt/minecraft/
   sudo chown -R minecraft:minecraft /opt/minecraft
   ```

3. **Edit the service file:**
   - Open `minecraft-server.service`
   - Change `User=minecraft` if using different user
   - Change `WorkingDirectory=/opt/minecraft` to your server path
   - Adjust `MIN_RAM` and `MAX_RAM`
   - Adjust `SERVER_JAR` name

4. **Install the service:**
   ```bash
   sudo cp minecraft-server.service /etc/systemd/system/
   sudo systemctl daemon-reload
   ```

5. **Enable and start:**
   ```bash
   sudo systemctl enable minecraft-server
   sudo systemctl start minecraft-server
   ```

#### Systemd Commands:

```bash
# Start server
sudo systemctl start minecraft-server

# Stop server
sudo systemctl stop minecraft-server

# Restart server
sudo systemctl restart minecraft-server

# View status
sudo systemctl status minecraft-server

# View live logs
sudo journalctl -u minecraft-server -f

# View last 100 lines
sudo journalctl -u minecraft-server -n 100

# Disable auto-start on boot
sudo systemctl disable minecraft-server

# Enable auto-start on boot
sudo systemctl enable minecraft-server
```

---

## ⚙️ Configuration Guide

### Memory Allocation

**Example configurations:**

```bash
# Small server (5 players, light mods)
MIN_RAM=2048
MAX_RAM=4096

# Medium server (20 players, medium mods)
MIN_RAM=4096
MAX_RAM=8192

# Large server (50+ players, heavy mods)
MIN_RAM=8192
MAX_RAM=16384
```

### Java Version

- **Minecraft 1.18+:** Java 17 or higher (recommended: Java 21)

- **Check your Java version:**
```bash
java -version
```

**Install Java (examples):**
```bash
# Ubuntu/Debian
sudo apt update
sudo apt install openjdk-21-jre-headless

# CentOS/RHEL
sudo yum install java-21-openjdk-headless

# Arch Linux
sudo pacman -S jre21-openjdk
```

---

## 🛠️ Troubleshooting

### Server doesn't restart after shutdown

**Cause:** Not using a restart script

**Solution:** Use one of the provided scripts (see Quick Start above)

---

### "Java not found" error

**Cause:** Java not installed or not in PATH

**Solution:**
- Install Java (see Java Version section)
- On Windows: Add Java to PATH environment variable
- On Linux: Most package managers handle this automatically

---

### "Permission denied" error (Linux)

**Cause:** Script not executable

**Solution:**
```bash
chmod +x start-server.sh
```

---

### Out of memory errors

**Cause:** Insufficient memory allocation

**Solution:** Increase `MAX_RAM` in script configuration

---

### Server takes too long to stop

**Cause:** Normal - Minecraft saves world before stopping

**Solution:** Wait patiently, or increase `RESTART_DELAY` if needed

---

### Systemd service won't start

**Common causes and solutions:**

1. **Wrong working directory:**
   ```bash
   sudo systemctl status minecraft-server
   # Check for "No such file or directory" errors
   # Fix WorkingDirectory in service file
   ```

2. **Wrong user permissions:**
   ```bash
   ls -la /opt/minecraft
   # Check if minecraft user owns all files
   sudo chown -R minecraft:minecraft /opt/minecraft
   ```

3. **Wrong jar name:**
   ```bash
   ls /opt/minecraft/*.jar
   # Update SERVER_JAR in service file
   ```

4. **Java not found:**
   ```bash
   which java
   # Update ExecStart path in service file if needed
   ```

---

## 📊 Monitoring

### Windows

Monitor in command prompt or PowerShell window

### Linux (Shell Script)

```bash
# View restart log
tail -f server-restart.log

# If using screen:
screen -r minecraft

# If using tmux:
tmux attach -t minecraft
```

### Linux (Systemd)

```bash
# Real-time logs
sudo journalctl -u minecraft-server -f

# Last 100 lines
sudo journalctl -u minecraft-server -n 100

# Logs since boot
sudo journalctl -u minecraft-server -b

# Logs for specific date
sudo journalctl -u minecraft-server --since "2025-01-01" --until "2025-01-02"
```

---

## 🔒 Security 

### Never run as root!

Running Minecraft as root is a **severe security risk**. Always use a dedicated user:

```bash
# Create dedicated user
sudo useradd -r -m -d /opt/minecraft minecraft

# Run server as this user
sudo -u minecraft ./start-server.sh
```

### Firewall configuration

```bash
# UFW (Ubuntu/Debian)
sudo ufw allow 25565/tcp
sudo ufw allow 25565/udp

# Firewalld (CentOS/RHEL)
sudo firewall-cmd --permanent --add-port=25565/tcp
sudo firewall-cmd --permanent --add-port=25565/udp
sudo firewall-cmd --reload

# iptables (manual)
sudo iptables -A INPUT -p tcp --dport 25565 -j ACCEPT
sudo iptables -A INPUT -p udp --dport 25565 -j ACCEPT
```

### File permissions

```bash
# Set correct ownership
sudo chown -R minecraft:minecraft /opt/minecraft

# Set correct permissions
sudo chmod -R 750 /opt/minecraft
```

---

## 💡 Advanced Usage

### Multiple servers on one machine

1. **Copy scripts to different directories**
2. **Change ports in server.properties** for each server
3. **Adjust memory allocation** based on total available RAM
4. **Use different systemd service names** (e.g., `minecraft-server-1`, `minecraft-server-2`)

### Automatic backups before restart

Add to `preRestartCommands` in Paradigm config:

```toml
[[preRestartCommands]]
command = "save-all"
secondsBefore = 30

[[preRestartCommands]]
command = "say Backing up world..."
secondsBefore = 25

# Linux backup command
[[preRestartCommands]]
command = "tar -czf /backups/world-$(date +%Y%m%d-%H%M%S).tar.gz world/"
secondsBefore = 20
```

### Resource monitoring

```bash
# Monitor CPU and memory usage
top -p $(pgrep -f minecraft)

# Or with htop (more user-friendly)
htop -p $(pgrep -f minecraft)

# Check disk usage
df -h

# Check disk I/O
iostat -x 2
```

---

## 📚 Additional Resources

- **Paradigm Documentation:** [Link to your docs]
- **Aikar's Flags (JVM optimization):** https://aikar.co/2018/07/02/tuning-the-jvm-g1gc-garbage-collector-flags-for-minecraft/
- **Systemd Documentation:** https://www.freedesktop.org/software/systemd/man/systemd.service.html

---

## 🆘 Getting Help

If you're still having issues:

1. Check Paradigm logs in `logs/latest.log`
2. Check script logs (`server-restart.log` or systemd journal)
3. Join our Discord: [Your Discord link]
4. Create an issue: [Your GitHub issues link]

When asking for help, please provide:
- Operating system and version
- Java version (`java -version`)
- Minecraft/Forge version
- Paradigm version
- Error messages from logs
- Script configuration (memory, jar name, etc.)

---

## 📝 Script Customization

All scripts are designed to be easily customizable. Key variables are at the top of each file:

- **Memory:** Adjust `MIN_RAM` and `MAX_RAM`
- **Jar file:** Change `SERVER_JAR` to match your file
- **Restart delay:** Modify `RESTART_DELAY`
- **Java arguments:** Edit `JAVA_ARGS` for custom JVM flags

Feel free to modify the scripts to fit your needs!

---

