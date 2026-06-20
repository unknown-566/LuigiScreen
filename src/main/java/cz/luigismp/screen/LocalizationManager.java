package cz.luigismp.screen;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class LocalizationManager {

    private static final String FALLBACK_LANGUAGE = "en";
    private static final Pattern LANGUAGE_CODE = Pattern.compile("[a-z0-9_-]{2,16}");

    private final LuigiScreenPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();
    private YamlConfiguration messages;
    private YamlConfiguration fallback;
    private String language;

    LocalizationManager(LuigiScreenPlugin plugin) {
        this.plugin = plugin;
    }

    void load() {
        saveBundledLanguage("en");
        saveBundledLanguage("cs");

        String configured = plugin.getConfig().getString("language", "cs");
        language = normalizeLanguage(configured);
        File selected = languageFile(language);
        if (!selected.isFile()) {
            plugin.getLogger().warning("Language file " + selected.getName()
                    + " was not found. Falling back to messages_en.yml.");
            language = FALLBACK_LANGUAGE;
            selected = languageFile(language);
        }
        fallback = loadBundledLanguage(
                language.equals("cs") ? "cs" : FALLBACK_LANGUAGE);
        messages = YamlConfiguration.loadConfiguration(selected);
    }

    String language() {
        return language;
    }

    Component component(String key, Map<String, ?> placeholders) {
        String template = raw(key);
        TagResolver.Builder resolver = TagResolver.builder();
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            resolver.resolver(Placeholder.unparsed(
                    entry.getKey(),
                    String.valueOf(entry.getValue())
            ));
        }
        return miniMessage.deserialize(template, resolver.build());
    }

    Component component(String key, Object... placeholders) {
        return component(key, placeholderMap(placeholders));
    }

    Component prefixed(String key, Object... placeholders) {
        return component("prefix").append(component(key, placeholders));
    }

    String plain(String key, Object... placeholders) {
        return plainText.serialize(component(key, placeholders));
    }

    String plainOr(String key, String fallbackValue) {
        if (messages.contains(key) || fallback.contains(key)) {
            return plain(key);
        }
        return fallbackValue;
    }

    void send(CommandSender sender, String key, Object... placeholders) {
        sender.sendMessage(prefixed(key, placeholders));
    }

    String state(String state) {
        String normalized = state == null ? "stopped"
                : state.toLowerCase(Locale.ROOT)
                .replace(" ", "_")
                .replace("(", "")
                .replace(")", "");
        return plain("states." + normalized);
    }

    String error(String error) {
        if (error == null || error.isBlank() || error.equalsIgnoreCase("none")) {
            return plain("errors.none");
        }
        String normalized = error.toLowerCase(Locale.ROOT).replace(" ", "_");
        String key = "errors." + normalized;
        if (messages.contains(key) || fallback.contains(key)) {
            return plain(key);
        }
        return error;
    }

    String direction(BlockFace face) {
        return plain("directions." + face.name().toLowerCase(Locale.ROOT));
    }

    String situation(MediaMtxSituation situation) {
        return plain("situations." + situation.commandName());
    }

    private String raw(String key) {
        String value = messages.getString(key);
        if (value == null) {
            value = fallback.getString(key);
        }
        if (value == null) {
            plugin.getLogger().warning("Missing language key: " + key);
            return "<red>Missing message: " + key + "</red>";
        }
        return value;
    }

    private void saveBundledLanguage(String code) {
        String resource = "messages_" + code + ".yml";
        File destination = new File(plugin.getDataFolder(), resource);
        if (!destination.isFile()) {
            plugin.saveResource(resource, false);
        }
    }

    private YamlConfiguration loadBundledLanguage(String code) {
        String resource = "messages_" + code + ".yml";
        try (var input = plugin.getResource(resource)) {
            if (input == null) {
                throw new IllegalStateException("Bundled language is missing: " + resource);
            }
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load bundled language: " + resource,
                    exception);
        }
    }

    private File languageFile(String code) {
        return new File(plugin.getDataFolder(), "messages_" + code + ".yml");
    }

    private static String normalizeLanguage(String value) {
        String normalized = value == null ? "cs" : value.trim().toLowerCase(Locale.ROOT);
        return LANGUAGE_CODE.matcher(normalized).matches() ? normalized : "cs";
    }

    private static Map<String, ?> placeholderMap(Object... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("Placeholders must be key/value pairs");
        }
        Map<String, Object> placeholders = new java.util.LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            placeholders.put(String.valueOf(values[index]), values[index + 1]);
        }
        return placeholders;
    }
}
