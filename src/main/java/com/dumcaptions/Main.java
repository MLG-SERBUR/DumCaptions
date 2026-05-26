package com.dumcaptions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dumcaptions.captions.CaptionsManager;
import com.dumcaptions.translate.GroqClient;
import com.dumcaptions.translate.ChannelStore;
import com.dumcaptions.translate.TranslationManager;
import com.dumcaptions.translate.backends.*;
import com.dumcaptions.vad.VadMode;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import moe.kyokobot.libdave.NativeDaveFactory;
import moe.kyokobot.libdave.jda.LDJDADaveSessionFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static class Config {
        public String discord_token;
        public String groq_api_key;
        public String stt_model;
        public boolean captions_enabled;
        public String vad_mode;
        
        // Translation config
        public String translate_api_key;
        public String mymemory_email;
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

        try {
            // Initialize DAVE support
            NativeDaveFactory daveFactory = new NativeDaveFactory();
            LDJDADaveSessionFactory daveSessionFactory = new LDJDADaveSessionFactory(daveFactory);
            VadMode vadMode = VadMode.fromConfig(config.vad_mode);
            logger.info("Using VAD mode: {}", vadMode.configValue());

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

            // Initialize Translation
            ChannelStore channelStore = new ChannelStore("channels.json");
            Map<String, Translator> translators = new HashMap<>();
            translators.put("TranslateAPI", new TranslateAPI(config.translate_api_key));
            translators.put("MyMemory", new MyMemory(config.mymemory_email));
            translators.put("Google", new GoogleTranslate());
            List<String> backendOrder = Arrays.asList("TranslateAPI", "MyMemory", "Google");

            TranslationManager translationManager = new TranslationManager(jda, translators, backendOrder, channelStore);
            jda.addEventListener(translationManager);

            if (config.captions_enabled) {
                GroqClient groq = new GroqClient(config.groq_api_key, config.stt_model);
                CaptionsManager captionsManager = new CaptionsManager(jda, groq, vadMode);
                jda.addEventListener(captionsManager);
            } else {
                logger.info("Captions are disabled in config.");
            }

            // Register Commands (overwrite all)
            OptionData backendOption = new OptionData(OptionType.STRING, "backend", "Backend to use for this channel when translation is on", false);
            for (String backend : backendOrder) {
                backendOption.addChoice(translators.get(backend).getDisplayName(), backend);
            }
            
            OptionData enabledOption = new OptionData(OptionType.STRING, "enabled", "Turn translation on or off for this channel", false)
                    .addChoice("on", "on")
                    .addChoice("off", "off");
                    
            OptionData interactOption = new OptionData(OptionType.STRING, "interaction_selection", "Enable or disable the backend select dropdown", false)
                    .addChoice("on", "on")
                    .addChoice("off", "off");

            logger.info("Registering slash commands...");
            
            List<SlashCommandData> activeCommands = new ArrayList<>();
            activeCommands.add(
                Commands.slash("translate", "Manage translation settings for this channel")
                    .addOptions(enabledOption, backendOption, interactOption)
            );
            
            if (config.captions_enabled) {
                activeCommands.add(
                    Commands.slash("captions", "Manage real-time translated captions in voice channels")
                        .addSubcommands(
                            new SubcommandData("on", "Start captions in your current voice channel"),
                            new SubcommandData("off", "Stop captions and leave the voice channel")
                        )
                );
            }

            jda.updateCommands().addCommands(activeCommands).queue(
                success -> logger.info("Successfully registered commands"),
                error -> logger.error("Failed to register commands: {}", error.getMessage(), error)
            );

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
