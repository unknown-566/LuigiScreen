package cz.luigismp.screen;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class StudioMenuManager implements Listener {

    private static final int PAGE_SIZE = 45;
    private static final DecimalFormat ONE_DECIMAL = new DecimalFormat("0.0");
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());
    private final LuigiScreenPlugin plugin;
    private final StudioService studio;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Prompt> prompts = new HashMap<>();
    private BukkitTask refreshTask;

    StudioMenuManager(LuigiScreenPlugin plugin, StudioService studio) {
        this.plugin = plugin;
        this.studio = studio;
        refreshTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::refreshOpenMenus, 20L, 20L);
    }

    void shutdown() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        sessions.clear();
        prompts.clear();
    }

    void openDashboard(Player player) {
        if (!allowed(player, "dashboard")) {
            deny(player, "luigiscreen.menu.dashboard");
            return;
        }
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        open(player, View.DASHBOARD, "", 0, session.selectedScreen);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof StudioHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        handle(player, holder, event.getRawSlot(), event.isLeftClick(),
                event.isRightClick(), event.isShiftClick(),
                event.getClick() == ClickType.MIDDLE);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof StudioHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
        prompts.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Prompt prompt;
        synchronized (prompts) {
            prompt = prompts.remove(event.getPlayer().getUniqueId());
        }
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        String answer = PlainTextComponentSerializer.plainText()
                .serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin,
                () -> completePrompt(event.getPlayer(), prompt, answer));
    }

    private void open(Player player, View view, String subject, int page, String selected) {
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        if (selected != null && !selected.isBlank()) {
            session.selectedScreen = selected;
        }
        if ((session.selectedScreen == null || !plugin.hasScreen(session.selectedScreen))
                && !plugin.screenIds().isEmpty()) {
            session.selectedScreen = plugin.screenIds().getFirst();
        }
        StudioHolder holder = new StudioHolder(view, subject, Math.max(0, page),
                session.selectedScreen);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text(title(view, subject), NamedTextColor.DARK_GREEN));
        holder.inventory = inventory;
        render(player, holder);
        player.openInventory(inventory);
    }

    private void render(Player player, StudioHolder holder) {
        Inventory inventory = holder.inventory;
        inventory.clear();
        decorate(inventory);
        switch (holder.view) {
            case DASHBOARD -> dashboard(player, holder);
            case SCREENS -> screens(player, holder);
            case SCREEN -> screenDetail(player, holder);
            case MEDIA -> media(player, holder);
            case PLAYLISTS -> playlists(player, holder);
            case PLAYLIST -> playlist(player, holder);
            case EVENTS -> events(player, holder);
            case EVENT -> event(player, holder);
            case LIVE -> live(player, holder);
            case QUEUE -> queue(player, holder);
            case GROUPS -> groups(player, holder);
            case GROUP -> group(player, holder);
            case SCHEDULES -> schedules(player, holder);
            case TEMPLATES -> templates(player, holder);
            case DIAGNOSTICS -> diagnostics(player, holder);
            case HISTORY -> history(player, holder);
            case EMERGENCY -> emergency(player, holder);
        }
    }

    private void dashboard(Player player, StudioHolder holder) {
        long enabled = plugin.screenIds().stream()
                .map(plugin::screenDefinition).filter(ScreenDefinition::enabled).count();
        long problems = plugin.screenIds().stream().map(plugin::screenHealth)
                .filter(health -> health != null && (!"none".equals(health.error())
                        || "waiting_for_stream".equals(health.state()))).count();
        int viewers = plugin.screenIds().stream().mapToInt(plugin::screenViewerCount).sum();
        long events = plugin.screenIds().stream().map(plugin::playbackSnapshot)
                .filter(snapshot -> snapshot.mode().equals("event")).count();
        holder.inventory.setItem(4, item(Material.RECOVERY_COMPASS,
                t("studio.dashboard-title"), NamedTextColor.GREEN,
                line("Screens", enabled + "/" + plugin.screenCount()),
                line("Problems", problems),
                line("Active events", events),
                line("Viewers", viewers),
                line("Media", studio.media().size())));
        section(player, holder.inventory, 10, "screens", Material.GLOW_ITEM_FRAME,
                t("studio.screens"), plugin.screenCount() + " configured");
        section(player, holder.inventory, 12, "media", Material.CHEST,
                t("studio.media"), studio.media().size() + " indexed files");
        section(player, holder.inventory, 14, "playlists", Material.JUKEBOX,
                t("studio.playlists"), plugin.playlistIds().size() + " rotations");
        section(player, holder.inventory, 16, "events", Material.REDSTONE_TORCH,
                t("studio.events"), plugin.eventIds().size() + " timelines");
        section(player, holder.inventory, 28, "groups", Material.ENDER_CHEST,
                t("studio.groups"), studio.groupIds().size() + " groups");
        section(player, holder.inventory, 30, "live", Material.ENDER_EYE,
                t("studio.live"), "Selected: " + selected(holder));
        section(player, holder.inventory, 32, "schedules", Material.CLOCK,
                t("studio.schedules"), studio.schedules().size() + " schedules");
        section(player, holder.inventory, 34, "diagnostics", Material.COMPARATOR,
                t("studio.diagnostics"), problems == 0 ? "No active warnings" : problems + " warnings");
        section(player, holder.inventory, 36, "templates", Material.KNOWLEDGE_BOOK,
                t("studio.templates"), studio.templateIds().size() + " templates");
        section(player, holder.inventory, 38, "history", Material.WRITABLE_BOOK,
                t("studio.history"), studio.audit().size() + " recorded changes");
        holder.inventory.setItem(49, item(studio.emergency()
                        ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                studio.emergency() ? t("studio.emergency-disable") : t("studio.emergency"),
                studio.emergency() ? NamedTextColor.GREEN : NamedTextColor.RED,
                text("Requires confirmation.")));
        List<String> favorites = studio.favorites(player);
        int[] favoriteSlots = {46, 47, 48, 50, 51, 52};
        for (int index = 0; index < Math.min(favoriteSlots.length, favorites.size()); index++) {
            holder.inventory.setItem(favoriteSlots[index], item(Material.NETHER_STAR,
                    favorites.get(index), NamedTextColor.GOLD,
                    text("Pinned action"), text("Shift-click sections to pin or unpin.")));
        }
    }

    private void screens(Player player, StudioHolder holder) {
        List<String> ids = plugin.screenIds();
        page(holder, ids.size());
        int start = holder.page * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && start + slot < ids.size(); slot++) {
            String id = ids.get(start + slot);
            ScreenDefinition definition = plugin.screenDefinition(id);
            ScreenHealth health = plugin.screenHealth(id);
            PlaybackSnapshot playback = plugin.playbackSnapshot(id);
            Material icon = screenMaterial(definition, health, playback);
            holder.inventory.setItem(slot, item(icon, id,
                    definition.enabled() ? NamedTextColor.GREEN : NamedTextColor.GRAY,
                    line("State", health == null ? "unknown" : health.state()),
                    line("Playing", playback.current()),
                    line("Next", playback.next()),
                    line("Controller", playback.controller()),
                    line("Remaining", duration(playback.remainingMillis())),
                    line("Viewers", health == null ? 0 : health.viewers()),
                    line("FPS", health == null ? "0/0"
                            : ONE_DECIMAL.format(health.effectiveFps()) + "/"
                            + ONE_DECIMAL.format(health.targetFps())),
                    text("Left: open | Right: start/stop"),
                    text("Shift-left: select for Live Control")));
        }
        nav(holder, View.DASHBOARD);
    }

    private void screenDetail(Player player, StudioHolder holder) {
        ScreenDefinition definition = plugin.screenDefinition(holder.subject);
        ScreenHealth health = plugin.screenHealth(holder.subject);
        if (definition == null || health == null) {
            holder.inventory.setItem(22, error("Screen no longer exists."));
            nav(holder, View.SCREENS);
            return;
        }
        PlaybackSnapshot playback = plugin.playbackSnapshot(holder.subject);
        holder.inventory.setItem(4, item(screenMaterial(definition, health, playback),
                definition.id(), NamedTextColor.GREEN,
                line("Mode", playback.mode()), line("Controller", playback.controller()),
                line("Playing", playback.current()), line("Next", playback.next()),
                line("Remaining", duration(playback.remainingMillis()))));
        holder.inventory.setItem(10, item(definition.enabled()
                        ? Material.LIME_DYE : Material.GRAY_DYE,
                definition.enabled() ? t("studio.stop") : t("studio.start"),
                definition.enabled() ? NamedTextColor.YELLOW : NamedTextColor.GREEN));
        holder.inventory.setItem(12, item(playback.paused()
                        ? Material.LIME_CONCRETE : Material.YELLOW_CONCRETE,
                playback.paused() ? t("studio.resume") : t("studio.hold"),
                NamedTextColor.YELLOW, line("Paused", playback.paused())));
        holder.inventory.setItem(13, item(Material.SPECTRAL_ARROW,
                t("studio.skip"), NamedTextColor.AQUA));
        holder.inventory.setItem(14, item(playback.repeat()
                        ? Material.LIME_CONCRETE : Material.REPEATER,
                t("studio.repeat"), NamedTextColor.AQUA,
                line("Enabled", playback.repeat())));
        holder.inventory.setItem(16, item(Material.COMPASS,
                t("studio.return"), NamedTextColor.GREEN,
                text("Clear event, queue, hold and repeat.")));
        holder.inventory.setItem(20, item(Material.SPYGLASS,
                t("studio.why-playing"), NamedTextColor.GOLD,
                wrap(playback.reason(), NamedTextColor.GRAY)));
        List<Component> queueLore = new ArrayList<>();
        queueLore.add(line("Next", playback.next()));
        playback.queue().stream().limit(8).forEach(value ->
                queueLore.add(text("- " + value)));
        holder.inventory.setItem(22, item(Material.HOPPER,
                t("studio.queue"), NamedTextColor.AQUA,
                queueLore.toArray(Component[]::new)));
        holder.inventory.setItem(24, item(Material.REDSTONE_TORCH,
                t("studio.automation"), NamedTextColor.YELLOW,
                line("Playlist", empty(definition.playlist())),
                line("Event", playback.mode().equals("event")
                        ? playback.controller() : "none")));
        holder.inventory.setItem(28, item(Material.ENDER_PEARL,
                t("studio.teleport"), NamedTextColor.AQUA,
                line("World", definition.world()),
                line("Facing", definition.facing())));
        holder.inventory.setItem(30, item(Material.GLOW_INK_SAC,
                t("studio.highlight"), NamedTextColor.LIGHT_PURPLE));
        holder.inventory.setItem(32, item(Material.ANVIL,
                t("studio.repair"), NamedTextColor.YELLOW,
                text("Respawn virtual frames and resend all maps.")));
        holder.inventory.setItem(34, healthItem(health));
        StudioStatistics stats = studio.screenStatistics(definition.id());
        holder.inventory.setItem(36, item(Material.BOOK,
                t("studio.statistics"), NamedTextColor.GOLD,
                line("Plays", stats.plays()),
                line("Planned time", duration(stats.plannedSeconds() * 1000)),
                line("Viewer time", duration(stats.viewerSeconds() * 1000)),
                line("Average viewers", ONE_DECIMAL.format(stats.averageViewers())),
                line("Skips", stats.skips()),
                line("Failures", stats.failures())));
        holder.inventory.setItem(40, item(definition.permissionRequired()
                        ? Material.TRIPWIRE_HOOK : Material.PAPER,
                t("studio.visibility"), NamedTextColor.GOLD,
                line("Permission required", definition.permissionRequired()),
                line("Node", ScreenPermissions.viewNode(definition.id()))));
        nav(holder, View.SCREENS);
    }

    private void media(Player player, StudioHolder holder) {
        List<MediaEntry> entries = studio.media();
        page(holder, entries.size());
        int start = holder.page * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && start + slot < entries.size(); slot++) {
            MediaEntry entry = entries.get(start + slot);
            StudioStatistics stats = studio.mediaStatistics(entry.id());
            ItemStack thumbnail = studio.thumbnailItem(entry);
            holder.inventory.setItem(slot, item(thumbnail == null
                            ? new ItemStack(mediaMaterial(entry)) : thumbnail,
                    entry.id(),
                    entry.valid() ? NamedTextColor.GREEN : NamedTextColor.RED,
                    line("Type", entry.type() == null ? "unsupported" : entry.type().id()),
                    line("Size", bytes(entry.sizeBytes())),
                    line("Resolution", entry.width() > 0
                            ? entry.width() + "x" + entry.height() : "read on playback"),
                    line("Modified", FILE_TIME.format(Instant.ofEpochMilli(entry.modifiedMillis()))),
                    line("References", entry.references().size()),
                    line("Plays / planned", stats.plays() + " / "
                            + duration(stats.plannedSeconds() * 1000)),
                    line("Validation", entry.valid() ? "OK" : entry.problem()),
                    text("Left: play now | Right: queue next"),
                    text("Selected screen: " + selected(holder))));
        }
        holder.inventory.setItem(48, item(Material.SUNFLOWER,
                t("studio.rescan"), NamedTextColor.YELLOW));
        nav(holder, View.DASHBOARD);
    }

    private void playlists(Player player, StudioHolder holder) {
        List<String> ids = plugin.playlistIds();
        page(holder, ids.size());
        int start = holder.page * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && start + slot < ids.size(); slot++) {
            String id = ids.get(start + slot);
            List<PlaybackItemView> items = plugin.playlistItems(id);
            holder.inventory.setItem(slot, item(Material.JUKEBOX, id,
                    NamedTextColor.GREEN, line("Items", items.size()),
                    text("Left: assign to " + selected(holder)),
                    text("Right: inspect and simulate")));
        }
        holder.inventory.setItem(48, item(Material.WRITABLE_BOOK,
                t("studio.create-playlist"), NamedTextColor.GREEN,
                text("Creates an editable starter playlist without YAML.")));
        nav(holder, View.DASHBOARD);
    }

    private void playlist(Player player, StudioHolder holder) {
        List<PlaybackItemView> items = plugin.playlistItems(holder.subject);
        int start = holder.page * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && start + slot < items.size(); slot++) {
            PlaybackItemView view = items.get(start + slot);
            String draftBase = "playlists." + holder.subject + ".items." + view.id();
            boolean draftEnabled = (boolean) studio.draftValue(
                    player, draftBase + ".enabled", view.enabled());
            int draftWeight = ((Number) studio.draftValue(
                    player, draftBase + ".weight", view.weight())).intValue();
            holder.inventory.setItem(slot, item(sourceMaterial(view.type()), view.id(),
                    draftEnabled ? NamedTextColor.GREEN : NamedTextColor.GRAY,
                    line("Type", view.type()), line("Value", view.value()),
                    line("Weight", draftWeight),
                    line("Estimated chance", ONE_DECIMAL.format(view.probability()) + "%"),
                    line("Duration", duration(view.durationMillis())),
                    line("Cooldown", duration(view.cooldownMillis())),
                    line("Conditions", view.conditions()),
                    line("Eligibility", plugin.explainPlaylistItem(
                            selected(holder), holder.subject, view.id())),
                    line("Draft enabled", draftEnabled),
                    text("Left: preview | Right: toggle enabled"),
                    text("Shift-left: increase draft weight"),
                    text("Middle-click: Condition Builder")));
        }
        Map<String, Integer> simulation = plugin.simulatePlaylist(holder.subject, 1000);
        List<Component> simulated = new ArrayList<>();
        simulated.add(text("1,000 weighted selections:"));
        simulation.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(8).forEach(entry -> simulated.add(
                        text(entry.getKey() + ": " + ONE_DECIMAL.format(entry.getValue() / 10.0) + "%")));
        holder.inventory.setItem(47, item(Material.BARRIER,
                t("studio.discard-draft"), NamedTextColor.RED,
                line("Pending changes", studio.draftSize(player))));
        holder.inventory.setItem(48, item(Material.LIME_CONCRETE,
                t("studio.publish"), NamedTextColor.GREEN,
                line("Pending changes", studio.draftSize(player)),
                text("A config snapshot is created before publishing.")));
        holder.inventory.setItem(49, item(Material.ENDER_EYE,
                t("studio.simulate"), NamedTextColor.LIGHT_PURPLE,
                simulated.toArray(Component[]::new)));
        nav(holder, View.PLAYLISTS);
    }

    private void events(Player player, StudioHolder holder) {
        List<String> ids = plugin.eventIds();
        int start = holder.page * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && start + slot < ids.size(); slot++) {
            String id = ids.get(start + slot);
            holder.inventory.setItem(slot, item(Material.REDSTONE_TORCH, id,
                    NamedTextColor.GOLD, line("Timeline steps", plugin.eventItems(id).size()),
                    text("Left: play on " + selected(holder)),
                    text("Right: inspect timeline")));
        }
        holder.inventory.setItem(48, item(Material.WRITABLE_BOOK,
                t("studio.create-event"), NamedTextColor.GREEN,
                text("Creates an editable starter timeline without YAML.")));
        nav(holder, View.DASHBOARD);
    }

    private void event(Player player, StudioHolder holder) {
        List<PlaybackItemView> items = plugin.eventItems(holder.subject);
        for (int slot = 0; slot < Math.min(PAGE_SIZE, items.size()); slot++) {
            PlaybackItemView view = items.get(slot);
            String base = "events." + holder.subject + ".sequence." + view.id();
            boolean draftEnabled = (boolean) studio.draftValue(
                    player, base + ".enabled", view.enabled());
            holder.inventory.setItem(slot, item(sourceMaterial(view.type()),
                    (slot + 1) + ". " + view.id(),
                    draftEnabled ? NamedTextColor.GOLD : NamedTextColor.GRAY,
                    line("Type", view.type()), line("Value", view.value()),
                    line("Duration", duration(view.durationMillis())),
                    line("Conditions", view.conditions()),
                    line("Draft enabled", draftEnabled),
                    text("Right: enable/disable this step"),
                    text("Shift-left: add 5 seconds to duration")));
        }
        holder.inventory.setItem(47, item(Material.BARRIER,
                t("studio.discard-draft"), NamedTextColor.RED,
                line("Pending changes", studio.draftSize(player))));
        holder.inventory.setItem(48, item(Material.LIME_CONCRETE,
                t("studio.publish"), NamedTextColor.GREEN,
                line("Pending changes", studio.draftSize(player))));
        holder.inventory.setItem(49, item(Material.LIME_CONCRETE,
                t("studio.take-live"), NamedTextColor.GREEN,
                text("Start this event on " + selected(holder))));
        nav(holder, View.EVENTS);
    }

    private void live(Player player, StudioHolder holder) {
        String screen = selected(holder);
        PlaybackSnapshot playback = plugin.playbackSnapshot(screen);
        holder.inventory.setItem(4, item(Material.ENDER_EYE,
                t("studio.live"), NamedTextColor.RED,
                line("Screen", screen), line("On air", playback.current()),
                line("Cued", playback.next()), line("Controller", playback.controller())));
        holder.inventory.setItem(10, item(Material.SPYGLASS,
                t("studio.preview"), NamedTextColor.AQUA,
                text("Choose media in Media Library, then queue it.")));
        holder.inventory.setItem(12, item(Material.LIME_CONCRETE,
                t("studio.take-live"), NamedTextColor.GREEN,
                text("Play the first queued source now.")));
        holder.inventory.setItem(14, item(Material.SPECTRAL_ARROW,
                t("studio.next"), NamedTextColor.AQUA));
        holder.inventory.setItem(16, item(playback.paused()
                        ? Material.LIME_DYE : Material.YELLOW_DYE,
                playback.paused() ? t("studio.resume") : t("studio.hold"),
                NamedTextColor.YELLOW));
        holder.inventory.setItem(28, item(Material.COMPASS,
                t("studio.return"), NamedTextColor.GREEN));
        VoteStatus vote = studio.voteStatus(screen);
        List<Component> voteLore = new ArrayList<>();
        if (vote == null) {
            voteLore.add(text("Starts a 60 second vote with up to three indexed media files."));
        } else {
            voteLore.add(line("Votes", vote.votes()));
            voteLore.add(line("Remaining", duration(vote.remainingMillis())));
            vote.results().forEach((option, count) ->
                    voteLore.add(text(studio.voteMediaName(option) + ": " + count)));
        }
        holder.inventory.setItem(20, item(vote == null
                        ? Material.BOOK : Material.ENCHANTED_BOOK,
                vote == null ? t("studio.start-vote") : t("studio.finish-vote"),
                NamedTextColor.LIGHT_PURPLE, voteLore.toArray(Component[]::new)));
        holder.inventory.setItem(30, item(Material.REDSTONE_TORCH,
                t("studio.events"), NamedTextColor.GOLD));
        holder.inventory.setItem(32, item(Material.CHEST,
                t("studio.media"), NamedTextColor.AQUA));
        holder.inventory.setItem(34, item(Material.BARRIER,
                t("studio.end-event"), NamedTextColor.RED));
        holder.inventory.setItem(49, item(Material.RED_CONCRETE,
                t("studio.emergency"), NamedTextColor.RED,
                text("Opens a separate confirmation screen.")));
        nav(holder, View.DASHBOARD);
    }

    private void queue(Player player, StudioHolder holder) {
        PlaybackSnapshot playback = plugin.playbackSnapshot(holder.subject);
        List<String> values = playback.queue();
        for (int slot = 0; slot < Math.min(PAGE_SIZE, values.size()); slot++) {
            holder.inventory.setItem(slot, item(Material.HOPPER,
                    (slot + 1) + ". " + values.get(slot), NamedTextColor.AQUA,
                    text("Left: play now"),
                    text("Right: remove"),
                    text("Shift-left: move one position up")));
        }
        holder.inventory.setItem(49, item(Material.BARRIER,
                t("studio.clear-queue"), NamedTextColor.RED,
                line("Items", values.size())));
        nav(holder, View.SCREEN);
    }

    private void groups(Player player, StudioHolder holder) {
        List<String> ids = studio.groupIds();
        for (int slot = 0; slot < Math.min(PAGE_SIZE, ids.size()); slot++) {
            String id = ids.get(slot);
            holder.inventory.setItem(slot, item(Material.ENDER_CHEST, id,
                    NamedTextColor.AQUA, line("Screens", studio.groupScreens(id).size()),
                    text(String.join(", ", studio.groupScreens(id)))));
        }
        holder.inventory.setItem(48, item(Material.WRITABLE_BOOK,
                t("studio.create-group"), NamedTextColor.GREEN,
                text("You will answer with a name and screen list in chat.")));
        nav(holder, View.DASHBOARD);
    }

    private void group(Player player, StudioHolder holder) {
        List<String> screens = studio.groupScreens(holder.subject);
        holder.inventory.setItem(4, item(Material.ENDER_CHEST, holder.subject,
                NamedTextColor.AQUA, line("Screens", screens.size()),
                text(String.join(", ", screens))));
        holder.inventory.setItem(11, item(Material.LIME_CONCRETE,
                t("studio.start-all"), NamedTextColor.GREEN));
        holder.inventory.setItem(13, item(Material.RED_CONCRETE,
                t("studio.stop-all"), NamedTextColor.RED));
        holder.inventory.setItem(15, item(Material.COMPASS,
                t("studio.return-all"), NamedTextColor.YELLOW));
        nav(holder, View.GROUPS);
    }

    private void schedules(Player player, StudioHolder holder) {
        List<StudioSchedule> schedules = studio.schedules();
        for (int slot = 0; slot < Math.min(PAGE_SIZE, schedules.size()); slot++) {
            StudioSchedule schedule = schedules.get(slot);
            List<String> conflicts = studio.scheduleConflicts(schedule.id());
            holder.inventory.setItem(slot, item(schedule.enabled()
                            ? conflicts.isEmpty() ? Material.CLOCK : Material.REDSTONE
                            : Material.GRAY_DYE,
                    schedule.id(), !conflicts.isEmpty() ? NamedTextColor.RED
                            : schedule.enabled() ? NamedTextColor.GREEN : NamedTextColor.GRAY,
                    line("Time", schedule.time()), line("Days", schedule.days()),
                    line("Target", schedule.target()), line("Action", schedule.action()),
                    line("Value", empty(schedule.value())),
                    line("Conflict policy", schedule.conflict()),
                    line("Conflicts", conflicts.isEmpty() ? "none"
                            : String.join(", ", conflicts))));
        }
        holder.inventory.setItem(48, item(Material.WRITABLE_BOOK,
                t("studio.create-schedule"), NamedTextColor.GREEN,
                text("You will enter time, target and action in chat.")));
        holder.inventory.setItem(50, item(Material.KNOWLEDGE_BOOK,
                t("studio.schedule-help"), NamedTextColor.YELLOW,
                text("Schedules are stored in studio.yml and reload live."),
                text("The GUI validates and displays every configured entry.")));
        nav(holder, View.DASHBOARD);
    }

    private void templates(Player player, StudioHolder holder) {
        List<String> ids = studio.templateIds();
        for (int slot = 0; slot < Math.min(PAGE_SIZE, ids.size()); slot++) {
            String id = ids.get(slot);
            holder.inventory.setItem(slot, item(Material.KNOWLEDGE_BOOK, id,
                    NamedTextColor.LIGHT_PURPLE,
                    text(studio.templateDescription(id)),
                    text("Click to install a safe editable example.")));
        }
        nav(holder, View.DASHBOARD);
    }

    private void diagnostics(Player player, StudioHolder holder) {
        List<String> ids = plugin.screenIds();
        for (int slot = 0; slot < Math.min(PAGE_SIZE, ids.size()); slot++) {
            ScreenHealth health = plugin.screenHealth(ids.get(slot));
            holder.inventory.setItem(slot, health == null ? error(ids.get(slot)) : healthItem(health,
                    ids.get(slot)));
        }
        holder.inventory.setItem(49, item(Material.COMPARATOR,
                t("studio.debug-overlay"), NamedTextColor.AQUA,
                text("Toggle the detailed boss bar and sidebar.")));
        nav(holder, View.DASHBOARD);
    }

    private void history(Player player, StudioHolder holder) {
        List<String> entries = studio.audit();
        for (int slot = 0; slot < Math.min(PAGE_SIZE, entries.size()); slot++) {
            holder.inventory.setItem(slot, item(Material.PAPER,
                    "Change " + (slot + 1), NamedTextColor.YELLOW,
                    wrap(entries.get(slot), NamedTextColor.GRAY)));
        }
        holder.inventory.setItem(48, item(Material.ENCHANTED_BOOK,
                t("studio.undo"), NamedTextColor.GOLD,
                text("Restore the newest config snapshot."),
                text("The snapshot is consumed after a successful restore.")));
        nav(holder, View.DASHBOARD);
    }

    private void emergency(Player player, StudioHolder holder) {
        holder.inventory.setItem(13, item(Material.BARRIER,
                studio.emergency() ? t("studio.confirm-disable") : t("studio.confirm-emergency"),
                NamedTextColor.RED,
                text("This affects every configured screen."),
                text("Events and automation are paused."),
                text("Click once more to confirm.")));
        holder.inventory.setItem(31, item(Material.LIME_CONCRETE,
                t("studio.cancel"), NamedTextColor.GREEN));
    }

    private void handle(Player player, StudioHolder holder, int slot,
                        boolean left, boolean right, boolean shift, boolean middle) {
        if (slot == 45 && holder.view != View.DASHBOARD) {
            String parentSubject = holder.view == View.QUEUE ? holder.subject : "";
            open(player, parent(holder.view), parentSubject, 0, holder.selectedScreen);
            return;
        }
        if (slot == 51 && holder.view != View.DASHBOARD) {
            openDashboard(player);
            return;
        }
        if (slot == 53 && holder.pageHasNext) {
            open(player, holder.view, holder.subject, holder.page + 1, holder.selectedScreen);
            return;
        }
        if (slot == 46 && holder.page > 0) {
            open(player, holder.view, holder.subject, holder.page - 1, holder.selectedScreen);
            return;
        }
        switch (holder.view) {
            case DASHBOARD -> dashboardClick(player, slot, shift);
            case SCREENS -> screensClick(player, holder, slot, right, shift);
            case SCREEN -> screenClick(player, holder, slot);
            case MEDIA -> mediaClick(player, holder, slot, right);
            case PLAYLISTS -> playlistsClick(player, holder, slot, right);
            case PLAYLIST -> playlistClick(player, holder, slot, right, shift, middle);
            case EVENTS -> eventsClick(player, holder, slot, right);
            case EVENT -> {
                if (slot == 47) {
                    studio.discardDraft(player);
                    open(player, View.EVENT, holder.subject, holder.page, selected(holder));
                } else if (slot == 48) {
                    boolean published = studio.publishDraft(player);
                    player.sendMessage(Component.text(published
                                    ? "Draft published." : "There are no draft changes.",
                            published ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
                    open(player, View.EVENT, holder.subject, holder.page, selected(holder));
                } else if (slot == 49 && allowed(player, "live")) {
                    plugin.playScreenEvent(selected(holder), holder.subject);
                    changed(player, "started event " + holder.subject + " on " + selected(holder));
                    open(player, View.LIVE, "", 0, selected(holder));
                } else if (slot < PAGE_SIZE) {
                    List<PlaybackItemView> items = plugin.eventItems(holder.subject);
                    if (slot < items.size()) {
                        PlaybackItemView item = items.get(slot);
                        String base = "events." + holder.subject + ".sequence." + item.id();
                        if (right) {
                            boolean current = (boolean) studio.draftValue(
                                    player, base + ".enabled", item.enabled());
                            studio.stage(player, base + ".enabled", !current);
                        } else if (shift) {
                            long seconds = Math.max(1, item.durationMillis() / 1000 + 5);
                            studio.stage(player, base + ".duration", seconds + "s");
                        }
                        open(player, View.EVENT, holder.subject, holder.page, selected(holder));
                    }
                }
            }
            case LIVE -> liveClick(player, holder, slot);
            case QUEUE -> queueClick(player, holder, slot, right, shift);
            case GROUPS -> {
                if (slot == 48) beginPrompt(player, PromptType.GROUP);
                else listSubjectClick(player, holder, slot, View.GROUP);
            }
            case GROUP -> groupClick(player, holder, slot);
            case TEMPLATES -> templateClick(player, holder, slot);
            case SCHEDULES -> {
                if (slot == 48) beginPrompt(player, PromptType.SCHEDULE);
            }
            case DIAGNOSTICS -> {
                if (slot == 49) plugin.toggleDebug(player);
                else if (slot < PAGE_SIZE) {
                    List<String> ids = plugin.screenIds();
                    if (slot < ids.size()) open(player, View.SCREEN, ids.get(slot), 0, ids.get(slot));
                }
            }
            case HISTORY -> {
                if (slot == 48 && allowed(player, "history")) {
                    boolean restored = studio.undoLastPublish(player);
                    player.sendMessage(Component.text(restored
                                    ? "The latest published config was restored."
                                    : "No usable config snapshot was found.",
                            restored ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
                    open(player, View.HISTORY, "", 0, selected(holder));
                }
            }
            case EMERGENCY -> {
                if (slot == 13 && allowed(player, "emergency")) {
                    studio.setEmergency(player, !studio.emergency());
                    openDashboard(player);
                } else if (slot == 31) {
                    openDashboard(player);
                }
            }
            default -> {
            }
        }
    }

    private void dashboardClick(Player player, int slot, boolean shift) {
        int[] favoriteSlots = {46, 47, 48, 50, 51, 52};
        List<String> favorites = studio.favorites(player);
        for (int index = 0; index < favoriteSlots.length; index++) {
            if (slot == favoriteSlots[index] && index < favorites.size()) {
                try {
                    View favorite = View.valueOf(favorites.get(index)
                            .toUpperCase(Locale.ROOT));
                    open(player, favorite, "", 0, null);
                } catch (IllegalArgumentException ignored) {
                }
                return;
            }
        }
        Map<Integer, View> views = Map.ofEntries(
                Map.entry(10, View.SCREENS), Map.entry(12, View.MEDIA),
                Map.entry(14, View.PLAYLISTS), Map.entry(16, View.EVENTS),
                Map.entry(28, View.GROUPS), Map.entry(30, View.LIVE),
                Map.entry(32, View.SCHEDULES), Map.entry(34, View.DIAGNOSTICS),
                Map.entry(36, View.TEMPLATES), Map.entry(38, View.HISTORY),
                Map.entry(49, View.EMERGENCY));
        View view = views.get(slot);
        if (view == null) return;
        String permission = permission(view);
        if (!allowed(player, permission)) {
            deny(player, "luigiscreen.menu." + permission);
            return;
        }
        if (shift) {
            studio.toggleFavorite(player, view.name().toLowerCase(Locale.ROOT));
            openDashboard(player);
            return;
        }
        open(player, view, "", 0, null);
    }

    private void screensClick(Player player, StudioHolder holder, int slot,
                              boolean right, boolean shift) {
        int index = holder.page * PAGE_SIZE + slot;
        List<String> ids = plugin.screenIds();
        if (slot >= PAGE_SIZE || index >= ids.size()) return;
        String id = ids.get(index);
        if (shift) {
            sessions.get(player.getUniqueId()).selectedScreen = id;
            open(player, View.LIVE, "", 0, id);
        } else if (right && allowed(player, "control")) {
            ScreenDefinition definition = plugin.screenDefinition(id);
            if (definition != null) plugin.setScreenEnabled(id, !definition.enabled());
            changed(player, "toggled screen " + id);
            open(player, View.SCREENS, "", holder.page, id);
        } else {
            open(player, View.SCREEN, id, 0, id);
        }
    }

    private void screenClick(Player player, StudioHolder holder, int slot) {
        String id = holder.subject;
        PlaybackSnapshot playback = plugin.playbackSnapshot(id);
        if (List.of(10, 12, 13, 14, 16, 32, 40).contains(slot)
                && !allowed(player, "control")) {
            deny(player, "luigiscreen.menu.control");
            return;
        }
        switch (slot) {
            case 10 -> {
                ScreenDefinition definition = plugin.screenDefinition(id);
                if (definition != null && allowed(player, "control")) {
                    plugin.setScreenEnabled(id, !definition.enabled());
                    changed(player, "toggled screen " + id);
                }
            }
            case 12 -> {
                plugin.pausePlayback(id, !playback.paused());
                changed(player, "changed hold on " + id);
            }
            case 13 -> {
                plugin.skipPlayback(id);
                changed(player, "skipped content on " + id);
            }
            case 14 -> {
                plugin.togglePlaybackRepeat(id);
                changed(player, "changed repeat on " + id);
            }
            case 16 -> {
                plugin.returnToAutomation(id);
                changed(player, "returned " + id + " to automation");
            }
            case 22 -> open(player, View.QUEUE, id, 0, id);
            case 24 -> open(player, View.PLAYLISTS, "", 0, id);
            case 28 -> teleport(player, id);
            case 30 -> highlight(player, id);
            case 32 -> {
                plugin.resyncScreen(id);
                changed(player, "resynced screen " + id);
            }
            case 40 -> {
                ScreenDefinition definition = plugin.screenDefinition(id);
                if (definition != null) {
                    plugin.setScreenPermissionRequired(id, !definition.permissionRequired());
                    changed(player, "changed visibility of " + id);
                }
            }
            default -> {
            }
        }
        open(player, View.SCREEN, id, 0, id);
    }

    private void mediaClick(Player player, StudioHolder holder, int slot, boolean right) {
        if (slot == 48) {
            studio.refreshMediaAsync();
            player.sendMessage(Component.text("Media Library refresh started.", NamedTextColor.GREEN));
            return;
        }
        if (!allowed(player, "live")) {
            deny(player, "luigiscreen.menu.live");
            return;
        }
        int index = holder.page * PAGE_SIZE + slot;
        List<MediaEntry> entries = studio.media();
        if (slot >= PAGE_SIZE || index >= entries.size()) return;
        MediaEntry entry = entries.get(index);
        if (!entry.valid() || entry.type() == null) return;
        plugin.queuePlayback(selected(holder), entry.source(), entry.id(), 30_000, !right);
        changed(player, (right ? "queued " : "played ") + entry.id()
                + " on " + selected(holder));
        open(player, View.LIVE, "", 0, selected(holder));
    }

    private void playlistsClick(Player player, StudioHolder holder, int slot, boolean right) {
        if (slot == 48) {
            beginPrompt(player, PromptType.PLAYLIST);
            return;
        }
        int index = holder.page * PAGE_SIZE + slot;
        List<String> ids = plugin.playlistIds();
        if (slot >= PAGE_SIZE || index >= ids.size()) return;
        String id = ids.get(index);
        if (right) {
            open(player, View.PLAYLIST, id, 0, selected(holder));
        } else {
            if (!allowed(player, "control")) {
                deny(player, "luigiscreen.menu.control");
                return;
            }
            plugin.setScreenPlaylist(selected(holder), id);
            changed(player, "assigned playlist " + id + " to " + selected(holder));
            open(player, View.SCREEN, selected(holder), 0, selected(holder));
        }
    }

    private void playlistClick(Player player, StudioHolder holder, int slot,
                               boolean right, boolean shift, boolean middle) {
        if (slot == 47) {
            studio.discardDraft(player);
            open(player, View.PLAYLIST, holder.subject, holder.page, selected(holder));
            return;
        }
        if (slot == 48) {
            boolean published = studio.publishDraft(player);
            player.sendMessage(Component.text(published
                    ? "Draft published." : "There are no valid draft changes.",
                    published ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            open(player, View.PLAYLIST, holder.subject, holder.page, selected(holder));
            return;
        }
        int index = holder.page * PAGE_SIZE + slot;
        List<PlaybackItemView> items = plugin.playlistItems(holder.subject);
        if (slot >= PAGE_SIZE || index >= items.size()) return;
        PlaybackItemView view = items.get(index);
        String base = "playlists." + holder.subject + ".items." + view.id();
        if (middle) {
            beginConditionPrompt(player, holder.subject, view.id());
            return;
        }
        if (right) {
            boolean current = (boolean) studio.draftValue(
                    player, base + ".enabled", view.enabled());
            studio.stage(player, base + ".enabled", !current);
            open(player, View.PLAYLIST, holder.subject, holder.page, selected(holder));
            return;
        }
        if (shift) {
            int current = ((Number) studio.draftValue(
                    player, base + ".weight", view.weight())).intValue();
            studio.stage(player, base + ".weight", Math.min(10_000, current + 1));
            open(player, View.PLAYLIST, holder.subject, holder.page, selected(holder));
            return;
        }
        ScreenSource source = ScreenSource.parse(view.type(), view.value());
        if (source != null && source.isValid()) {
            plugin.queuePlayback(selected(holder), source, view.id(), view.durationMillis(), true);
            changed(player, "previewed " + view.id() + " on " + selected(holder));
        }
    }

    private void eventsClick(Player player, StudioHolder holder, int slot, boolean right) {
        if (slot == 48) {
            beginPrompt(player, PromptType.EVENT);
            return;
        }
        int index = holder.page * PAGE_SIZE + slot;
        List<String> ids = plugin.eventIds();
        if (slot >= PAGE_SIZE || index >= ids.size()) return;
        String id = ids.get(index);
        if (right) {
            open(player, View.EVENT, id, 0, selected(holder));
        } else {
            if (!allowed(player, "live")) {
                deny(player, "luigiscreen.menu.live");
                return;
            }
            plugin.playScreenEvent(selected(holder), id);
            changed(player, "started event " + id + " on " + selected(holder));
            open(player, View.LIVE, "", 0, selected(holder));
        }
    }

    private void liveClick(Player player, StudioHolder holder, int slot) {
        String screen = selected(holder);
        PlaybackSnapshot playback = plugin.playbackSnapshot(screen);
        switch (slot) {
            case 10, 32 -> open(player, View.MEDIA, "", 0, screen);
            case 12, 14 -> plugin.skipPlayback(screen);
            case 16 -> plugin.pausePlayback(screen, !playback.paused());
            case 28 -> plugin.returnToAutomation(screen);
            case 20 -> {
                VoteStatus vote = studio.voteStatus(screen);
                if (vote == null) {
                    studio.startVote(player, screen, studio.defaultVoteOptions(), 60_000);
                } else {
                    studio.finishVote(player, screen);
                }
            }
            case 30 -> open(player, View.EVENTS, "", 0, screen);
            case 34 -> plugin.stopScreenEvent(screen);
            case 49 -> open(player, View.EMERGENCY, "", 0, screen);
            default -> {
            }
        }
        if (slot != 10 && slot != 30 && slot != 32 && slot != 49) {
            changed(player, "used Live Control on " + screen);
            open(player, View.LIVE, "", 0, screen);
        }
    }

    private void queueClick(Player player, StudioHolder holder, int slot,
                            boolean right, boolean shift) {
        if (!allowed(player, "live")) {
            deny(player, "luigiscreen.menu.live");
            return;
        }
        String screen = holder.subject;
        if (slot == 49) {
            plugin.clearPlaybackQueue(screen);
        } else if (slot < PAGE_SIZE) {
            if (right) plugin.removeQueuedPlayback(screen, slot);
            else if (shift) plugin.moveQueuedPlayback(screen, slot, -1);
            else plugin.playQueuedNow(screen, slot);
        }
        changed(player, "edited queue on " + screen);
        open(player, View.QUEUE, screen, 0, screen);
    }

    private void listSubjectClick(Player player, StudioHolder holder, int slot, View target) {
        List<String> values = studio.groupIds();
        if (slot < PAGE_SIZE && slot < values.size()) {
            open(player, target, values.get(slot), 0, selected(holder));
        }
    }

    private void groupClick(Player player, StudioHolder holder, int slot) {
        String action = switch (slot) {
            case 11 -> "start";
            case 13 -> "stop";
            case 15 -> "return";
            default -> "";
        };
        if (!action.isBlank()) {
            int changed = studio.applyGroup(player, holder.subject, action, "");
            player.sendMessage(Component.text("Updated " + changed + " screens.",
                    NamedTextColor.GREEN));
            open(player, View.GROUP, holder.subject, 0, selected(holder));
        }
    }

    private void templateClick(Player player, StudioHolder holder, int slot) {
        List<String> ids = studio.templateIds();
        if (slot < PAGE_SIZE && slot < ids.size()) {
            String id = ids.get(slot);
            boolean installed = studio.installTemplate(player, id);
            player.sendMessage(Component.text(t(installed
                            ? "studio.template-installed" : "studio.template-failed"),
                    installed ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
    }

    private void refreshOpenMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof StudioHolder holder
                    && (holder.view == View.DASHBOARD || holder.view == View.SCREENS
                    || holder.view == View.SCREEN || holder.view == View.LIVE
                    || holder.view == View.DIAGNOSTICS)) {
                render(player, holder);
            }
        }
    }

    private void beginPrompt(Player player, PromptType type) {
        player.closeInventory();
        synchronized (prompts) {
            prompts.put(player.getUniqueId(), new Prompt(type, "", ""));
        }
        String key = switch (type) {
            case GROUP -> "studio.prompt-group";
            case SCHEDULE -> "studio.prompt-schedule";
            case PLAYLIST -> "studio.prompt-playlist";
            case EVENT -> "studio.prompt-event";
            case CONDITIONS -> "studio.prompt-conditions";
        };
        player.sendMessage(Component.text(t(key), NamedTextColor.YELLOW));
        player.sendMessage(Component.text(t("studio.prompt-cancel"), NamedTextColor.DARK_GRAY));
    }

    private void beginConditionPrompt(Player player, String playlist, String item) {
        player.closeInventory();
        synchronized (prompts) {
            prompts.put(player.getUniqueId(),
                    new Prompt(PromptType.CONDITIONS, playlist, item));
        }
        player.sendMessage(Component.text(t("studio.prompt-conditions"),
                NamedTextColor.YELLOW));
        player.sendMessage(Component.text(t("studio.prompt-cancel"),
                NamedTextColor.DARK_GRAY));
    }

    private void completePrompt(Player player, Prompt prompt, String answer) {
        if (answer.equalsIgnoreCase("cancel")) {
            player.sendMessage(Component.text(t("studio.prompt-cancelled"),
                    NamedTextColor.YELLOW));
            openDashboard(player);
            return;
        }
        boolean success;
        View reopen;
        String[] parts = answer.split("\\s+");
        switch (prompt.type) {
            case GROUP -> {
                success = parts.length >= 2 && studio.createGroup(player, parts[0],
                        List.of(parts[1].split(",")));
                reopen = View.GROUPS;
            }
            case SCHEDULE -> {
                success = parts.length >= 4 && studio.createSchedule(
                        player, parts[0], parts[1], parts[2], parts[3],
                        parts.length > 4 ? parts[4] : "");
                reopen = View.SCHEDULES;
            }
            case PLAYLIST -> {
                success = parts.length == 1 && studio.createPlaylist(player, parts[0]);
                reopen = View.PLAYLISTS;
            }
            case EVENT -> {
                success = parts.length == 1 && studio.createEvent(player, parts[0]);
                reopen = View.EVENTS;
            }
            case CONDITIONS -> {
                success = applyConditionAnswer(player, prompt, answer);
                reopen = View.PLAYLIST;
            }
            default -> throw new IllegalStateException("Unknown prompt");
        }
        player.sendMessage(Component.text(t(success
                        ? "studio.prompt-success" : "studio.prompt-invalid"),
                success ? NamedTextColor.GREEN : NamedTextColor.RED));
        open(player, reopen,
                prompt.type == PromptType.CONDITIONS ? prompt.subject : "", 0, null);
    }

    private boolean applyConditionAnswer(Player player, Prompt prompt, String answer) {
        String base = "playlists." + prompt.subject + ".items."
                + prompt.item + ".conditions.";
        List<String> allowed = List.of("min-online", "max-online", "min-viewers",
                "max-viewers", "viewer-permission", "all-viewers-permission",
                "tps-above", "tps-below", "world", "days");
        boolean changed = false;
        for (String pair : answer.split(",")) {
            String[] values = pair.trim().split("=", 2);
            if (values.length != 2 || !allowed.contains(values[0])) continue;
            Object value = values[1];
            if (List.of("min-online", "max-online", "min-viewers", "max-viewers")
                    .contains(values[0])) {
                try {
                    value = Integer.parseInt(values[1]);
                } catch (NumberFormatException ignored) {
                    continue;
                }
            } else if (List.of("tps-above", "tps-below").contains(values[0])) {
                try {
                    value = Double.parseDouble(values[1]);
                } catch (NumberFormatException ignored) {
                    continue;
                }
            } else if ("days".equals(values[0])) {
                value = List.of(values[1].split("\\|"));
            }
            studio.stage(player, base + values[0], value);
            changed = true;
        }
        return changed;
    }

    private void teleport(Player player, String id) {
        ScreenDefinition definition = plugin.screenDefinition(id);
        World world = definition == null ? null : Bukkit.getWorld(definition.world());
        if (definition == null || world == null) return;
        Location target = definition.location().toLocation(world).add(0.5, 0, 0.5);
        target.add(definition.facing().getOppositeFace().getDirection().multiply(3));
        player.teleportAsync(target);
    }

    private void highlight(Player player, String id) {
        ScreenDefinition definition = plugin.screenDefinition(id);
        World world = definition == null ? null : Bukkit.getWorld(definition.world());
        if (definition == null || world == null) return;
        Location first = definition.location().toLocation(world).add(0.5, 0.5, 0.5);
        Location second = definition.secondCorner().toLocation(world).add(0.5, 0.5, 0.5);
        for (int step = 0; step <= 20; step++) {
            double ratio = step / 20.0;
            player.spawnParticle(Particle.END_ROD,
                    first.clone().add((second.getX() - first.getX()) * ratio,
                            0, (second.getZ() - first.getZ()) * ratio), 1, 0, 0, 0, 0);
            player.spawnParticle(Particle.END_ROD,
                    first.clone().add((second.getX() - first.getX()) * ratio,
                            second.getY() - first.getY(), (second.getZ() - first.getZ()) * ratio),
                    1, 0, 0, 0, 0);
            player.spawnParticle(Particle.END_ROD,
                    first.clone().add(0, (second.getY() - first.getY()) * ratio, 0),
                    1, 0, 0, 0, 0);
            player.spawnParticle(Particle.END_ROD,
                    second.clone().subtract(0, (second.getY() - first.getY()) * ratio, 0),
                    1, 0, 0, 0, 0);
        }
    }

    private void changed(Player player, String action) {
        studio.audit(player, action);
    }

    private boolean allowed(Player player, String section) {
        return player.hasPermission(ScreenPermissions.ADMIN)
                || player.hasPermission("luigiscreen.menu.*")
                || player.hasPermission("luigiscreen.menu." + section);
    }

    private void deny(Player player, String permission) {
        player.sendMessage(Component.text(
                "You need permission " + permission + ".", NamedTextColor.RED));
    }

    private void section(Player player, Inventory inventory, int slot, String permission,
                         Material material, String name, String detail) {
        boolean allowed = allowed(player, permission);
        inventory.setItem(slot, item(allowed ? material : Material.BARRIER, name,
                allowed ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY,
                text(detail), allowed ? text("Click to open")
                        : text("Missing luigiscreen.menu." + permission),
                text("Shift-click to pin this section.")));
    }

    private void nav(StudioHolder holder, View parent) {
        holder.inventory.setItem(45, item(Material.ARROW, t("studio.back"),
                NamedTextColor.YELLOW));
        if (holder.page > 0) {
            holder.inventory.setItem(46, item(Material.ARROW, t("studio.previous-page"),
                    NamedTextColor.YELLOW));
        }
        holder.inventory.setItem(51, item(Material.NETHER_STAR, t("studio.dashboard"),
                NamedTextColor.GREEN));
        if (holder.pageHasNext) {
            holder.inventory.setItem(53, item(Material.ARROW, t("studio.next-page"),
                    NamedTextColor.YELLOW));
        }
    }

    private void page(StudioHolder holder, int size) {
        holder.pageHasNext = (holder.page + 1) * PAGE_SIZE < size;
    }

    private static View parent(View view) {
        return switch (view) {
            case SCREEN -> View.SCREENS;
            case QUEUE -> View.SCREEN;
            case PLAYLIST -> View.PLAYLISTS;
            case EVENT -> View.EVENTS;
            case GROUP -> View.GROUPS;
            default -> View.DASHBOARD;
        };
    }

    private static String permission(View view) {
        return switch (view) {
            case DASHBOARD -> "dashboard";
            case SCREENS, SCREEN -> "screens";
            case QUEUE -> "live";
            case MEDIA -> "media";
            case PLAYLISTS, PLAYLIST -> "playlists";
            case EVENTS, EVENT -> "events";
            case LIVE -> "live";
            case GROUPS, GROUP -> "groups";
            case SCHEDULES -> "schedules";
            case TEMPLATES -> "templates";
            case DIAGNOSTICS -> "diagnostics";
            case HISTORY -> "history";
            case EMERGENCY -> "emergency";
        };
    }

    private static Material screenMaterial(
            ScreenDefinition definition, ScreenHealth health, PlaybackSnapshot playback) {
        if (!definition.enabled()) return Material.GRAY_CONCRETE;
        if (health != null && !"none".equalsIgnoreCase(health.error())) {
            return Material.RED_CONCRETE;
        }
        if (playback.paused()) return Material.YELLOW_CONCRETE;
        if ("event".equals(playback.mode())) return Material.REDSTONE_BLOCK;
        if ("playlist".equals(playback.mode())) return Material.JUKEBOX;
        return Material.LIME_CONCRETE;
    }

    private static Material mediaMaterial(MediaEntry entry) {
        if (!entry.valid()) return Material.BARRIER;
        if (entry.type() == null) return Material.PAPER;
        return switch (entry.type()) {
            case VIDEO -> Material.FILLED_MAP;
            case IMAGE, URL_IMAGE -> Material.PAINTING;
            case GIF -> Material.FIREWORK_STAR;
            case RTMP, MJPEG -> Material.ENDER_EYE;
        };
    }

    private static Material sourceMaterial(String type) {
        SourceType source = SourceType.parse(type);
        if (source == null) return Material.PAPER;
        return switch (source) {
            case VIDEO -> Material.FILLED_MAP;
            case IMAGE, URL_IMAGE -> Material.PAINTING;
            case GIF -> Material.FIREWORK_STAR;
            case RTMP, MJPEG -> Material.ENDER_EYE;
        };
    }

    private ItemStack healthItem(ScreenHealth health) {
        return healthItem(health, t("studio.health"));
    }

    private ItemStack healthItem(ScreenHealth health, String name) {
        boolean healthy = "none".equalsIgnoreCase(health.error());
        return item(healthy ? Material.HEART_OF_THE_SEA : Material.POISONOUS_POTATO,
                name, healthy ? NamedTextColor.AQUA : NamedTextColor.RED,
                line("State", health.state()), line("Error", health.error()),
                line("Last frame", duration(health.lastFrameAgeMillis())),
                line("Reconnects", health.reconnects()),
                line("Frames received/rendered",
                        health.receivedFrames() + "/" + health.renderedFrames()),
                line("Dropped/replaced", health.droppedFrames()),
                line("FPS effective/target", ONE_DECIMAL.format(health.effectiveFps())
                        + "/" + ONE_DECIMAL.format(health.targetFps())),
                line("Render last/average", nanos(health.lastRenderNanos())
                        + "/" + nanos(health.averageRenderNanos()) + " ms"),
                line("Viewers/shared", health.viewers() + "/" + health.sharedScreens()));
    }

    private static ItemStack item(Material material, String name, NamedTextColor color,
                                  Component... lore) {
        return item(new ItemStack(material), name, color, lore);
    }

    private static ItemStack item(ItemStack stack, String name, NamedTextColor color,
                                  Component... lore) {
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        List<Component> normalized = new ArrayList<>(lore.length);
        for (Component component : lore) {
            normalized.add(component.decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(normalized);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack error(String message) {
        return item(Material.BARRIER, t("studio.label.error"),
                NamedTextColor.RED, text(message));
    }

    private Component text(String value) {
        return Component.text(localized("text", value), NamedTextColor.GRAY);
    }

    private Component line(String key, Object value) {
        return Component.text(localized("label", key) + ": ", NamedTextColor.DARK_GRAY)
                .append(Component.text(String.valueOf(value), NamedTextColor.WHITE));
    }

    private String localized(String section, String value) {
        String slug = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return plugin.messages().plainOr("studio." + section + "." + slug, value);
    }

    private static Component wrap(String value, NamedTextColor color) {
        return Component.text(value == null ? "" : value, color);
    }

    private static String bytes(long value) {
        if (value < 1024) return value + " B";
        if (value < 1024 * 1024) return ONE_DECIMAL.format(value / 1024.0) + " KiB";
        if (value < 1024L * 1024 * 1024) {
            return ONE_DECIMAL.format(value / (1024.0 * 1024)) + " MiB";
        }
        return ONE_DECIMAL.format(value / (1024.0 * 1024 * 1024)) + " GiB";
    }

    private static String duration(long millis) {
        if (millis < 0) return "n/a";
        long seconds = millis / 1000;
        if (seconds < 60) return seconds + "s";
        return seconds / 60 + "m " + seconds % 60 + "s";
    }

    private static String nanos(long value) {
        return ONE_DECIMAL.format(value / 1_000_000.0);
    }

    private static String empty(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

    private static String selected(StudioHolder holder) {
        return holder.selectedScreen == null || holder.selectedScreen.isBlank()
                ? "none" : holder.selectedScreen;
    }

    private String t(String key) {
        return plugin.messages().plain(key);
    }

    private String title(View view, String subject) {
        return "LuigiScreen | " + switch (view) {
            case DASHBOARD -> t("studio.dashboard");
            case SCREENS -> t("studio.screens");
            case SCREEN -> subject;
            case MEDIA -> t("studio.media");
            case PLAYLISTS -> t("studio.playlists");
            case PLAYLIST -> subject;
            case EVENTS -> t("studio.events");
            case EVENT -> subject;
            case LIVE -> t("studio.live");
            case QUEUE -> t("studio.queue");
            case GROUPS -> t("studio.groups");
            case GROUP -> subject;
            case SCHEDULES -> t("studio.schedules");
            case TEMPLATES -> t("studio.templates");
            case DIAGNOSTICS -> t("studio.diagnostics");
            case HISTORY -> t("studio.history");
            case EMERGENCY -> t("studio.emergency");
        };
    }

    private static void decorate(Inventory inventory) {
        ItemStack pane = item(Material.BLACK_STAINED_GLASS_PANE, " ",
                NamedTextColor.BLACK);
        for (int slot = 45; slot < 54; slot++) {
            inventory.setItem(slot, pane);
        }
    }

    private enum View {
        DASHBOARD, SCREENS, SCREEN, MEDIA, PLAYLISTS, PLAYLIST, EVENTS, EVENT,
        LIVE, QUEUE, GROUPS, GROUP, SCHEDULES, TEMPLATES, DIAGNOSTICS, HISTORY,
        EMERGENCY
    }

    private static final class Session {
        private String selectedScreen = "";
    }

    private enum PromptType {
        GROUP, SCHEDULE, PLAYLIST, EVENT, CONDITIONS
    }

    private record Prompt(PromptType type, String subject, String item) {
    }

    private static final class StudioHolder implements InventoryHolder {
        private final View view;
        private final String subject;
        private final int page;
        private final String selectedScreen;
        private Inventory inventory;
        private boolean pageHasNext;

        private StudioHolder(View view, String subject, int page, String selectedScreen) {
            this.view = view;
            this.subject = subject == null ? "" : subject;
            this.page = page;
            this.selectedScreen = selectedScreen;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
