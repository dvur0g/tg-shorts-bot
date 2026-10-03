package dev.shortsbot.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** A small thread-safe cache that evicts the least recently used entry once it is full. */
public class LruCache<V> {

    private final Map<String, V> entries;

    public LruCache(int capacity) {
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > capacity;
            }
        };
    }

    public synchronized Optional<V> get(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    public synchronized void put(String key, V value) {
        entries.put(key, value);
    }

    public synchronized void remove(String key) {
        entries.remove(key);
    }

    public synchronized int size() {
        return entries.size();
    }
}
