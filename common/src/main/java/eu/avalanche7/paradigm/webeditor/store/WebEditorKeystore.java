/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package eu.avalanche7.paradigm.webeditor.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public final class WebEditorKeystore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path consoleKeysPath;
    private final Set<String> trustedConsoleKeys;
    private final Map<String, String> trustedPlayerKeys;

    public WebEditorKeystore(Path consoleKeysPath) {
        this.consoleKeysPath = consoleKeysPath;
        this.trustedConsoleKeys = new CopyOnWriteArraySet<>();
        this.trustedPlayerKeys = new HashMap<>();
        try {
            load();
        } catch (Exception e) {
        }
    }

    // Legacy methods kept for compatibility (console/global)
    public boolean isTrusted(byte[] publicKey) {
        return this.trustedConsoleKeys.contains(hash(publicKey));
    }

    public void trust(byte[] publicKey) {
        if (publicKey == null) return;
        String h = hash(publicKey);
        if (this.trustedConsoleKeys.add(h)) {
            try { save(); } catch (Exception ignored) {}
        }
    }

    // Sender-aware trust APIs
    public boolean isTrusted(ICommandSource sender, byte[] publicKey) {
        if (sender == null || publicKey == null) return false;
        String h = hash(publicKey);
        if (sender.isConsole()) {
            return this.trustedConsoleKeys.contains(h);
        }
        IPlayer p = sender.getPlayer();
        if (p == null) return false;
        String uuid = p.getUUID() == null ? null : p.getUUID().toString();
        String stored = this.trustedPlayerKeys.get(uuid);
        return h.equals(stored);
    }

    public boolean trust(ICommandSource sender, byte[] publicKey) {
        if (sender == null || publicKey == null) return false;
        String h = hash(publicKey);
        if (sender.isConsole()) {
            boolean added = this.trustedConsoleKeys.add(h);
            if (added) try { save(); } catch (Exception ignored) {}
            return added;
        }
        IPlayer p = sender.getPlayer();
        if (p == null) return false;
        String uuid = p.getUUID() == null ? null : p.getUUID().toString();
        String prev = this.trustedPlayerKeys.put(uuid, h);
        try { save(); } catch (Exception ignored) {}
        return !h.equals(prev);
    }

    public boolean untrust(ICommandSource sender, String hashOrAll) {
        if (sender == null) return false;
        if (sender.isConsole()) {
            if (hashOrAll == null || hashOrAll.isEmpty()) return false;
            if ("all".equalsIgnoreCase(hashOrAll)) {
                boolean changed = !this.trustedConsoleKeys.isEmpty();
                this.trustedConsoleKeys.clear();
                if (changed) try { save(); } catch (Exception ignored) {}
                return changed;
            }
            boolean removed = this.trustedConsoleKeys.remove(hashOrAll);
            if (removed) try { save(); } catch (Exception ignored) {}
            return removed;
        } else {
            IPlayer p = sender.getPlayer();
            if (p == null) return false;
            String uuid = p.getUUID() == null ? null : p.getUUID().toString();
            if (hashOrAll != null && !hashOrAll.isEmpty()) {
                String cur = this.trustedPlayerKeys.get(uuid);
                if (hashOrAll.equals(cur)) {
                    this.trustedPlayerKeys.remove(uuid);
                    try { save(); } catch (Exception ignored) {}
                    return true;
                }
                return false;
            } else {
                boolean existed = this.trustedPlayerKeys.remove(uuid) != null;
                if (existed) try { save(); } catch (Exception ignored) {}
                return existed;
            }
        }
    }

    public List<String> listTrusted(ICommandSource sender) {
        List<String> out = new ArrayList<>();
        if (sender == null) return out;
        if (sender.isConsole()) {
            out.addAll(this.trustedConsoleKeys);
        } else {
            IPlayer p = sender.getPlayer();
            if (p != null) {
                String uuid = p.getUUID() == null ? null : p.getUUID().toString();
                String v = this.trustedPlayerKeys.get(uuid);
                if (v != null) out.add(v);
            }
        }
        return out;
    }

    private void load() throws Exception {
        if (Files.exists(this.consoleKeysPath)) {
            try (BufferedReader reader = Files.newBufferedReader(this.consoleKeysPath, StandardCharsets.UTF_8)) {
                KeystoreFile file = GSON.fromJson(reader, KeystoreFile.class);
                if (file != null) {
                    if (file.consoleKeys != null) {
                        this.trustedConsoleKeys.addAll(file.consoleKeys);
                    }
                    if (file.playerKeys != null) {
                        this.trustedPlayerKeys.putAll(file.playerKeys);
                    }
                }
            }
        }
    }

    private void save() throws Exception {
        if (this.consoleKeysPath.getParent() != null && !Files.exists(this.consoleKeysPath.getParent())) {
            try { Files.createDirectories(this.consoleKeysPath.getParent()); } catch (Exception ignored) {}
        }
        try (BufferedWriter writer = Files.newBufferedWriter(this.consoleKeysPath, StandardCharsets.UTF_8)) {
            KeystoreFile file = new KeystoreFile();
            file.consoleKeys = new ArrayList<>(this.trustedConsoleKeys);
            file.playerKeys = new HashMap<>(this.trustedPlayerKeys);
            GSON.toJson(file, writer);
        }
    }

    public static String hash(byte[] buf) {
        byte[] digest = createDigest().digest(buf);
        return Base64.getEncoder().encodeToString(digest);
    }

    private static MessageDigest createDigest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static class KeystoreFile {
        private String _comment = "This file stores a list of trusted editor public keys";
        private List<String> consoleKeys = null;
        private Map<String, String> playerKeys = null;
    }
}
