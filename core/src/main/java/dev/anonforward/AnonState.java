package dev.anonforward;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

final class AnonState {
    static final long PLACEHOLDER_UIN = 1094950020L;
    static final long PLACEHOLDER_GROUP = 284840486L;
    static final String PLACEHOLDER_UID = "u_B-xbHgFtPzMTjvfvZNVuqw";
    private static final Map<String, String> aliases = new HashMap<>();
    private static final Map<String, String> nameAliases = new HashMap<>();
    private static final AtomicLong synthetic = new AtomicLong(5_000_000L);
    private static File stateFile;
    private static File packetMarkerFile;
    private static File failureMarkerFile;
    private static boolean armed;
    private static boolean forwardStarted;
    private static long stateFileModified;
    private static int aliasCounter;
    private static boolean forwardNoticeShown;

    private AnonState() {}

    static synchronized void initialize(String hostDataDir) {
        stateFile = new File(new File(hostDataDir, "files"), "anon_forward.state");
        packetMarkerFile = new File(new File(hostDataDir, "files"), "anon_forward.packet_ok");
        failureMarkerFile = new File(new File(hostDataDir, "files"), "anon_forward.packet_failed");
        try {
            if (stateFile.isFile()) {
                byte[] data;
                try (FileInputStream input = new FileInputStream(stateFile)) {
                    data = new byte[(int) stateFile.length()];
                    int offset = 0;
                    while (offset < data.length) {
                        int count = input.read(data, offset, data.length - offset);
                        if (count < 0) break;
                        offset += count;
                    }
                }
                String value = new String(data, StandardCharsets.UTF_8).trim();
                armed = "1".equals(value);
                stateFileModified = stateFile.lastModified();
            }
        } catch (Exception e) {
            AFLog.e("Failed to read state", e);
        }
    }

    static synchronized void arm() {
        aliases.clear();
        nameAliases.clear();
        aliasCounter = 0;
        forwardNoticeShown = false;
        forwardStarted = false;
        armed = true;
        if (packetMarkerFile != null && packetMarkerFile.isFile()) packetMarkerFile.delete();
        if (failureMarkerFile != null && failureMarkerFile.isFile()) failureMarkerFile.delete();
        persist();
        AFLog.i("Anonymous forwarding armed for the selected operation");
    }

    static synchronized void disarm() {
        armed = false;
        forwardStarted = false;
        if (packetMarkerFile != null && packetMarkerFile.isFile()) packetMarkerFile.delete();
        if (failureMarkerFile != null && failureMarkerFile.isFile()) failureMarkerFile.delete();
        persist();
        AFLog.i("Anonymous forwarding disarmed");
    }

    static synchronized boolean isArmed() {
        if (stateFile != null && stateFile.isFile() && stateFile.lastModified() != stateFileModified) {
            try {
                byte[] data;
                try (FileInputStream input = new FileInputStream(stateFile)) {
                    data = new byte[(int) stateFile.length()];
                    int offset = 0;
                    while (offset < data.length) {
                        int count = input.read(data, offset, data.length - offset);
                        if (count < 0) break;
                        offset += count;
                    }
                }
                armed = "1".equals(new String(data, StandardCharsets.UTF_8).trim());
                stateFileModified = stateFile.lastModified();
            } catch (Exception e) {
                AFLog.e("Failed to refresh state", e);
            }
        }
        return armed;
    }

    static synchronized void markForwardStarted() {
        if (armed) forwardStarted = true;
    }

    static synchronized boolean hasForwardStarted() {
        return forwardStarted;
    }

    static synchronized String aliasFor(String uid, long uin, String displayName) {
        String name = cleanName(displayName);
        String key;
        if (uid != null && !uid.isBlank() && !uid.equals(PLACEHOLDER_UID) && !uid.equals("0")) {
            key = "uid:" + uid;
        } else if (uin > 0 && uin != PLACEHOLDER_UIN) {
            key = "uin:" + Long.toUnsignedString(uin);
        } else {
            key = "name:" + name;
        }
        String existing = aliases.get(key);
        if (existing != null) return existing;
        existing = nameAliases.get(name);
        if (existing != null) {
            aliases.put(key, existing);
            return existing;
        }
        String alias = "匿名用户" + (++aliasCounter);
        aliases.put(key, alias);
        nameAliases.put(name, alias);
        return alias;
    }

    static synchronized String aliasForName(String displayName) {
        String name = cleanName(displayName);
        return nameAliases.computeIfAbsent(name, ignored -> "匿名用户" + (++aliasCounter));
    }

    static synchronized String aliasForKnownName(String displayName) {
        if (displayName == null) return null;
        return nameAliases.get(cleanName(displayName));
    }

    static synchronized String replaceKnownPreviewPrefix(String text) {
        if (text == null || text.isEmpty()) return text;
        String matchedName = null;
        int colonIndex = -1;
        for (String name : nameAliases.keySet()) {
            if (!text.startsWith(name)) continue;
            int index = name.length();
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
            if (index >= text.length() || (text.charAt(index) != ':' && text.charAt(index) != '：')) continue;
            if (matchedName == null || name.length() > matchedName.length()) {
                matchedName = name;
                colonIndex = index;
            }
        }
        if (matchedName == null) return text;
        return nameAliases.get(matchedName) + text.substring(colonIndex);
    }

    static long nextSyntheticSequence() {
        return synthetic.incrementAndGet();
    }

    static long nextSyntheticMessageId() {
        return 72_057_595_890_000_000L + synthetic.incrementAndGet();
    }

    static synchronized boolean markForwardEntrySeen() {
        if (forwardNoticeShown) return false;
        forwardNoticeShown = true;
        return true;
    }

    static synchronized void markPacketSanitized() {
        if (packetMarkerFile == null) return;
        try {
            File parent = packetMarkerFile.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileOutputStream output = new FileOutputStream(packetMarkerFile, false)) {
                output.write('1');
            }
        } catch (Exception e) {
            AFLog.e("Failed to mark sanitized packet", e);
        }
    }

    static synchronized boolean hasSanitizedPacket() {
        if (packetMarkerFile == null || !packetMarkerFile.isFile()) return false;
        if (failureMarkerFile != null && failureMarkerFile.isFile()) return false;
        return true;
    }

    static synchronized void markPacketFailed() {
        if (failureMarkerFile == null) return;
        try {
            File parent = failureMarkerFile.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileOutputStream output = new FileOutputStream(failureMarkerFile, false)) {
                output.write('1');
            }
        } catch (Exception e) {
            AFLog.e("Failed to mark packet failure", e);
        }
    }

    private static String cleanName(String name) {
        if (name == null || name.isBlank()) return "QQ用户";
        return name.trim();
    }

    private static void persist() {
        if (stateFile == null) return;
        try {
            File parent = stateFile.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileOutputStream output = new FileOutputStream(stateFile, false)) {
                output.write(armed ? '1' : '0');
            }
            stateFileModified = stateFile.lastModified();
        } catch (Exception e) {
            AFLog.e("Failed to persist state", e);
        }
    }
}
