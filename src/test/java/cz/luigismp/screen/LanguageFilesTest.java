package cz.luigismp.screen;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageFilesTest {

    @Test
    void bundledLanguagesContainTheSameMessageKeys() {
        YamlConfiguration english = load("messages_en.yml");
        YamlConfiguration czech = load("messages_cs.yml");

        Set<String> englishKeys = english.getKeys(true);
        Set<String> czechKeys = czech.getKeys(true);
        assertEquals(englishKeys, czechKeys);
        assertTrue(englishKeys.size() > 70);
    }

    @Test
    void bundledLanguagesContainRequiredCoreMessages() {
        for (String resource : new String[]{"messages_en.yml", "messages_cs.yml"}) {
            YamlConfiguration language = load(resource);
            assertNotNull(language.getString("prefix"));
            assertNotNull(language.getString("commands.reload-success"));
            assertNotNull(language.getString("commands.clone-success"));
            assertNotNull(language.getString("commands.set-success"));
            assertNotNull(language.getString("commands.source-success"));
            assertNotNull(language.getString("commands.source-types"));
            assertNotNull(language.getString("commands.no-permission"));
            assertNotNull(language.getString("status.summary"));
            assertNotNull(language.getString("debug.memory"));
            assertNotNull(language.getString("mediamtx.setup-header"));
            assertNotNull(language.getString("screen.offline"));
            assertNotNull(language.getString("updates.available"));
            assertNotNull(language.getString("updates.open-modrinth"));
            assertNotNull(language.getString("logs.update-available"));
            assertEquals("LuigiScreen", language.getString("screen.offline-title"));
        }
    }

    @Test
    void allMessagesUseValidMiniMessageFormatting() {
        MiniMessage miniMessage = MiniMessage.miniMessage();
        for (String resource : new String[]{"messages_en.yml", "messages_cs.yml"}) {
            YamlConfiguration language = load(resource);
            for (String key : language.getKeys(true)) {
                if (language.isString(key)) {
                    miniMessage.deserialize(language.getString(key, ""));
                }
            }
        }
    }

    @Test
    void escapedCommandArgumentIsDisplayedAsAngleBrackets() {
        YamlConfiguration english = load("messages_en.yml");
        var component = MiniMessage.miniMessage().deserialize(
                english.getString("commands.help-mediamtx", ""),
                Placeholder.unparsed("argument",
                        "<" + english.getString("commands.mediamtx-argument", "") + ">"));
        String plain = PlainTextComponentSerializer.plainText().serialize(component);
        assertTrue(plain.contains("<situation>"));
    }

    private static YamlConfiguration load(String resource) {
        var stream = LanguageFilesTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(stream);
        return YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
}
