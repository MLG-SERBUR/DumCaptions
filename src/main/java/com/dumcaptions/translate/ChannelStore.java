package com.dumcaptions.translate;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ChannelStore {
    private static final Logger logger = LoggerFactory.getLogger(ChannelStore.class);

    private final String filePath;
    private final ObjectMapper mapper;
    private final ChannelSettings defaults;
    private final Map<String, ChannelSettings> channels = new ConcurrentHashMap<>();

    public ChannelStore(String filePath) {
        this(filePath, null, null);
    }

    public ChannelStore(String filePath, List<String> initialChannels, ChannelSettings defaults) {
        this.filePath = filePath;
        this.mapper = new ObjectMapper();
        this.defaults = normalizeDefaults(defaults);
        load();

        if (initialChannels != null) {
            for (String channelId : initialChannels) {
                if (channelId == null || channelId.isBlank()) continue;
                ChannelSettings cs = channels.computeIfAbsent(channelId, k -> defaultSettings());
                cs.enabled = true;
                if (cs.backend == null || cs.backend.isBlank()) {
                    cs.backend = this.defaults.backend;
                }
            }
            saveAsync();
        }
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
                        cs.backend = ps.backend != null && !ps.backend.isBlank() ? ps.backend : defaults.backend;
                        cs.interactionSelectEnabled = ps.interactionSelectEnabled != null ? ps.interactionSelectEnabled : defaults.interactionSelectEnabled;
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
        ChannelSettings cs = channels.computeIfAbsent(channelId, k -> defaultSettings());
        
        cs.enabled = true;
        if (backend != null) {
            cs.backend = backend;
        } else if (cs.backend == null || cs.backend.isBlank()) {
            cs.backend = defaults.backend;
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

    public static ChannelSettings defaults(String backend, Boolean interactionSelectEnabled) {
        ChannelSettings settings = new ChannelSettings();
        settings.enabled = false;
        if (backend != null && !backend.isBlank()) {
            settings.backend = backend;
        }
        if (interactionSelectEnabled != null) {
            settings.interactionSelectEnabled = interactionSelectEnabled;
        }
        return settings;
    }

    private ChannelSettings defaultSettings() {
        ChannelSettings settings = new ChannelSettings();
        settings.enabled = defaults.enabled;
        settings.backend = defaults.backend;
        settings.interactionSelectEnabled = defaults.interactionSelectEnabled;
        return settings;
    }

    private static ChannelSettings normalizeDefaults(ChannelSettings defaults) {
        ChannelSettings settings = defaults != null ? defaults : new ChannelSettings();
        if (settings.backend == null || settings.backend.isBlank()) {
            settings.backend = "TranslateAPI";
        }
        settings.enabled = false;
        return settings;
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
