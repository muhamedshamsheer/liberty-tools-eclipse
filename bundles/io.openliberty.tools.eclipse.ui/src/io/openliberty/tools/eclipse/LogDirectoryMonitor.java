/*******************************************************************************
 * Copyright (c) 2022, 2026 IBM Corporation and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial implementation
 *******************************************************************************/
package io.openliberty.tools.eclipse;

import java.io.Closeable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONObject;

import io.openliberty.tools.eclipse.logging.Trace;

/**
 * Monitors Liberty server log directories for file-system changes using WatchService.
 *
 * If the logs directory exists it is watched directly along with its sub-directories.
 * If it does not exist yet the parent is watched and the watch is promoted once the
 * directory appears (i.e. after the Liberty server starts for the first time).
 *
 * Rename detection uses snapshot reconciliation with no timing assumptions. On every
 * WatchService event the directory is re-scanned and compared to the previous snapshot.
 * When exactly one entry disappeared and one appeared of the same kind (both files or
 * both sub-directories) the change is treated as a rename and the original group is
 * associated with the new name. Hints are persisted to disk so they survive Eclipse restarts.
 * Stale hints (pointing to files or dirs no longer on disk) are pruned on load.
 *
 * Hint keys use a "dir:" prefix for sub-directory entries to distinguish them from file stems.
 */
public class LogDirectoryMonitor implements Closeable {

    /** Sub-directory inside the plugin workarea where rename hints are persisted. */
    private static final String HINTS_DIR = "log-rename-hints";

    /**
     * Key prefix used to distinguish sub-directory entries from file-stem entries
     * in the snapshot and rename-hint maps. Using a constant prevents silent breakage
     * if the prefix ever needs to change.
     */
    static final String DIR_PREFIX = "dir:";

    // -------------------------------------------------------------------------
    // Per-project state
    // -------------------------------------------------------------------------

    private static class Entry {
        final String              projectName;
        final Path                logsDir;
        final Runnable            onChange;
        boolean                   watchingLogsDir;
        final Map<String, String> snapshot    = new HashMap<>();
        final Map<String, String> renameHints = new ConcurrentHashMap<>();
        final Map<WatchKey, String> subDirKeys = new ConcurrentHashMap<>();

