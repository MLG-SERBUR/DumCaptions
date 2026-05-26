package com.dumcaptions.translate;

import com.dumcaptions.translate.backends.Translator;
import com.dumcaptions.translate.backends.TranslateResponse;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Webhook;
import net.dv8tion.jda.api.entities.WebhookClient;
import net.dv8tion.jda.api.entities.channel.attribute.IWebhookContainer;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class TranslationManager extends ListenerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(TranslationManager.class);
    
    private final JDA jda;
    private final Map<String, Translator> translators;
    private final List<String> backendOrder;
    private final ChannelStore channelStore;
    private final Map<String, WebhookClient> webhookClients = new ConcurrentHashMap<>();
    
    private final ExecutorService translationExecutor = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger(0);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "translation-worker-" + count.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    public TranslationManager(JDA jda, Map<String, Translator> translators, List<String> backendOrder, ChannelStore channelStore) {
        this.jda = jda;
        this.translators = translators;
        this.backendOrder = backendOrder;
        this.channelStore = channelStore;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;
        
        String channelId = event.getChannel().getId();
        if (!channelStore.hasEnabled(channelId)) return;
        
        String content = event.getMessage().getContentRaw();
        if (content.isEmpty()) return;

        if (!LanguageDetector.isArabicOrKorean(content)) return;

        CompletableFuture.runAsync(() -> {
            String source = LanguageDetector.detectLanguage(content);
            ChannelStore.ChannelSettings settings = channelStore.get(channelId);
            String backendName = resolveBackend(settings != null ? settings.backend : null);
            
            Translator backend = translators.get(backendName);
            if (backend == null) return;

            try {
                TranslateResponse resp = backend.translate(content, source);
                if (resp == null || resp.getTranslatedText() == null || resp.getTranslatedText().strip().isEmpty()) {
                    logger.warn("Translation backend returned empty/null text for channel {}, skipping webhook execution", channelId);
                    return;
                }
                if (!"ar".equals(resp.getSourceLanguage()) && !"ko".equals(resp.getSourceLanguage())) {
                    logger.info("Translation API returned source language {}, skipping webhook", resp.getSourceLanguage());
                    return;
                }
                
                sendWebhookAsync(event.getMessage(), resp.getTranslatedText(), backendName, settings != null && settings.interactionSelectEnabled);
                
            } catch (Exception e) {
                logger.error("Translation error: {}", e.getMessage());
            }
        }, translationExecutor);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("translate")) return;

        OptionMapping enabledOpt = event.getOption("enabled");
        OptionMapping backendOpt = event.getOption("backend");
        OptionMapping interactOpt = event.getOption("interaction_selection");

        String channelId = event.getChannel().getId();

        if (enabledOpt == null) {
            if (backendOpt != null || interactOpt != null) {
                if (!channelStore.hasEnabled(channelId)) {
                    event.reply("Set `enabled` to `on` or `off` when changing translation settings for this channel.").queue();
                    return;
                }
                
                String newBackend = backendOpt != null ? backendOpt.getAsString() : null;
                Boolean newInteract = interactOpt != null ? interactOpt.getAsString().equals("on") : null;
                
                channelStore.update(channelId, newBackend, newInteract);
                ChannelStore.ChannelSettings updated = channelStore.get(channelId);
                event.reply(String.format("Translation settings updated for this channel.\nBackend: %s\nInteraction select dropdown: %s",
                        updated.backend, updated.interactionSelectEnabled ? "on" : "off")).queue();
            } else {
                ChannelStore.ChannelSettings settings = channelStore.get(channelId);
                if (settings == null || !settings.enabled) {
                    event.reply("Translation is off for this channel.").queue();
                } else {
                    event.reply(String.format("Translation is on for this channel.\nBackend: %s\nInteraction select dropdown: %s",
                            settings.backend, settings.interactionSelectEnabled ? "on" : "off")).queue();
                }
            }
            return;
        }

        String enabledStr = enabledOpt.getAsString();
        if ("on".equals(enabledStr)) {
            String newBackend = backendOpt != null ? backendOpt.getAsString() : null;
            Boolean newInteract = interactOpt != null ? interactOpt.getAsString().equals("on") : null;
            channelStore.enable(channelId, newBackend, newInteract);
            ChannelStore.ChannelSettings updated = channelStore.get(channelId);
            event.reply(String.format("Translation is on for this channel.\nBackend: %s\nInteraction select dropdown: %s",
                    updated.backend, updated.interactionSelectEnabled ? "on" : "off")).queue();
        } else if ("off".equals(enabledStr)) {
            channelStore.disable(channelId);
            event.reply("Translation is off for this channel.").queue();
        } else {
            event.reply("Invalid enabled value. Use `on` or `off`.").queue();
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (!event.getComponentId().startsWith("backend_select:")) return;
        
        event.deferEdit().queue();
        
        String[] parts = event.getComponentId().split(":");
        if (parts.length < 2) return;
        String originalMessageId = parts[1];
        
        String nextBackend = event.getValues().get(0);
        if (!translators.containsKey(nextBackend)) {
            event.getHook().editOriginal("Invalid translation backend selection.").queue();
            return;
        }
        
        event.getChannel().retrieveMessageById(originalMessageId).queue(originalMsg -> {
            CompletableFuture.runAsync(() -> {
                String originalContent = originalMsg.getContentRaw();
                String source = LanguageDetector.detectLanguage(originalContent);
                
                Translator backend = translators.get(nextBackend);
                try {
                    TranslateResponse resp = backend.translate(originalContent, source);
                    if (resp == null || resp.getTranslatedText() == null || resp.getTranslatedText().strip().isEmpty()) {
                        throw new Exception("Backend returned empty or null translation");
                    }
                    event.getHook().editOriginal(resp.getTranslatedText())
                        .setComponents(createBackendSelectMenu(originalMessageId, nextBackend))
                        .queue();
                } catch (Exception e) {
                    event.getHook().editOriginal("Translation failed with " + nextBackend + ": " + e.getMessage())
                        .setComponents(createBackendSelectMenu(originalMessageId, nextBackend))
                        .queue();
                }
            }, translationExecutor);
        }, err -> {
            event.getHook().editOriginal("Could not find original message to re-translate.").queue();
        });
    }

    private String resolveBackend(String backend) {
        if (backend != null && translators.containsKey(backend)) {
            return backend;
        }
        return channelStore.getDefaultBackend();
    }

    private ActionRow createBackendSelectMenu(String messageId, String activeBackend) {
        List<SelectOption> options = new ArrayList<>();
        for (String backendName : backendOrder) {
            Translator t = translators.get(backendName);
            if (t != null) {
                options.add(SelectOption.of(t.getDisplayName(), backendName)
                        .withDefault(backendName.equals(activeBackend)));
            }
        }
        
        StringSelectMenu menu = StringSelectMenu.create("backend_select:" + messageId)
                .setPlaceholder("Select Translation Backend")
                .addOptions(options)
                .build();
                
        return ActionRow.of(menu);
    }

    private void sendWebhookAsync(Message message, String content, String activeBackend, boolean interactionSelectEnabled) {
        if (!(message.getChannel() instanceof IWebhookContainer webhookContainer)) {
            logger.warn("Channel {} does not support webhooks", message.getChannel().getId());
            return;
        }

        if (message.getGuild() != null && !message.getGuild().getSelfMember().hasPermission(webhookContainer, Permission.MANAGE_WEBHOOKS)) {
            logger.warn("Missing MANAGE_WEBHOOKS permission in channel {}", webhookContainer.getId());
            return;
        }

        webhookContainer.retrieveWebhooks().queue(webhooks -> {
            Webhook targetWebhook = null;
            for (Webhook w : webhooks) {
                if ("DumTranslator".equals(w.getName())) {
                    targetWebhook = w;
                    break;
                }
            }

            if (targetWebhook == null) {
                webhookContainer.createWebhook("DumTranslator").queue(created -> {
                    executeWebhook(created, message, content, activeBackend, interactionSelectEnabled);
                }, err -> logger.error("Failed to create webhook: {}", err.getMessage()));
            } else {
                executeWebhook(targetWebhook, message, content, activeBackend, interactionSelectEnabled);
            }
        }, err -> logger.error("Failed to retrieve webhooks: {}", err.getMessage()));
    }

    private void executeWebhook(Webhook targetWebhook, Message message, String content, String activeBackend, boolean interactionSelectEnabled) {
        String displayName = message.getMember() != null && message.getMember().getNickname() != null 
            ? message.getMember().getNickname() 
            : message.getAuthor().getEffectiveName();

        WebhookClient client = webhookClients.computeIfAbsent(targetWebhook.getId(), k -> WebhookClient.createClient(jda, targetWebhook.getUrl()));
        
        var action = client.sendMessage(content)
                .setUsername(displayName)
                .setAvatarUrl(message.getAuthor().getEffectiveAvatarUrl());
                
        if (interactionSelectEnabled) {
            action.addComponents(createBackendSelectMenu(message.getId(), activeBackend));
        }
        
        action.queue(null, obj -> {
            Throwable err = (Throwable) obj;
            logger.error("Failed to send webhook: {}", err.getMessage());
            webhookClients.remove(targetWebhook.getId());
        });
    }
}
