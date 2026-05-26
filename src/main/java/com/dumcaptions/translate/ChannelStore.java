package com.dumcaptions.translate;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ChannelStore {
    private static final Logger logger = LoggerFactory.getLogger(ChannelStore.class);

    private final String filePath;
    private final ObjectMapper mapper;
    private final Map<String, ChannelSettings> channels = new ConcurrentHashMap<>();

    public ChannelStore(String filePath) {
        this.filePath = filePath;
        this.mapper = new ObjectMapper();
        load();
    }

    private void load() {
        File file = new File(filePath);
        if (file.exists()) {
            try {
                PersistedStore store = mapper.readValue(file, PersistedStore.class);
                if (store != null && store.channels != null) {
                    for (Map.Entry<String, PersistedSettings> entry : store.channels.entrySet()) {
                        PersistedSettings ps = entry.getValue();
                        ChannelSettings cs = new ChannelSettings();
                        cs.enabled = ps.enabled != null ? ps.enabled : true;
                        cs.backend = ps.backend != null ? ps.backend : "TranslateAPI";
                        cs.interactionSelectEnabled = ps.interactionSelectEnabled != null ? ps.interactionSelectEnabled : true;
                        channels.put(entry.getKey(), cs);
                    }
                }
            } catch (IOException e) {
                logger.error("Failed to load channel store: {}", e.getMessage());
            }
        }
    }

    private synchronized void saveSync() {
        PersistedStore store = new PersistedStore();
        for (Map.Entry<String, ChannelSettings> entry : channels.entrySet()) {
            ChannelSettings cs = entry.getValue();
            PersistedSettings ps = new PersistedSettings();
            ps.enabled = cs.enabled;
            ps.backend = cs.backend;
            ps.interactionSelectEnabled = cs.interactionSelectEnabled;
            store.channels.put(entry.getKey(), ps);
        }

        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(filePath), store);
        } catch (IOException e) {
            logger.error("Failed to save channel store: {}", e.getMessage());
        }
    }

    private void saveAsync() {
        CompletableFuture.runAsync(this::saveSync);
    }

    public ChannelSettings get(String channelId) {
        return channels.get(channelId);
    }

    public boolean hasEnabled(String channelId) {
        ChannelSettings cs = channels.get(channelId);
        return cs != null && cs.enabled;
    }

    public void enable(String channelId, String backend, Boolean interactionSelectEnabled) {
        ChannelSettings cs = channels.computeIfAbsent(channelId, k -> new ChannelSettings());
        
        cs.enabled = true;
        if (backend != null) {
            cs.backend = backend;
        }
        
        if (interactionSelectEnabled != null) {
            cs.interactionSelectEnabled = interactionSelectEnabled;
        }
        
        saveAsync();
    }

    public void disable(String channelId) {
        ChannelSettings cs = channels.get(channelId);
        if (cs != null) {
            cs.enabled = false;
            saveAsync();
        }
    }

    public void update(String channelId, String backend, Boolean interactionSelectEnabled) {
        ChannelSettings cs = channels.get(channelId);
        if (cs != null) {
            if (backend != null) {
                cs.backend = backend;
            }
            if (interactionSelectEnabled != null) {
                cs.interactionSelectEnabled = interactionSelectEnabled;
            }
            saveAsync();
        }
    }

    public static class ChannelSettings {
        public boolean enabled = true;
        public String backend = "TranslateAPI";
        public boolean interactionSelectEnabled = true;
    }

    private static class PersistedStore {
        @JsonProperty("channels")
        public Map<String, PersistedSettings> channels = new ConcurrentHashMap<>();
    }

    private static class PersistedSettings {
        @JsonProperty("enabled")
        public Boolean enabled;
        @JsonProperty("backend")
        public String backend;
        @JsonProperty("interaction_select_enabled")
        public Boolean interactionSelectEnabled;
    }
}
