package com.dumcaptions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dumcaptions.captions.CaptionsManager;
import com.dumcaptions.translate.GroqClient;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import moe.kyokobot.libdave.NativeDaveFactory;
import moe.kyokobot.libdave.jda.LDJDADaveSessionFactory;

import java.io.File;
import java.io.IOException;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static class Config {
        public String discord_token;
        public String groq_api_key;
        public String stt_model;
        public boolean captions_enabled;
    }

    public static void main(String[] args) {
        String configPath = "config.json";
        if (args.length > 0) {
            configPath = args[0];
        }

        ObjectMapper mapper = new ObjectMapper();
        Config config;
        try {
            config = mapper.readValue(new File(configPath), Config.class);
        } catch (IOException e) {
            logger.error("Failed to load config from {}: {}", configPath, e.getMessage());
            return;
        }

        if (!config.captions_enabled) {
            logger.info("Captions are disabled in config. Exiting.");
            return;
        }

        try {
            // Initialize DAVE support
            NativeDaveFactory daveFactory = new NativeDaveFactory();
            LDJDADaveSessionFactory daveSessionFactory = new LDJDADaveSessionFactory(daveFactory);

            JDABuilder builder = JDABuilder.createDefault(config.discord_token)
                    .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_VOICE_STATES, GatewayIntent.GUILD_MEMBERS)
                    .setChunkingFilter(ChunkingFilter.ALL)
                    .setMemberCachePolicy(MemberCachePolicy.ALL)
                    .setStatus(OnlineStatus.ONLINE)
                    .setActivity(Activity.listening("Voices"))
                    .setAudioModuleConfig(new AudioModuleConfig()
                            .withDaveSessionFactory(daveSessionFactory));

            JDA jda = builder.build();
            jda.awaitReady();

            GroqClient groq = new GroqClient(config.groq_api_key, config.stt_model);
            CaptionsManager captionsManager = new CaptionsManager(jda, groq);
            
            jda.addEventListener(captionsManager);
            
            // Register /captions commands
            captionsManager.registerCommands();

            logger.info("DumCaptions is now running.");
            
            // Wait for shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down...");
                jda.shutdown();
            }));

        } catch (Exception e) {
            logger.error("Failed to start DumCaptions: {}", e.getMessage(), e);
        }
    }
}
