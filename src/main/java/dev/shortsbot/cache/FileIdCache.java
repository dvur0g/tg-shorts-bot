package dev.shortsbot.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Remembers the Telegram file_id of videos already uploaded, keyed by {@code DetectedLink.canonicalId()},
 * so a link posted again is answered instantly without downloading. Least recently used entries are evicted.
 */
public class FileIdCache {

    public static final int DEFAULT_CAPACITY = 500;

    private final Map<String, String> entries;

    public FileIdCache() {
        this(DEFAULT_CAPACITY);
    }

    public FileIdCache(int capacity) {
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > capacity;
            }
        };
    }

    public synchronized Optional<String> get(String canonicalId) {
        return Optional.ofNullable(entries.get(canonicalId));
    }

    public synchronized void put(String canonicalId, String fileId) {
        entries.put(canonicalId, fileId);
    }

    public synchronized void remove(String canonicalId) {
        entries.remove(canonicalId);
    }

    public synchronized int size() {
        return entries.size();
    }
}
