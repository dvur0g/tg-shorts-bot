package dev.shortsbot.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileIdCacheTest {

    @Test
    void storesAndRemovesEntries() {
        var cache = new FileIdCache();
        cache.put("youtube:a", "file-a");

        assertThat(cache.get("youtube:a")).contains("file-a");
        assertThat(cache.get("youtube:b")).isEmpty();

        cache.remove("youtube:a");
        assertThat(cache.get("youtube:a")).isEmpty();
    }

    @Test
    void evictsLeastRecentlyUsedEntry() {
        var cache = new FileIdCache(2);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.get("a");
        cache.put("c", "3");

        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.get("a")).contains("1");
        assertThat(cache.get("b")).isEmpty();
        assertThat(cache.get("c")).contains("3");
    }
}