        Entry(String projectName, Path logsDir, Runnable onChange) {
            this.projectName     = projectName;
            this.logsDir         = logsDir;
            this.onChange        = onChange;
            this.watchingLogsDir = false;
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private WatchService                watcher;
    private Thread                      watchThread;
    private volatile boolean            running;
    private final Map<WatchKey, Entry>  keyToEntry   = new ConcurrentHashMap<>();
    private final Map<String, WatchKey> projectToKey = new ConcurrentHashMap<>();
    private final Map<String, Entry>    projectEntry = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Starts the WatchService and the background poll thread.
     */
    public void start() {
        try {
            watcher     = FileSystems.getDefault().newWatchService();
            running     = true;
            watchThread = new Thread(this::pollLoop, "liberty-log-dir-monitor");
            watchThread.setDaemon(true);
            watchThread.start();
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, "LogDirectoryMonitor: WatchService started.");
            }
        } catch (Exception e) {
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, "LogDirectoryMonitor: failed to start", e);
            }
        }
    }

    /**
     * Registers a project for monitoring. If the same logsDir is already being watched the call
     * is a no-op so accumulated rename hints are not lost. If the logs directory does not yet exist
     * the parent is watched instead and the watch is promoted automatically when the directory appears.
     *
     * @param projectName The name of the project to monitor.
     * @param logsDir     The expected logs directory path (may not exist yet).
     * @param onChange    Callback invoked on the poll thread whenever a change is detected.
     */
    public void register(String projectName, Path logsDir, Runnable onChange) {
        if (watcher == null || logsDir == null) return;
        Entry existing = projectEntry.get(projectName);
        if (existing != null && existing.logsDir.equals(logsDir)) {
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, "LogDirectoryMonitor: already watching [" + projectName + "], skipping re-register.");
            }
            return;
        }
        unregister(projectName);
        try {
            Entry entry = new Entry(projectName, logsDir, onChange);
            loadHints(entry);
            WatchKey key;
            if (logsDir.toFile().isDirectory()) {
                takeSnapshot(entry);
                key = logsDir.register(watcher,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE);
                registerSubDirs(entry);
                entry.watchingLogsDir = true;
                if (Trace.isEnabled()) {
                    Trace.getTracer().trace(Trace.TRACE_TOOLS, "LogDirectoryMonitor: watching [" + projectName + "]: " + logsDir);
                }
            } else {
                Path parent = logsDir.getParent();
                if (parent == null || !parent.toFile().isDirectory()) return;
                key = parent.register(watcher,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE);
                if (Trace.isEnabled()) {
                    Trace.getTracer().trace(Trace.TRACE_TOOLS, "LogDirectoryMonitor: logs dir absent for [" + projectName + "], watching parent: " + parent);
                }
            }
            keyToEntry.put(key, entry);
            projectToKey.put(projectName, key);
            projectEntry.put(projectName, entry);
        } catch (Exception e) {
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, "LogDirectoryMonitor: failed to register " + projectName, e);
            }
        }
    }

    /**
     * Cancels all watches for the given project including sub-directory keys.
     *
     * @param projectName The name of the project to stop monitoring.
     */
    public void unregister(String projectName) {
        WatchKey key = projectToKey.remove(projectName);
        if (key != null) { keyToEntry.remove(key); key.cancel(); }
        Entry entry = projectEntry.remove(projectName);
        if (entry != null) {
            entry.subDirKeys.keySet().forEach(k -> { keyToEntry.remove(k); k.cancel(); });
            entry.subDirKeys.clear();
        }
    }

    /**
     * Returns rename hints for a project as an unmodifiable map of new key to original group.
     * File hints use the file stem as key; sub-directory hints use the {@code "dir:<name>"} key.
     *
     * @param projectName The name of the project whose hints are requested.
     *
     * @return An unmodifiable map of new key to original group; never null, empty when no hints exist.
     */
    public Map<String, String> getRenameHints(String projectName) {
        Entry entry = projectEntry.get(projectName);
        return entry == null ? Collections.emptyMap() : Collections.unmodifiableMap(entry.renameHints);
    }

    /**
     * Stops the monitor, interrupts the poll thread, and releases all WatchService resources.
     */
    public void stop() {
        running = false;
        if (watchThread != null) watchThread.interrupt();
        try { if (watcher != null) watcher.close(); } catch (Exception ignored) { }
    }

    @Override
    public void close() { stop(); }

    // -------------------------------------------------------------------------
    // Poll loop
    // -------------------------------------------------------------------------

    /**
     * Background poll loop that waits for WatchService events and dispatches them to reconcile
     * or promote operations. Runs on a dedicated daemon thread until {@link #stop()} is called.
     */
    private void pollLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                break;
            }
            Entry entry = keyToEntry.get(key);
            if (entry != null) {
                key.pollEvents(); // drain all events before reconciling
                if (Trace.isEnabled()) {
                    Trace.getTracer().trace(Trace.TRACE_TOOLS, "LogDirectoryMonitor: event received. watchingLogsDir="
                            + entry.watchingLogsDir + " isSubDir=" + entry.subDirKeys.containsKey(key));
                }
                if (!entry.watchingLogsDir) {
                    if (entry.logsDir.toFile().isDirectory()) promoteToLogsDir(entry, key);
                } else {
                    reconcile(entry);
                    entry.onChange.run();
                }
            }
            if (!key.reset()) handleKeyInvalid(key);
        }
    }

    /**
     * Promotes the watch from the parent directory to the logs directory once it appears on disk.
     * Cancels the parent key, registers the logs directory directly, and fires the onChange callback.
     *
     * @param entry  The per-project entry whose watch is being promoted.
     * @param oldKey The parent-directory WatchKey to cancel.
     */
    private void promoteToLogsDir(Entry entry, WatchKey oldKey) {
        oldKey.cancel();
        keyToEntry.remove(oldKey);
        try {
            takeSnapshot(entry);
            WatchKey newKey = entry.logsDir.register(watcher,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE);
            entry.watchingLogsDir = true;
            keyToEntry.put(newKey, entry);
            updateProjectKey(oldKey, newKey);
            registerSubDirs(entry);
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, "LogDirectoryMonitor: promoted to watch logs dir: " + entry.logsDir);
            }
            entry.onChange.run();
        } catch (Exception e) {
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, "LogDirectoryMonitor: failed to promote watch", e);
            }
        }
    }

    /**
     * Handles an invalidated WatchKey. When the logs directory itself is deleted the watch falls back
     * to the parent directory so the logs directory can be re-detected when it reappears.
     *
     * @param key The invalidated WatchKey.
     */
    private void handleKeyInvalid(WatchKey key) {
        Entry removed = keyToEntry.remove(key);
        if (removed == null || !removed.watchingLogsDir) return;
        removed.watchingLogsDir = false;
        removed.snapshot.clear();
        try {
            Path parent = removed.logsDir.getParent();
            if (parent != null && parent.toFile().isDirectory()) {
                WatchKey parentKey = parent.register(watcher,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE);
                keyToEntry.put(parentKey, removed);
                updateProjectKey(key, parentKey);
                if (Trace.isEnabled()) {
                    Trace.getTracer().trace(Trace.TRACE_TOOLS, "LogDirectoryMonitor: logs dir gone, watching parent.");
                }
            }
        } catch (Exception e) {
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, "LogDirectoryMonitor: failed to fall back to parent watch", e);
            }
        }
    }

    /**
     * Replaces all projectToKey entries that point to oldKey with newKey.
     *
     * @param oldKey The WatchKey being replaced.
     * @param newKey The replacement WatchKey.
     */
    private void updateProjectKey(WatchKey oldKey, WatchKey newKey) {
        projectToKey.entrySet().stream()
                .filter(e -> e.getValue() == oldKey)
                .forEach(e -> projectToKey.put(e.getKey(), newKey));
    }

    // -------------------------------------------------------------------------
    // Snapshot and reconciliation
    // -------------------------------------------------------------------------

    /**
     * Builds the snapshot from the current disk state. File stems and sub-directory names are
     * both captured; existing rename hints are applied so the snapshot already reflects the
     * canonical group for previously detected renames.
     *
     * @param entry The per-project entry whose snapshot is to be refreshed.
     */
    private static void takeSnapshot(Entry entry) {
        entry.snapshot.clear();
        // Reuse scanDisk but override group values with any known rename hints.
        scanDisk(entry.logsDir).forEach((key, defaultGrp) -> {
            String resolved = entry.renameHints.getOrDefault(key, defaultGrp);
            entry.snapshot.put(key, resolved);
        });
        if (Trace.isEnabled()) {
            Trace.getTracer().trace(Trace.TRACE_TOOLS, "LogDirectoryMonitor: snapshot taken: " + entry.snapshot);
        }
    }

    /**
     * Compares the previous snapshot to the current disk state to detect renames. A rename is
     * confirmed when exactly one entry disappeared and one appeared of the same kind. On a confirmed
     * rename the hint is recorded, the sub-directory watch is re-registered if needed, and the hint
     * is persisted asynchronously. New sub-directories that appeared without a matching delete are
     * registered for watching.
     *
     * @param entry The per-project entry to reconcile.
     */
    private void reconcile(Entry entry) {
        Map<String, String> current  = scanDisk(entry.logsDir);
        Map<String, String> gone     = new HashMap<>();
        Set<String>         appeared = new HashSet<>();
        for (Map.Entry<String, String> e : entry.snapshot.entrySet()) {
            if (!current.containsKey(e.getKey())) gone.put(e.getKey(), e.getValue());
        }
        for (String k : current.keySet()) {
            if (!entry.snapshot.containsKey(k)) appeared.add(k);
        }
        if (Trace.isEnabled()) {
            Trace.getTracer().trace(Trace.TRACE_TOOLS, "LogDirectoryMonitor: reconcile — gone=" + gone.keySet() + " appeared=" + appeared);
        }

        if (gone.size() == 1 && appeared.size() == 1) {
            String oldKey    = gone.keySet().iterator().next();
            String newKey    = appeared.iterator().next();
            boolean oldIsDir = oldKey.startsWith(DIR_PREFIX);
            boolean newIsDir = newKey.startsWith(DIR_PREFIX);
            if (oldIsDir == newIsDir) {   // same kind: both files or both dirs
                String origGroup = gone.get(oldKey);
                entry.renameHints.put(newKey, origGroup);
                entry.renameHints.remove(oldKey);
                if (Trace.isEnabled()) {
                    Trace.getTracer().trace(Trace.TRACE_TOOLS, "LogDirectoryMonitor: rename detected: " + oldKey + " -> " + newKey + " (group=" + origGroup + "); hints=" + entry.renameHints);
                }
                if (newIsDir) reregisterSubDir(entry, oldKey.substring(DIR_PREFIX.length()), newKey.substring(DIR_PREFIX.length()));
                saveHintsAsync(entry);
            }
        } else {
            if (Trace.isEnabled() && (!gone.isEmpty() || !appeared.isEmpty())) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, "LogDirectoryMonitor: no rename — gone=" + gone.size() + " appeared=" + appeared.size());
            }
            for (String k : appeared) {
                if (k.startsWith(DIR_PREFIX)) registerSubDir(entry, k.substring(DIR_PREFIX.length()));
            }
        }

        // Rebuild snapshot with current hints applied.
        takeSnapshot(entry);
    }

    /**
     * Scans the logs directory and returns a map of snapshot key to default group for
     * all .log files and sub-directories present on disk at the time of the call.
     * File stems use the stem as key; sub-directories use {@link #DIR_PREFIX} + name.
     * This is the single directory-walk used by both {@link #takeSnapshot} and
     * {@link #reconcile}, eliminating the previously duplicated {@code scanCurrent} walk.
     *
     * @param logsDir The logs directory to scan.
     *
     * @return A map of key to default group name; never null.
     */
    private static Map<String, String> scanDisk(Path logsDir) {
        Map<String, String> map = new HashMap<>();
        File[] all = logsDir.toFile().listFiles();
        if (all != null) {
            for (File f : all) {
                if (f.isFile() && f.getName().endsWith(".log")) {
                    String stem = stemOf(f.getName());
                    map.put(stem, defaultGroup(stem));
                } else if (f.isDirectory()) {
                    map.put(DIR_PREFIX + f.getName(), f.getName());
                }
            }
        }
        return map;
    }

    // -------------------------------------------------------------------------
    // Sub-directory watch management
    // -------------------------------------------------------------------------

    /**
     * Registers WatchService watches for all existing sub-directories of the logs directory.
     *
     * @param entry The per-project entry whose sub-directories are to be watched.
     */
    private void registerSubDirs(Entry entry) {
        File[] dirs = entry.logsDir.toFile().listFiles(File::isDirectory);
        if (dirs != null) {
            for (File dir : dirs) registerSubDir(entry, dir.getName());
        }
    }

    /**
     * Registers a WatchService watch for a single named sub-directory inside the logs directory.
     *
     * @param entry   The per-project entry that owns the sub-directory watch.
     * @param dirName The name of the sub-directory to watch.
     */
    private void registerSubDir(Entry entry, String dirName) {
        try {
            Path subDir = entry.logsDir.resolve(dirName);
            if (!subDir.toFile().isDirectory()) return;
            WatchKey subKey = subDir.register(watcher,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE);
            entry.subDirKeys.put(subKey, dirName);
            keyToEntry.put(subKey, entry);
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, "LogDirectoryMonitor: watching sub-dir: " + subDir);
            }
        } catch (Exception e) {
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, "LogDirectoryMonitor: failed to watch sub-dir " + dirName, e);
            }
        }
    }

    /**
     * Cancels the WatchService watch for the old sub-directory name and registers a new watch
     * under the new name.
     *
     * @param entry      The per-project entry that owns the sub-directory watch.
     * @param oldDirName The sub-directory name that was removed or renamed.
     * @param newDirName The new sub-directory name to watch.
     */
    private void reregisterSubDir(Entry entry, String oldDirName, String newDirName) {
        entry.subDirKeys.entrySet().removeIf(e -> {
            if (!e.getValue().equals(oldDirName)) return false;
            keyToEntry.remove(e.getKey());
            e.getKey().cancel();
            return true;
        });
        registerSubDir(entry, newDirName);
        if (Trace.isEnabled()) {
            Trace.getTracer().trace(Trace.TRACE_TOOLS, "LogDirectoryMonitor: sub-dir re-registered: " + oldDirName + " -> " + newDirName);
        }
    }

    // -------------------------------------------------------------------------
    // Hint persistence
    // -------------------------------------------------------------------------

    /**
     * Loads persisted rename hints from disk into the entry. Any hint whose key no longer exists
     * on disk is silently discarded to prevent stale hints from accumulating after files or
     * directories are deleted.
     *
     * @param entry The per-project entry to populate with loaded hints.
     */
    private static void loadHints(Entry entry) {
        try {
            Path file = hintsFile(entry.projectName);
            if (file == null || !file.toFile().exists()) return;
            JSONObject obj    = new JSONObject(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8));
            Set<String> onDisk = currentKeys(entry.logsDir);
            obj.keySet().stream()
               .filter(onDisk::contains)
               .forEach(k -> entry.renameHints.put(k, obj.getString(k)));
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, "LogDirectoryMonitor: loaded hints for [" + entry.projectName + "]: " + entry.renameHints);
            }
        } catch (Exception e) {
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, "LogDirectoryMonitor: failed to load hints for " + entry.projectName, e);
            }
        }
    }

    /**
     * Persists the current rename hints to disk on a daemon thread so the watch thread is not blocked.
     *
     * @param entry The per-project entry whose hints are to be saved.
     */
    private static void saveHintsAsync(Entry entry) {
        Map<String, String> copy = new HashMap<>(entry.renameHints);
        Thread t = new Thread(() -> {
            try {
                Path file = hintsFile(entry.projectName);
                if (file == null) return;
                JSONObject obj = new JSONObject();
                copy.forEach(obj::put);
                try (FileWriter fw = new FileWriter(file.toFile(), java.nio.charset.StandardCharsets.UTF_8)) { fw.write(obj.toString()); }
                if (Trace.isEnabled()) {
                    Trace.getTracer().trace(Trace.TRACE_TOOLS, "LogDirectoryMonitor: hints saved for [" + entry.projectName + "]: " + copy);
                }
            } catch (Exception e) {
                if (Trace.isEnabled()) {
                    Trace.getTracer().trace(Trace.TRACE_TOOLS, "LogDirectoryMonitor: failed to save hints for " + entry.projectName, e);
                }
            }
        }, "liberty-log-hints-save");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Returns the path to the hints JSON file for the given project.
     *
     * @param projectName The project name used to derive the file name.
     *
     * @return The path to the hints JSON file, or null if the plugin workarea is unavailable.
     */
    private static Path hintsFile(String projectName) {
        try {
            String safe = projectName.replaceAll("[^a-zA-Z0-9._-]", "_");
            return Paths.get(LibertyDevPlugin.getWorkareaDir(HINTS_DIR), safe + ".json");
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Returns the set of valid hint keys currently present on disk for the given logs directory.
     * Delegates to {@link #scanDisk} to avoid a third independent directory walk.
     *
     * @param logsDir The logs directory to inspect.
     *
     * @return The set of valid hint keys on disk.
     */
    private static Set<String> currentKeys(Path logsDir) {
        return scanDisk(logsDir).keySet();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the stem of a log file name by stripping the final extension.
     *
     * @param fileName The log file name, e.g. {@code messages.log}.
     *
     * @return The stem, e.g. {@code messages}.
     */
    private static String stemOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * Returns the default group name for a log file stem by taking the portion before the first
     * underscore, or the full stem when no underscore is present.
     *
     * @param stem The log file stem, e.g. {@code messages_26.09.21_13.11.36.0}.
     *
     * @return The default group name, e.g. {@code messages}.
     */
    private static String defaultGroup(String stem) {
        int us = stem.indexOf('_');
        return us > 0 ? stem.substring(0, us) : stem;
    }
}
