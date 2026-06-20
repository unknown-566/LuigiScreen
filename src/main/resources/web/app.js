(() => {
  "use strict";

  const app = {
    state: null,
    view: "dashboard",
    screenLayout: "grid",
    selected: null,
    detailScreen: null,
    selectedPlaylist: null,
    selectedEvent: null,
    previewMedia: null,
    liveTarget: null,
    mediaFilter: "all",
    mediaSearch: "",
    metrics: [],
    eventSource: null,
    live: false,
    confirmAction: null,
    paletteItems: []
  };

  const $ = selector => document.querySelector(selector);
  const $$ = selector => [...document.querySelectorAll(selector)];
  const esc = value => String(value ?? "").replace(/[&<>'"]/g, char => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;"
  })[char]);
  const lang = () => app.state?.config?.language?.toLowerCase().startsWith("cs") ? "cs" : "en";
  const tx = (en, cs) => lang() === "cs" ? cs : en;
  const info = (en, cs = en) => `<span class="info" tabindex="0" data-help="${esc(tx(en, cs))}">i</span>`;
  const fmt = value => new Intl.NumberFormat(lang() === "cs" ? "cs-CZ" : "en-US", { maximumFractionDigits: 2 }).format(Number(value || 0));
  const bytes = value => {
    let size = Number(value || 0); const units = ["B", "KB", "MB", "GB"]; let index = 0;
    while (size >= 1024 && index < units.length - 1) { size /= 1024; index++; }
    return `${size.toFixed(index ? 1 : 0)} ${units[index]}`;
  };
  const duration = millis => {
    if (millis == null || millis < 0) return "--";
    const seconds = Math.max(0, Math.round(millis / 1000));
    const minutes = Math.floor(seconds / 60);
    return `${String(minutes).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`;
  };
  const can = capability => app.state?.session?.capabilities?.includes("*") || app.state?.session?.capabilities?.includes(capability);
  const viewCapability = view => ({ dashboard:"dashboard", screens:"screens", media:"media",
    playlists:"playlists", events:"events", automations:"automations", live:"live",
    schedule:"schedules", monitoring:"monitoring", diagnostics:"diagnostics",
    configuration:"configuration", settings:"settings" })[view];

  const routes = {
    dashboard: ["OVERVIEW", "Dashboard", "Live operational overview of every screen.", "Živý provozní přehled všech pláten."],
    screens: ["BUILD", "Screens", "Physical displays, playback state and rendering health.", "Fyzická plátna, stav přehrávání a vykreslování."],
    media: ["BUILD", "Media Library", "Every local asset and its production readiness.", "Všechna lokální média a jejich připravenost."],
    playlists: ["BUILD", "Playlist Editor", "Sequence and smart rotation without blind random choices.", "Sekvence a chytrá rotace bez náhodného hádání."],
    events: ["BUILD", "Events", "Build and inspect controlled broadcast timelines.", "Tvorba a kontrola řízených vysílacích časových os."],
    automations: ["BUILD", "Automations", "Readable triggers, conditions and resulting actions.", "Čitelné spouštěče, podmínky a výsledné akce."],
    live: ["OPERATE", "Live Studio", "Prepare privately, then take the source live.", "Připrav zdroj v náhledu a potom ho pošli živě."],
    schedule: ["OPERATE", "Schedule", "Upcoming broadcasts and conflict detection.", "Nadcházející vysílání a kontrola konfliktů."],
    monitoring: ["SYSTEM", "Monitoring", "Real-time load, frame flow and per-screen performance.", "Zátěž, tok snímků a výkon jednotlivých pláten."],
    diagnostics: ["SYSTEM", "Diagnostics", "Actionable checks and explanations, not raw errors.", "Srozumitelné kontroly a vysvětlení místo holých chyb."],
    configuration: ["SYSTEM", "Configuration", "Stage safe changes before publishing them.", "Připrav bezpečné změny před jejich publikováním."],
    settings: ["SYSTEM", "Settings", "Studio access, session and server information.", "Přístup ke Studiu, relace a informace o serveru."]
  };
  const czechTitles = { dashboard:"Přehled", screens:"Plátna", media:"Knihovna médií",
    playlists:"Editor playlistů", events:"Eventy", automations:"Automatizace",
    live:"Živé studio", schedule:"Plán", monitoring:"Monitoring",
    diagnostics:"Diagnostika", configuration:"Konfigurace", settings:"Nastavení" };

  async function request(path, data) {
    const options = { headers: {} };
    if (data) {
      options.method = "POST";
      options.headers["Content-Type"] = "application/x-www-form-urlencoded;charset=UTF-8";
      options.headers["X-LuigiScreen-CSRF"] = app.state?.session?.csrf || "";
      options.body = new URLSearchParams(data).toString();
    }
    const response = await fetch(path, options);
    const result = await response.json().catch(() => ({ ok: false, message: `HTTP ${response.status}` }));
    if (response.status === 401) location.reload();
    if (!response.ok) throw new Error(result.message || `HTTP ${response.status}`);
    return result;
  }

  async function load() {
    try {
      app.state = await request("/api/state");
      app.live = true;
      pushMetric();
      updateChrome();
      render();
      connectLive();
    } catch (error) {
      $("#workspaceContent").innerHTML = `<div class="empty"><div><h2>${esc(tx("Studio could not connect", "Studio se nemůže připojit"))}</h2><p>${esc(error.message)}</p></div></div>`;
    }
  }

  function connectLive() {
    app.eventSource?.close();
    const source = new EventSource("/api/events");
    app.eventSource = source;
    source.addEventListener("connected", () => { app.live = true; updateChrome(); });
    source.addEventListener("state", event => {
      try {
        const live = JSON.parse(event.data);
        if (app.state && live.revision !== app.state.revision && !app.refreshing) {
          refresh();
          return;
        }
        app.state = { ...app.state, ...live,
          session: { ...app.state.session, ...live.session } };
        app.live = true;
        pushMetric();
        updateChrome();
        const active = document.activeElement;
        if (!active || !["INPUT", "SELECT", "TEXTAREA"].includes(active.tagName)) render(false);
      } catch (_) { /* A later live frame will recover the panel. */ }
    });
    source.onerror = () => { app.live = false; updateChrome(); };
  }

  function pushMetric() {
    const server = app.state?.server;
    if (!server) return;
    app.metrics.push({ tps: server.tps, mspt: server.mspt, fps: server.effectiveFps, viewers: server.viewers });
    if (app.metrics.length > 60) app.metrics.shift();
  }

  function updateChrome() {
    if (!app.state) return;
    const server = app.state.server;
    $("#serverName").textContent = server.name;
    $(".connection-pill").classList.toggle("online", app.live);
    $("#connectionDot").title = app.live ? "Live" : "Reconnecting";
    $("#sideVersion").textContent = `v${server.version}`;
    $("#sessionAvatar").textContent = (app.state.session.actor || "?").slice(0, 2).toUpperCase();
    $("#footTps").textContent = fmt(server.tps);
    $("#footMspt").textContent = fmt(server.mspt);
    $("#footScreens").textContent = `${server.activeScreens}/${server.totalScreens}`;
    $("#footViewers").textContent = server.viewers;
    $("#footFfmpeg").textContent = String(server.ffmpeg).toUpperCase();
    $("#footMap").textContent = server.mapEngine ? "READY" : "MISSING";
    $("#footWarnings").textContent = `${server.warnings} ${server.warnings === 1 ? "WARNING" : "WARNINGS"}`;
    $("#liveConnection").textContent = app.live ? "LIVE SYNC" : "RECONNECTING";
    $("#liveConnection").classList.toggle("offline", !app.live);
    const dirty = app.state.session.draftChanges > 0;
    const external = app.state.session.externalChanges;
    $("#draftState").textContent = external ? tx("External config change", "Externí změna configu") : dirty
      ? tx(`${app.state.session.draftChanges} unpublished`, `${app.state.session.draftChanges} nepublikováno`)
      : tx("No changes", "Žádné změny");
    $("#draftState").classList.toggle("dirty", dirty || external);
    $("#publishButton").disabled = !dirty || external;
    $("#discardButton").disabled = !dirty;
    document.documentElement.lang = lang();
    updateNavigationLabels();
    decorateHelp(document);
  }

  function updateNavigationLabels() {
    const labels = {
      dashboard: ["Dashboard", "Přehled"], screens: ["Screens", "Plátna"], media: ["Media", "Média"],
      playlists: ["Playlists", "Playlisty"], events: ["Events", "Eventy"], automations: ["Automations", "Automatizace"],
      live: ["Live Studio", "Živé studio"], schedule: ["Schedule", "Plán"], monitoring: ["Monitoring", "Monitoring"],
      diagnostics: ["Diagnostics", "Diagnostika"], configuration: ["Configuration", "Konfigurace"], settings: ["Settings", "Nastavení"]
    };
    $$('[data-view]').forEach(button => {
      const pair = labels[button.dataset.view];
      if (pair) button.querySelector("span").textContent = lang() === "cs" ? pair[1] : pair[0];
      button.hidden = !can(viewCapability(button.dataset.view));
    });
    $("#publishButton").childNodes[0].textContent = tx("Publish", "Publikovat");
    $("#discardButton").childNodes[0].textContent = tx("Discard", "Zahodit");
  }

  function render(resetScroll = true) {
    if (!app.state) return;
    const route = routes[app.view];
    $("#pageEyebrow").textContent = route[0];
    $("#pageTitle").textContent = lang() === "cs" ? czechTitles[app.view] : route[1];
    $("#pageSubtitle").textContent = lang() === "cs" ? route[3] : route[2];
    $$('[data-view]').forEach(button => button.classList.toggle("active", button.dataset.view === app.view));
    const content = $("#workspaceContent");
    const scroll = $(".workspace").scrollTop;
    content.classList.remove("loading");
    content.innerHTML = ({
      dashboard: renderDashboard, screens: renderScreens, media: renderMedia,
      playlists: renderPlaylists, events: renderEvents, automations: renderAutomations,
      live: renderLive, schedule: renderSchedule, monitoring: renderMonitoring,
      diagnostics: renderDiagnostics, configuration: renderConfiguration, settings: renderSettings
    })[app.view]();
    bindView();
    decorateHelp(content);
    renderInspector();
    if (!resetScroll) $(".workspace").scrollTop = scroll;
  }

  const metric = (label, value, note, helpEn, helpCs, tone = "") => `<div class="metric ${tone}"><label>${esc(label)} ${info(helpEn, helpCs)}</label><strong>${esc(value)}</strong><small>${esc(note)}</small></div>`;
  const panelHead = (title, helpEn, helpCs, aside = "") => `<div class="panel-head"><h2>${esc(title)} ${info(helpEn, helpCs)}</h2>${aside}</div>`;
  const empty = (en, cs) => `<div class="empty">${esc(tx(en, cs))}</div>`;

  function renderDashboard() {
    const s = app.state.server;
    const onAir = app.state.screens.filter(screen => screen.enabled);
    return `<div class="metrics">
      ${metric(tx("Screens online", "Plátna online"), `${s.activeScreens}/${s.totalScreens}`, tx("configured displays", "nastavených pláten"), "Enabled screens compared with every configured display.", "Počet zapnutých pláten oproti všem nastaveným.", s.activeScreens ? "good" : "warn")}
      ${metric(tx("Current viewers", "Aktuální diváci"), s.viewers, tx("receiving maps", "přijímá mapy"), "Unique screen receiver count sampled by the plugin.", "Počet příjemců pláten měřený pluginem.")}
      ${metric(tx("Active broadcasts", "Aktivní vysílání"), s.broadcasts, tx("live controllers", "živých řadičů"), "Screens with a running source, playlist or event controller.", "Plátna s běžícím zdrojem, playlistem nebo eventem.")}
      ${metric(tx("System health", "Stav systému"), healthLabel(s.health), `${s.warnings} ${tx("alerts", "upozornění")}`, "Combined health from sources, media, playlists, MapEngine and server timing.", "Souhrnný stav zdrojů, médií, playlistů, MapEngine a serveru.", s.health === "healthy" ? "good" : s.health === "critical" ? "bad" : "warn")}
    </div>
    <div class="grid-two"><section class="panel">${panelHead(tx("On air", "Právě vysílá"), "Screens currently visible to players.", "Plátna, která nyní mohou vidět hráči.", `<small>${onAir.length}</small>`)}
      ${onAir.length ? `<div class="screen-grid">${onAir.map(screenCard).join("")}</div>` : empty("No active screens.", "Žádná aktivní plátna.")}
    </section><div><section class="panel">${panelHead(tx("Upcoming", "Nadcházející"), "The nearest enabled schedule entries and queued work.", "Nejbližší aktivní plány a připravený obsah.")}${renderUpcoming()}</section>
      <section class="panel">${panelHead(tx("Alerts", "Upozornění"), "Only issues that can affect playback or publishing are shown here.", "Zde se ukazují jen problémy ovlivňující přehrávání nebo publikování.")}${renderAlerts()}</section></div></div>`;
  }

  function renderUpcoming() {
    const items = [...app.state.upcoming];
    app.state.screens.forEach(screen => screen.queue.slice(0, 2).forEach(item => items.push({ id: item, nextLabel: tx("Queued", "Ve frontě"), action: "queue", target: screen.id })));
    if (!items.length) return empty("Nothing is scheduled or queued.", "Nic není naplánované ani ve frontě.");
    return `<div class="upcoming-list">${items.slice(0, 8).map(item => `<div class="upcoming-item"><span class="tag ${item.action === "event" ? "event" : ""}">${esc(item.action)}</span><div><strong>${esc(item.value || item.id)}</strong><small>${esc(item.target || "")} · ${esc(item.nextLabel || "")}</small></div>${info("This item is expected to take control next.", "Tato položka by měla převzít řízení jako další.")}</div>`).join("")}</div>`;
  }

  function renderAlerts() {
    if (!app.state.alerts.length) return empty("No actionable alerts.", "Žádná upozornění vyžadující zásah.");
    return `<div class="alert-list">${app.state.alerts.slice(0, 8).map(alert => `<div class="alert ${esc(alert.level)}" data-open="${esc(alert.target)}" data-id="${esc(alert.id)}"><span class="alert-marker"></span><div><strong>${esc(alert.title)}</strong><small>${esc(alert.detail)}</small></div><button class="button ghost">${tx("Inspect", "Zkontrolovat")}</button></div>`).join("")}</div>`;
  }

  function healthLabel(value) {
    return ({ healthy: tx("Healthy", "V pořádku"), warnings: tx("Warnings", "Varování"), critical: tx("Critical", "Kritické") })[value] || value;
  }

  function screenCard(screen) {
    const stateClass = !screen.enabled ? "off" : screen.error !== "none" ? "error" : screen.mode === "event" ? "event" : "live";
    return `<article class="screen-card ${stateClass} ${app.selected?.type === "screen" && app.selected.id === screen.id ? "selected" : ""}" data-screen="${esc(screen.id)}">
      <div class="preview">${screen.preview ? `<img loading="lazy" src="${esc(screen.preview)}" alt="">` : `<span class="signal"></span>`}${screen.enabled ? `<span class="on-air">ON AIR</span>` : ""}</div>
      <div class="card-body"><div class="card-title"><h3>${esc(screen.id)} ${info("Open this screen's live controls, automation, location, performance and history.", "Otevře živé ovládání, automatizaci, umístění, výkon a historii plátna.")}</h3><span class="status ${esc(screen.state)}">${esc(screen.state)}</span></div>
      <div class="card-meta"><span>${tx("Now playing", "Nyní hraje")}<b>${esc(screen.current)}</b></span><span>${tx("Viewers", "Diváci")}<b>${screen.viewers}</b></span><span>${tx("Output", "Výstup")}<b>${screen.width}x${screen.height} maps</b></span><span>${tx("Actual FPS", "Skutečné FPS")}<b>${fmt(screen.actualFps)}</b></span></div></div></article>`;
  }

  function renderScreens() {
    if (app.detailScreen) {
      const screen = app.state.screens.find(item => item.id === app.detailScreen);
      if (screen) return renderScreenDetail(screen);
      app.detailScreen = null;
    }
    const layouts = [["grid", "Grid"], ["list", "List"], ["world", "World"]];
    const toolbar = `<div class="toolbar"><div class="view-switch">${layouts.map(([id,label]) => `<button data-layout="${id}" class="${app.screenLayout === id ? "active" : ""}">${label}</button>`).join("")}</div>${info("Grid is visual, List is compact, and World groups displays by dimension.", "Mřížka je vizuální, seznam úsporný a světy seskupují plátna podle dimenze.")}</div>`;
    if (!app.state.screens.length) return toolbar + empty("Create a screen in Minecraft with /screen create.", "Vytvoř plátno v Minecraftu příkazem /screen create.");
    if (app.screenLayout === "list") return toolbar + `<section class="panel"><table><thead><tr><th>SCREEN</th><th>STATE</th><th>WORLD</th><th>SIZE</th><th>VIEWERS</th><th>FPS</th><th>CONTENT</th></tr></thead><tbody>${app.state.screens.map(screen => `<tr class="clickable" data-screen="${esc(screen.id)}"><td><b>${esc(screen.id)}</b></td><td>${esc(screen.state)}</td><td>${esc(screen.world)}</td><td>${screen.width}x${screen.height}</td><td>${screen.viewers}</td><td>${fmt(screen.actualFps)}</td><td>${esc(screen.current)}</td></tr>`).join("")}</tbody></table></section>`;
    if (app.screenLayout === "world") {
      const worlds = app.state.screens.reduce((result, screen) => {
        (result[screen.world] ||= []).push(screen);
        return result;
      }, {});
      return toolbar + Object.entries(worlds).map(([world, screens]) => `<section class="panel">${panelHead(world, "Screens physically placed in this Minecraft world.", "Plátna fyzicky umístěná v tomto světě Minecraftu.", `<small>${screens.length}</small>`)}<div class="screen-grid">${screens.map(screenCard).join("")}</div></section>`).join("");
    }
    return toolbar + `<div class="screen-grid">${app.state.screens.map(screenCard).join("")}</div>`;
  }

  function renderScreenDetail(screen) {
    const tabs = ["Overview", "Automation", "Location", "Performance", "History"];
    return `<div class="toolbar"><button class="button ghost" data-back-screens>${tx("Back to screens", "Zpět na plátna")}</button><span class="tag ${screen.mode === "event" ? "event" : "live"}">${esc(screen.mode)}</span></div>
      <section class="panel"><div class="panel-head"><h2>${esc(screen.id)} ${info("The selected physical map display and its active controller.", "Vybrané fyzické mapové plátno a jeho aktivní řadič.")}</h2><small>${screen.viewers} ${tx("viewers", "diváků")}</small></div>
      <div class="grid-two"><div><div class="preview" style="aspect-ratio:${screen.width}/${screen.height}">${screen.preview ? `<img src="${esc(screen.preview)}" alt="">` : `<span class="signal"></span>`}${screen.enabled ? `<span class="on-air">ON AIR</span>` : ""}</div><div class="button-row"><button class="button success" data-action="screen.start" data-screen-id="${screen.id}">Play</button><button class="button ghost" data-action="${screen.paused ? "playback.resume" : "playback.pause"}" data-screen-id="${screen.id}">${screen.paused ? "Resume" : "Pause"}</button><button class="button ghost" data-action="playback.skip" data-screen-id="${screen.id}">Skip</button><button class="button ghost" data-action="playback.repeat" data-screen-id="${screen.id}">Repeat</button><button class="button primary" data-action="playback.return" data-screen-id="${screen.id}">Return Auto</button></div></div>
      <div><h3>${tx("Now playing", "Nyní hraje")} ${info("The exact media label currently controlled by LuigiScreen.", "Přesný název média právě řízeného LuigiScreenem.")}</h3><h2>${esc(screen.current)}</h2><p class="muted">${esc(screen.controller)} · ${duration(screen.remaining)} ${tx("remaining", "zbývá")}</p><div class="inspector-section"><h3>${tx("Why is this playing?", "Proč se toto přehrává?")} ${info("This explanation comes from the playback decision that selected the current item.", "Toto vysvětlení pochází z rozhodnutí, které vybralo aktuální položku.")}</h3><p>${esc(screen.reason)}</p></div><div class="property"><span>${tx("Next", "Další")}</span><b>${esc(screen.next)}</b></div><div class="property"><span>${tx("Queue", "Fronta")}</span><b>${screen.queue.length}</b></div></div></div></section>
      <section class="panel"><div class="view-switch">${tabs.map((tab,index) => `<button class="${index === 0 ? "active" : ""}">${tab}</button>`).join("")}</div><div class="grid-three" style="margin-top:14px">${metric("Source", screen.sourceType, screen.source, "The active typed media source. Sensitive URL values are masked.", "Aktivní typovaný zdroj. Citlivé části URL jsou skryté.")}${metric("Performance", `${fmt(screen.actualFps)} FPS`, `${fmt(screen.renderMillis)} ms render`, "Actual rendered frame rate and average map conversion time.", "Skutečné FPS a průměrný čas převodu map.")}${metric("Location", screen.world, `${screen.x}, ${screen.y}, ${screen.z}`, "Stored world and upper-left wall coordinate.", "Uložený svět a souřadnice levého horního rohu.")}</div></section>`;
  }

  function renderMedia() {
    const filtered = app.state.media.filter(item => (app.mediaFilter === "all" || item.type === app.mediaFilter)
      && (!app.mediaSearch || item.id.toLowerCase().includes(app.mediaSearch.toLowerCase())));
    return `<div class="toolbar"><input id="mediaSearch" value="${esc(app.mediaSearch)}" placeholder="${tx("Search media...", "Hledat média...")}"><select id="mediaFilter"><option value="all">${tx("All media", "Všechna média")}</option>${["video","image","gif"].map(type => `<option ${app.mediaFilter === type ? "selected" : ""}>${type}</option>`).join("")}</select>${info("Media Library scans the configured local directory and updates automatically.", "Knihovna automaticky prochází nastavenou lokální složku a sleduje změny.")}<span class="tag">${filtered.length}/${app.state.media.length}</span></div>
      ${filtered.length ? `<div class="media-grid">${filtered.map(mediaCard).join("")}</div>` : empty("No matching media.", "Žádná odpovídající média.")}`;
  }

  function mediaCard(media) {
    return `<article class="media-card ${app.selected?.type === "media" && app.selected.id === media.id ? "selected" : ""}" data-media="${esc(media.id)}"><div class="preview">${media.thumbnail ? `<img loading="lazy" src="${esc(media.thumbnail)}" alt="">` : `<span class="signal"></span>`}<span class="tag" style="position:absolute;right:8px;top:8px">${esc(media.type)}</span></div><div class="card-body"><div class="card-title"><h3 title="${esc(media.id)}">${esc(media.name)} ${info("Select this media to inspect metadata, references and live controls.", "Vyber médium pro metadata, použití a živé ovládání.")}</h3><span class="status ${media.valid ? "running" : "connecting"}">${media.valid ? "ready" : "problem"}</span></div><div class="card-meta"><span>${tx("Resolution", "Rozlišení")}<b>${media.width && media.height ? `${media.width}x${media.height}` : "--"}</b></span><span>${tx("Size", "Velikost")}<b>${bytes(media.size)}</b></span><span>${tx("Used by", "Používá")}<b>${media.references.length}</b></span><span>${tx("Plays", "Přehrání")}<b>${media.statistics.plays}</b></span></div></div></article>`;
  }

  function renderPlaylists() {
    const creator = can("playlists") ? `<div class="toolbar"><input id="newPlaylistName" placeholder="${tx("new_playlist", "novy_playlist")}"><button class="button primary" data-create-playlist>${tx("Create playlist", "Vytvořit playlist")}</button>${info("Creates a safe starter rotation that you can edit before assigning it.", "Vytvoří bezpečný základ rotace, který upravíš před přiřazením.")}</div>` : "";
    if (!app.selectedPlaylist) {
      return creator + (app.state.playlists.length ? `<div class="grid-three">${app.state.playlists.map(playlist => `<article class="automation-card panel" data-playlist="${esc(playlist.id)}">${panelHead(playlist.id, "Open the playlist editor and probability analysis.", "Otevře editor playlistu a analýzu pravděpodobností.", `<span class="tag">${playlist.items.length} items</span>`)}<p class="muted">${playlist.valid ? tx("Ready for playback", "Připraven k přehrávání") : tx("No usable items", "Žádné použitelné položky")}</p>${probabilityBars(playlist.items)}</article>`).join("")}</div>` : empty("No playlists configured.", "Nejsou nastavené žádné playlisty."));
    }
    const playlist = app.state.playlists.find(item => item.id === app.selectedPlaylist);
    if (!playlist) { app.selectedPlaylist = null; return renderPlaylists(); }
    return `<div class="toolbar"><button class="button ghost" data-back-playlists>${tx("All playlists", "Všechny playlisty")}</button><span class="tag">SMART ROTATION</span><button class="button ghost" data-simulate="${esc(playlist.id)}">${tx("Simulate 1,000 picks", "Simulovat 1 000 výběrů")}</button>${info("Simulation uses current weights and conditions without changing live playback.", "Simulace používá aktuální váhy a podmínky bez změny živého přehrávání.")}</div>
      ${can("playlists")?`<div class="toolbar"><select id="playlistAddMedia">${mediaOptions()}</select><input id="playlistAddItem" placeholder="item_name"><button class="button primary" data-add-playlist-item="${esc(playlist.id)}">${tx("Add media to draft", "Přidat médium do konceptu")}</button>${info("The new item is staged with weight 1 and a 30-second duration until you publish it.", "Nová položka se připraví s váhou 1 a délkou 30 sekund až do publikování.")}</div>`:""}
      <div class="grid-two"><section class="panel">${panelHead(playlist.id, "Items are evaluated for conditions, cooldown and anti-repeat before weighted selection.", "Před váženým výběrem se kontrolují podmínky, cooldown a zákaz opakování.")}<div class="timeline">${playlist.items.map((item,index) => `<article class="timeline-step" data-playlist-item="${esc(item.id)}"><span class="step-number">${String(index+1).padStart(2,"0")}</span><h3>${esc(item.id)} ${info("Click to edit this item's weight, duration and enabled state in the inspector.", "Kliknutím upravíš váhu, délku a zapnutí položky v inspectoru.")}</h3><small>${esc(item.type)} · ${duration(item.duration)}</small><div class="weight-bar"><span style="width:${Math.min(100,item.probability)}%"></span></div><div class="property"><span>${tx("Probability", "Pravděpodobnost")}</span><b>${fmt(item.probability)}%</b></div><div class="property"><span>${tx("Weight", "Váha")}</span><b>${item.weight}</b></div></article>`).join("")}</div></section><section class="panel">${panelHead(tx("Rotation analysis", "Analýza rotace"), "Estimated result after eligibility filters; percentages update with published weights.", "Odhad po filtrech způsobilosti; procenta se mění s publikovanými vahami.")}${probabilityBars(playlist.items)}<div class="inspector-section"><h3>Anti-repeat ${info("Recent item and category windows prevent repetitive playback.", "Historie položek a kategorií brání opakovanému přehrávání.")}</h3><p class="muted">${tx("Configured in the playlist draft settings. Items on cooldown are excluded before the roll.", "Nastavuje se v konceptu playlistu. Položky s cooldownem se před výběrem vyřadí.")}</p></div></section></div>`;
  }

  function probabilityBars(items) {
    if (!items.length) return empty("No usable items.", "Žádné použitelné položky.");
    return items.map(item => `<div class="property"><span>${esc(item.id)} ${info("Estimated share among currently eligible weighted items.", "Odhadovaný podíl mezi aktuálně způsobilými váženými položkami.")}</span><b>${fmt(item.probability)}%</b></div><div class="weight-bar"><span style="width:${Math.min(100,item.probability)}%"></span></div>`).join("");
  }

  function renderEvents() {
    const creator = can("events") ? `<div class="toolbar"><input id="newEventName" placeholder="${tx("new_event", "novy_event")}"><button class="button primary" data-create-event>${tx("Create event", "Vytvořit event")}</button>${info("Creates a starter timeline with an opening card and manual hold step.", "Vytvoří základní osu s úvodní kartou a ručním čekáním.")}</div>` : "";
    if (!app.selectedEvent) {
      return creator + (app.state.events.length ? `<div class="grid-three">${app.state.events.map(event => `<article class="automation-card panel" data-event="${esc(event.id)}">${panelHead(event.id, "Open the event timeline, inspect stages and start it on a target screen.", "Otevře časovou osu eventu, kroky a spuštění na cílovém plátně.", `<span class="tag event">${event.steps.length} steps</span>`)}<div class="timeline" style="padding-bottom:4px">${event.steps.slice(0,4).map(step => `<span class="tag">${esc(step.type)}</span>`).join("")}</div></article>`).join("")}</div>` : empty("No events configured.", "Nejsou nastavené žádné eventy."));
    }
    const event = app.state.events.find(item => item.id === app.selectedEvent);
    if (!event) { app.selectedEvent = null; return renderEvents(); }
    return `<div class="toolbar"><button class="button ghost" data-back-events>${tx("All events", "Všechny eventy")}</button><select id="eventTarget">${screenOptions(app.liveTarget)}</select><button class="button success" data-start-event="${esc(event.id)}">${tx("Start event", "Spustit event")}</button>${info("Starting an event temporarily takes control according to event priority.", "Spuštění eventu dočasně převezme řízení podle jeho priority.")}</div>${can("events")?`<div class="toolbar"><select id="eventAddMedia">${mediaOptions()}</select><input id="eventAddStep" placeholder="step_name"><button class="button primary" data-add-event-step="${esc(event.id)}">${tx("Add step to draft", "Přidat krok do konceptu")}</button>${info("The media step is staged with a 30-second duration and appears after publishing.", "Krok s médiem se připraví na 30 sekund a objeví se po publikování.")}</div>`:""}<section class="panel">${panelHead(event.id, "A simple timeline remains readable; branch steps expose advanced logic in their inspector.", "Jednoduchá osa zůstává čitelná; větvení ukáže pokročilou logiku v inspectoru.")}<div class="preview"><span class="signal"></span></div><div class="timeline">${event.steps.map((step,index) => `<article class="timeline-step" data-event-step="${esc(step.id)}"><span class="step-number">STEP ${index+1}</span><h3>${esc(step.id)} ${info("Select the stage to inspect duration, conditions and fallback behavior.", "Vyber krok pro délku, podmínky a záložní chování.")}</h3><small>${esc(step.type)} · ${duration(step.duration)}</small><p>${esc(step.value)}</p></article>`).join("")}</div></section>`;
  }

  function renderAutomations() {
    const schedules = app.state.schedules;
    const groupCreator = can("groups") ? `<section class="panel">${panelHead(tx("Screen groups", "Skupiny pláten"), "A group lets one live action target multiple physical displays.", "Skupina umožní jednou živou akcí cílit na více fyzických pláten.")}<div class="toolbar"><input id="newGroupName" placeholder="spawn_screens"><input id="newGroupScreens" placeholder="main,lobby"><button class="button primary" data-create-group>${tx("Create group", "Vytvořit skupinu")}</button></div><div class="quick-sources">${app.state.groups.map(group=>`<span class="tag">${esc(group.id)}: ${esc(group.screens.join(", "))}</span>`).join("")}</div></section>` : "";
    return groupCreator + `<section class="panel">${panelHead(tx("Readable automation rules", "Čitelná pravidla automatizace"), "Automation is expressed as WHEN, IF and THEN instead of an opaque node graph.", "Automatizace je zapsaná jako KDY, POKUD a POTOM místo nepřehledného grafu.")}<div class="grid-three">${schedules.length ? schedules.map(schedule => `<article class="automation-card panel"><span class="tag ${schedule.enabled ? "live" : ""}">${schedule.enabled ? "ENABLED" : "DISABLED"}</span><h3>${esc(schedule.id)} ${info("This rule is generated from an enabled schedule and its conflict policy.", "Toto pravidlo vychází z aktivního plánu a jeho řešení konfliktů.")}</h3><div class="condition"><span><b>WHEN</b><br>${esc(schedule.nextLabel)}</span></div><div class="condition"><span><b>IF</b><br>${esc(schedule.days.join(", "))}</span></div><div class="condition"><span><b>THEN</b><br>${esc(schedule.action)} ${esc(schedule.value)} on ${esc(schedule.target)}</span></div><small class="muted">Cooldown/conflict: ${esc(schedule.conflict)} · priority ${schedule.priority}</small></article>`).join("") : empty("No automation rules yet.", "Zatím nejsou žádná pravidla automatizace.")}</div></section>`;
  }

  function renderLive() {
    const target = targetScreen(app.liveTarget) || app.state.screens[0];
    if (target && !app.liveTarget) app.liveTarget = target.id;
    const media = app.state.media.find(item => item.id === app.previewMedia) || app.state.media.find(item => item.valid);
    if (media && !app.previewMedia) app.previewMedia = media.id;
    return `<div class="toolbar"><label>${tx("Screen target", "Cílové plátno")} ${info("Preview stays private until Take Live sends it to this screen or group.", "Náhled zůstane soukromý, dokud ho Take Live nepošle na plátno nebo skupinu.")}</label><select id="liveTarget">${targetOptions(app.liveTarget)}</select><button class="button primary" data-return-auto>${tx("Return to automation", "Vrátit automatizaci")}</button></div>
      <div class="studio-deck"><section class="monitor preview-monitor"><div class="monitor-head"><span>PREVIEW ${info("Prepared source that players cannot see yet.", "Připravený zdroj, který hráči ještě nevidí.")}</span><span>${media ? esc(media.type) : "EMPTY"}</span></div><div class="preview">${media?.thumbnail ? `<img src="${esc(media.thumbnail)}" alt="">` : `<span class="signal"></span>`}</div><div class="card-body"><h2>${esc(media?.name || tx("No source selected", "Není vybraný zdroj"))}</h2><select id="previewSource"><option value="">${tx("Choose media", "Vyber médium")}</option>${app.state.media.filter(item=>item.valid).map(item=>`<option value="${esc(item.id)}" ${item.id===app.previewMedia?"selected":""}>${esc(item.id)}</option>`).join("")}</select></div></section>
      <section class="monitor program"><div class="monitor-head"><span>PROGRAM / ON AIR ${info("This is what players at the target screen currently receive.", "Toto právě přijímají hráči u cílového plátna.")}</span><span>${target?.enabled ? "ON AIR" : "OFF"}</span></div><div class="preview">${target?.preview ? `<img src="${esc(target.preview)}" alt="">` : `<span class="signal"></span>`}${target?.enabled ? `<span class="on-air">ON AIR</span>`:""}</div><div class="card-body"><h2>${esc(target?.current || "--")}</h2><p class="muted">${esc(target?.reason || "")}</p></div></section></div>
      <div class="take-live"><button data-take-live ${!media || !target ? "disabled" : ""}>TAKE LIVE</button></div>
      <section class="panel">${panelHead(tx("Quick sources", "Rychlé zdroje"), "Frequently used media can be prepared with one click before going live.", "Často používaná média připravíš jedním kliknutím před vysíláním.")}<div class="quick-sources">${app.state.media.filter(item=>item.valid).slice(0,8).map(item=>`<button class="quick-source" data-cue-media="${esc(item.id)}"><b>${esc(item.name)}</b><br><small>${esc(item.type)}</small></button>`).join("")}</div></section>`;
  }

  function renderSchedule() {
    const creator = can("schedules") ? `<section class="panel">${panelHead(tx("New schedule", "Nový plán"), "Creates a daily server-time rule. Advanced days and conflict policy remain editable in studio.yml.", "Vytvoří denní pravidlo podle času serveru. Dny a konflikty lze dále upravit ve studio.yml.")}<div class="toolbar"><input id="newScheduleName" placeholder="evening_show"><input id="newScheduleTime" type="time" value="20:00"><select id="newScheduleTarget">${targetOptions(app.liveTarget)}</select><select id="newScheduleAction"><option>event</option><option>playlist</option><option>start</option><option>stop</option><option>return</option></select><input id="newScheduleValue" placeholder="event_or_playlist"><button class="button primary" data-create-schedule>${tx("Create schedule", "Vytvořit plán")}</button></div></section>` : "";
    return creator + `<section class="panel">${panelHead(tx("Upcoming schedule", "Nadcházející plán"), "Schedules execute at server local time. Conflicts are detected before publishing.", "Plány se spouštějí podle lokálního času serveru. Konflikty se kontrolují před publikováním.", `<div class="view-switch"><button class="active">Upcoming</button><button>Week</button><button>Month</button></div>`)}${app.state.schedules.length ? `<table><thead><tr><th>TIME</th><th>NAME</th><th>TARGET</th><th>ACTION</th><th>PRIORITY</th><th>CONFLICTS</th></tr></thead><tbody>${app.state.schedules.map(item=>`<tr><td>${esc(item.nextLabel)}</td><td><b>${esc(item.id)}</b></td><td>${esc(item.target)}</td><td><span class="tag ${item.action==="event"?"event":""}">${esc(item.action)}</span> ${esc(item.value)}</td><td>${item.priority}</td><td class="${item.conflicts.length?"warning-count":""}">${item.conflicts.length?esc(item.conflicts.join(", ")):"none"}</td></tr>`).join("")}</tbody></table>` : empty("No schedules configured.", "Nejsou nastavené žádné plány.")}</section>`;
  }

  function renderMonitoring() {
    const s = app.state.server;
    return `<div class="metrics">${metric("TPS", fmt(s.tps), "target 20.0", "Server ticks per second sampled by Paper.", "Počet ticků serveru za sekundu měřený Paperem.", s.tps>18?"good":"warn")}${metric("MSPT", `${fmt(s.mspt)} ms`, "tick cost", "Average tick processing time.", "Průměrný čas zpracování ticku.", s.mspt<40?"good":"warn")}${metric("Rendered FPS", fmt(s.effectiveFps), `${s.renderedFrames} frames`, "Current primary output frame rate.", "Aktuální rychlost hlavního výstupu.")}${metric("Heap", bytes(s.memoryUsed), `of ${bytes(s.memoryMax)}`, "Java heap used by the server process; native FFmpeg memory is separate.", "Java heap serveru; nativní paměť FFmpeg se počítá zvlášť.")}</div>
      <div class="grid-two"><section class="panel">${panelHead(tx("Server timing", "Časování serveru"), "Rolling samples received by this browser while Studio is open.", "Průběžné vzorky přijaté tímto prohlížečem během otevřeného Studia.")}<div class="chart">${chartBars("tps",20)}</div></section><section class="panel">${panelHead(tx("Rendered output", "Vykreslený výstup"), "Effective FPS after adaptive throttling.", "Efektivní FPS po adaptivním omezení.")}<div class="chart">${chartBars("fps",20)}</div></section></div>
      <section class="panel">${panelHead(tx("Per-screen performance", "Výkon jednotlivých pláten"), "Compare target FPS, actual FPS, map count, packet pressure and rendering cost.", "Porovná cílové a skutečné FPS, počet map a cenu vykreslení.")}<table><thead><tr><th>SCREEN</th><th>TARGET</th><th>ACTUAL</th><th>MAPS</th><th>VIEWERS</th><th>DROPPED</th><th>RENDER</th><th>PROFILE</th></tr></thead><tbody>${app.state.screens.map(screen=>`<tr><td>${esc(screen.id)}</td><td>${fmt(screen.targetFps)}</td><td>${fmt(screen.actualFps)}</td><td>${screen.maps}</td><td>${screen.viewers}</td><td>${screen.droppedFrames}</td><td>${fmt(screen.renderMillis)} ms</td><td><span class="tag">${performanceProfile(screen)}</span></td></tr>`).join("")}</tbody></table></section>`;
  }

  function chartBars(key, max) {
    return app.metrics.map(item => `<span style="height:${Math.max(3,Math.min(100,(Number(item[key]||0)/max)*100))}%" title="${fmt(item[key])}"></span>`).join("");
  }
  function performanceProfile(screen) { return screen.actualFps < screen.targetFps*.5 ? "PERFORMANCE" : screen.maps > 30 ? "BALANCED" : "QUALITY"; }

  function renderDiagnostics() {
    const s=app.state.server;
    const checks=[
      [s.minecraft==="1.21.11", "Paper version supported", `Minecraft ${s.minecraft}`],
      [s.mapEngine, "MapEngine connected", s.mapEngine?"API ready":"Plugin missing"],
      [s.ffmpeg!=="error", "FFmpeg pipeline", s.ffmpeg],
      [app.state.media.every(item=>item.valid), "Media library valid", `${app.state.media.filter(item=>!item.valid).length} problems`],
      [s.tps>18, "Server timing healthy", `${fmt(s.tps)} TPS / ${fmt(s.mspt)} MSPT`],
      [app.state.alerts.length===0, "No active alerts", `${app.state.alerts.length} alerts`]
    ];
    return `<div class="grid-two"><section class="panel">${panelHead(tx("System check", "Kontrola systému"), "A concise readiness checklist for the complete playback path.", "Stručná kontrola připravenosti celé přehrávací cesty.")}${checks.map(([ok,name,detail])=>`<div class="diagnostic-check"><span class="check-mark ${ok?"":"fail"}">${ok?"OK":"X"}</span><b>${esc(name)} ${info("This check updates live and points to the subsystem responsible for playback.", "Kontrola se mění živě a ukazuje část systému odpovědnou za přehrávání.")}</b><small class="muted">${esc(detail)}</small></div>`).join("")}</section><section class="panel">${panelHead(tx("Why not playing?", "Proč se nepřehrává?"), "Select a screen to inspect the latest decision, source error and queue state.", "Vyber plátno pro poslední rozhodnutí, chybu zdroje a stav fronty.")}<select id="diagnosticScreen">${screenOptions(app.selected?.type==="screen"?app.selected.id:app.liveTarget)}</select>${diagnosticReason()}</section></div><section class="panel">${panelHead(tx("Recent problems", "Poslední problémy"), "Errors are sanitized so stream passwords and keys never appear here.", "Chyby jsou očištěné, takže se zde neukážou hesla ani stream key.")}${renderAlerts()}</section>`;
  }

  function diagnosticReason() {
    const screen=screenById(app.selected?.type==="screen"?app.selected.id:app.liveTarget)||app.state.screens[0];
    if(!screen)return empty("No screen selected.","Není vybrané plátno.");
    return `<div class="inspector-section"><h3>${esc(screen.id)}</h3><p>${esc(screen.reason)}</p><div class="property"><span>${tx("Source state", "Stav zdroje")}</span><b>${esc(screen.state)}</b></div><div class="property"><span>${tx("Source error", "Chyba zdroje")}</span><b>${esc(screen.error)}</b></div><div class="property"><span>${tx("Next eligible", "Další způsobilé")}</span><b>${esc(screen.next)}</b></div></div>`;
  }

  function renderConfiguration() {
    const c=app.state.config;
    return `<div class="grid-two"><section class="panel">${panelHead(tx("Performance draft", "Koncept výkonu"), "Values remain private to this session until Publish is pressed.", "Hodnoty zůstanou v této relaci, dokud nestiskneš Publikovat.")}
      ${draftField("performance.max-map-updates-per-second", "integer", tx("Maximum map updates per second", "Maximum aktualizací map za sekundu"), c.maxMapUpdates, "Caps map packet pressure across the plugin.", "Omezuje množství mapových packetů v celém pluginu.")}
      ${draftToggle("performance.adaptive-fps", tx("Adaptive FPS", "Adaptivní FPS"), c.adaptiveFps, "Automatically lowers output FPS for expensive displays.", "Automaticky sníží FPS u náročných pláten.")}
      ${draftToggle("performance.pause-rendering-without-viewers", tx("Pause without viewers", "Pozastavit bez diváků"), c.pauseWithoutViewers, "Stops decoding when no eligible player can see a source.", "Zastaví dekódování, pokud zdroj nevidí žádný oprávněný hráč.")}
      </section><section class="panel">${panelHead(tx("Publishing pipeline", "Publikační proces"), "Studio snapshots config.yml, applies staged values and performs a safe plugin reload.", "Studio zazálohuje config.yml, použije koncept a bezpečně znovu načte plugin.")}${app.state.session.externalChanges?`<div class="alert critical"><span class="alert-marker"></span><div><strong>${tx("Configuration changed outside Studio", "Konfigurace se změnila mimo Studio")}</strong><small>${tx("Publishing is locked to prevent overwriting manual edits.", "Publikování je uzamčené, aby nepřepsalo ruční úpravy.")}</small></div></div>`:""}<div class="diagnostic-check"><span class="check-mark">1</span><b>Draft</b><small>${app.state.session.draftChanges} changes</small></div><div class="diagnostic-check"><span class="check-mark">2</span><b>Backup</b><small>config history</small></div><div class="diagnostic-check"><span class="check-mark">3</span><b>Validate</b><small>typed values</small></div><div class="diagnostic-check"><span class="check-mark">4</span><b>Publish</b><small>safe reload</small></div><div class="button-row"><button class="button publish" data-publish ${!app.state.session.draftChanges||app.state.session.externalChanges?"disabled":""}>${tx("Publish draft", "Publikovat koncept")}</button><button class="button ghost" data-discard ${!app.state.session.draftChanges?"disabled":""}>${tx("Discard", "Zahodit")}</button></div></section></div>
      <section class="panel">${panelHead(tx("Per-screen settings", "Nastavení pláten"), "Select a screen and stage FPS, viewer distance or permission changes in its inspector.", "Vyber plátno a připrav změnu FPS, vzdálenosti nebo oprávnění v inspectoru.")}<div class="screen-grid">${app.state.screens.map(screenCard).join("")}</div></section>`;
  }

  function draftField(path,type,label,value,helpEn,helpCs){return `<div class="field"><label>${esc(label)} ${info(helpEn,helpCs)}</label><div class="field-row"><input data-draft-input="${esc(path)}" data-type="${type}" value="${esc(value)}"><button class="button ghost" data-stage="${esc(path)}">${tx("Stage", "Připravit")}</button></div></div>`;}
  function draftToggle(path,label,value,helpEn,helpCs){return `<div class="field"><label>${esc(label)} ${info(helpEn,helpCs)}</label><div class="field-row"><select data-draft-input="${esc(path)}" data-type="boolean"><option value="true" ${value?"selected":""}>true</option><option value="false" ${!value?"selected":""}>false</option></select><button class="button ghost" data-stage="${esc(path)}">${tx("Stage", "Připravit")}</button></div></div>`;}

  function renderSettings() {
    const s=app.state.server, session=app.state.session;
    return `<div class="grid-two"><section class="panel">${panelHead(tx("Web Studio access", "Přístup k Web Studiu"), "The HTTP server is local-only by default and requires a one-time login link.", "HTTP server je ve výchozím stavu pouze lokální a vyžaduje jednorázový odkaz.")}<div class="property"><span>${tx("Server", "Server")} ${info("The name shown by this LuigiScreen instance.", "Název této instance LuigiScreen.")}</span><b>${esc(s.name)}</b></div><div class="property"><span>${tx("Version", "Verze")} ${info("Installed plugin version.", "Nainstalovaná verze pluginu.")}</span><b>${esc(s.version)}</b></div><div class="property"><span>${tx("Session user", "Uživatel relace")} ${info("The command sender who created this temporary login.", "Uživatel, který vytvořil toto dočasné přihlášení.")}</span><b>${esc(session.actor)}</b></div><div class="property"><span>${tx("Session expires", "Relace vyprší")} ${info("Expired sessions are rejected automatically.", "Relace po vypršení plugin automaticky odmítne.")}</span><b>${new Date(session.expiresAt).toLocaleString()}</b></div><div class="property"><span>${tx("Live connection", "Živé spojení")} ${info("Server-Sent Events keep operational data current without reloading the page.", "Server-Sent Events udržují provozní data aktuální bez obnovování stránky.")}</span><b>${app.live?"connected":"reconnecting"}</b></div><div class="button-row"><a class="button ghost" href="/logout">${tx("End this session", "Ukončit tuto relaci")}</a></div></section>
      <section class="panel">${panelHead(tx("Role capabilities", "Oprávnění role"), "Capabilities are copied from Bukkit permissions when the login link is created.", "Oprávnění se převezmou z Bukkit permissions při vytvoření odkazu.")}<div class="quick-sources">${session.capabilities.map(item=>`<span class="tag live">${esc(item)}</span>`).join("")}</div><p class="muted" style="margin-top:14px">${tx("Create a new link after changing permissions. Existing sessions do not silently gain access.", "Po změně oprávnění vytvoř nový odkaz. Existující relace nezískají přístup potají.")}</p></section></div>`;
  }

  function renderInspector() {
    const box=$("#inspectorContent"), title=$("#inspectorTitle");
    if(!app.selected){title.textContent=tx("Nothing selected","Nic není vybrané");box.innerHTML=`<p class="muted">${tx("Select an object in the workspace to inspect it here.","Vyber objekt v pracovní ploše a zde uvidíš jeho detail.")}</p>`;return;}
    if(app.selected.type==="screen") return inspectScreen(screenById(app.selected.id));
    if(app.selected.type==="media") return inspectMedia(app.state.media.find(item=>item.id===app.selected.id));
    if(app.selected.type==="playlist-item") return inspectPlaylistItem();
    if(app.selected.type==="event-step") return inspectEventStep();
  }

  function inspectScreen(screen){if(!screen)return;$("#inspectorTitle").textContent=screen.id;$("#inspectorContent").innerHTML=`<section class="inspector-section"><h3>${tx("Status","Stav")} ${info("Current source and playback state.","Aktuální stav zdroje a přehrávání.")}</h3><div class="property"><span>State</span><b>${esc(screen.state)}</b></div><div class="property"><span>Content</span><b>${esc(screen.current)}</b></div><div class="property"><span>Controller</span><b>${esc(screen.controller)}</b></div><div class="property"><span>Error</span><b>${esc(screen.error)}</b></div></section><section class="inspector-section"><h3>${tx("Properties","Vlastnosti")} ${info("Staged values apply only after Publish.","Připravené hodnoty se použijí až po publikování.")}</h3>${draftField(`screens.${screen.id}.fps`,"double","Target FPS",screen.targetFps,"Requested frames per second from 0.1 to 20.","Požadované FPS od 0,1 do 20.")}${draftField(`screens.${screen.id}.distance`,"double",tx("Viewer distance","Vzdálenost diváků"),screen.distance,"Maximum range for map receivers.","Maximální vzdálenost příjemců map.")}${draftToggle(`screens.${screen.id}.enabled`,tx("Enabled","Zapnuto"),screen.enabled,"Whether the display starts and accepts media.","Zda se plátno spouští a přijímá média.")}${draftToggle(`screens.${screen.id}.permission-required`,tx("Require view permission","Vyžadovat oprávnění"),screen.permissionRequired,"When enabled, players need the per-screen see permission.","Po zapnutí hráči potřebují oprávnění see pro toto plátno.")}</section><section class="inspector-section"><h3>${tx("Live control","Živé ovládání")} ${info("These controls affect production immediately.","Tyto ovládací prvky mění živý provoz okamžitě.")}</h3><div class="button-row"><button class="button ${screen.enabled?"danger":"success"}" data-action="screen.${screen.enabled?"stop":"start"}" data-screen-id="${screen.id}">${screen.enabled?"Stop":"Start"}</button><button class="button ghost" data-action="screen.resync" data-screen-id="${screen.id}">Resync</button><button class="button primary" data-action="playback.return" data-screen-id="${screen.id}">Return Auto</button></div></section>`;decorateHelp($("#inspectorContent"));bindActions($("#inspectorContent"));}

  function inspectMedia(media){if(!media)return;$("#inspectorTitle").textContent=media.name;$("#inspectorContent").innerHTML=`${media.thumbnail?`<div class="preview"><img src="${esc(media.thumbnail)}" alt=""></div>`:""}<section class="inspector-section"><h3>${tx("Media details","Detail média")} ${info("Metadata is read from the local file and never exposes unrestricted filesystem paths.","Metadata se čtou z lokálního souboru bez odhalení neomezených cest.")}</h3><div class="property"><span>Type</span><b>${esc(media.type)}</b></div><div class="property"><span>Resolution</span><b>${media.width}x${media.height}</b></div><div class="property"><span>Size</span><b>${bytes(media.size)}</b></div><div class="property"><span>Validation</span><b>${media.valid?"ready":esc(media.problem)}</b></div><div class="property"><span>Used by</span><b>${esc(media.references.join(", ")||"none")}</b></div></section><section class="inspector-section"><h3>${tx("Preview and program","Náhled a vysílání")} ${info("Cue prepares the item; Play Now interrupts the selected screen or group after confirmation.","Připravit vloží položku do fronty; Přehrát nyní po potvrzení přeruší plátno nebo skupinu.")}</h3><div class="field"><label>${tx("Target","Cíl")}</label><select id="mediaTarget">${targetOptions(app.liveTarget)}</select></div><div class="button-row"><button class="button ghost" data-media-queue="${esc(media.id)}">${tx("Queue next","Zařadit dál")}</button><button class="button danger" data-media-live="${esc(media.id)}">${tx("Play now","Přehrát nyní")}</button></div></section>`;decorateHelp($("#inspectorContent"));bindActions($("#inspectorContent"));}

  function inspectPlaylistItem(){
    const playlist=app.state.playlists.find(value=>value.id===app.selectedPlaylist);
    const item=playlist?.items.find(value=>value.id===app.selected.id);
    if(!item)return;
    $("#inspectorTitle").textContent=item.id;
    const base=`playlists.${playlist.id}.items.${item.id}`;
    $("#inspectorContent").innerHTML=`<section class="inspector-section"><h3>${tx("Selection","Výběr")} ${info("Weight affects probability only after eligibility conditions pass.","Váha ovlivní pravděpodobnost až po splnění podmínek.")}</h3><div class="property"><span>Probability</span><b>${fmt(item.probability)}%</b></div><div class="property"><span>Conditions</span><b>${esc(item.conditions)}</b></div>${draftField(`${base}.weight`,"integer",tx("Weight","Váha"),item.weight,"Relative weighted chance among eligible items.","Relativní vážená šance mezi způsobilými položkami.")}${draftField(`${base}.duration`,"duration",tx("Duration","Délka"),`${Math.round(item.duration/1000)}s`,"How long this item controls the screen.","Jak dlouho položka řídí plátno.")}${draftField(`${base}.cooldown`,"duration","Cooldown",`${Math.round(item.cooldown/1000)}s`,"Minimum wait before this item is eligible again.","Minimální čekání před dalším možným výběrem.")}${draftToggle(`${base}.enabled`,tx("Enabled","Zapnuto"),item.enabled,"Disabled items are excluded before selection.","Vypnuté položky se před výběrem vyřadí.")}</section><section class="inspector-section"><h3>${tx("Condition Builder","Editor podmínek")} ${info("Each condition is evaluated before cooldown and weighted selection.","Každá podmínka se ověří před cooldownem a váženým výběrem.")}</h3>${draftField(`${base}.conditions.min-viewers`,"integer",tx("Minimum viewers","Minimum diváků"),0,"Required viewers near this screen.","Požadovaný počet diváků poblíž plátna.")}${draftField(`${base}.conditions.tps-above`,"double",tx("TPS above","TPS vyšší než"),18,"Minimum safe server TPS.","Minimální bezpečné TPS serveru.")}${draftField(`${base}.conditions.any-viewer-permission`,"string",tx("Any viewer permission","Oprávnění některého diváka"),"","At least one nearby viewer must have this permission.","Alespoň jeden blízký divák musí mít toto oprávnění.")}<div class="field"><label>${tx("Test on screen","Otestovat na plátně")} ${info("Evaluates the published conditions against live server state.","Ověří publikované podmínky proti živému stavu serveru.")}</label><select id="conditionTestScreen">${screenOptions(app.liveTarget)}</select></div><button class="button ghost" data-test-conditions>${tx("Test conditions","Otestovat podmínky")}</button></section>`;
    decorateHelp($("#inspectorContent"));bindActions($("#inspectorContent"));
  }

  function inspectEventStep(){const event=app.state.events.find(item=>item.id===app.selectedEvent),step=event?.steps.find(entry=>entry.id===app.selected.id);if(!step)return;$("#inspectorTitle").textContent=step.id;const base=`events.${event.id}.sequence.${step.id}`;$("#inspectorContent").innerHTML=`<section class="inspector-section"><h3>${tx("Event stage","Krok eventu")} ${info("A stage runs in timeline order unless branch or wait logic changes the flow.","Krok běží podle osy, pokud tok nezmění větev nebo čekání.")}</h3><div class="property"><span>Type</span><b>${esc(step.type)}</b></div><div class="property"><span>Value</span><b>${esc(step.value)}</b></div><div class="property"><span>Conditions</span><b>${esc(step.conditions)}</b></div>${draftField(`${base}.duration`,"duration",tx("Duration","Délka"),`${Math.round(step.duration/1000)}s`,"Maximum or fixed stage duration depending on its type.","Maximální nebo pevná délka podle typu kroku.")}${draftToggle(`${base}.enabled`,tx("Enabled","Zapnuto"),step.enabled,"Disabled stages are skipped safely.","Vypnuté kroky se bezpečně přeskočí.")}${draftField(`${base}.conditions.min-viewers`,"integer",tx("Minimum viewers","Minimum diváků"),0,"The stage waits or skips when too few viewers are nearby.","Krok čeká nebo se přeskočí, pokud je poblíž málo diváků.")}${draftField(`${base}.conditions.tps-above`,"double",tx("TPS above","TPS vyšší než"),18,"Protects the server from expensive stages during low TPS.","Chrání server před náročným krokem při nízkém TPS.")}</section>`;decorateHelp($("#inspectorContent"));bindActions($("#inspectorContent"));}

  function bindView(){
    $$('[data-screen]').forEach(element=>element.onclick=()=>{app.selected={type:"screen",id:element.dataset.screen};app.detailScreen=element.dataset.screen;render();openInspector();});
    $$('[data-media]').forEach(element=>element.onclick=()=>{app.selected={type:"media",id:element.dataset.media};renderInspector();openInspector();$$('[data-media]').forEach(card=>card.classList.toggle("selected",card===element));});
    $$('[data-layout]').forEach(button=>button.onclick=()=>{app.screenLayout=button.dataset.layout;render();});
    $('[data-back-screens]')?.addEventListener("click",()=>{app.detailScreen=null;render();});
    $$('[data-playlist]').forEach(element=>element.onclick=()=>{app.selectedPlaylist=element.dataset.playlist;render();});
    $('[data-back-playlists]')?.addEventListener("click",()=>{app.selectedPlaylist=null;app.selected=null;render();});
    $$('[data-playlist-item]').forEach(element=>element.onclick=()=>{app.selected={type:"playlist-item",id:element.dataset.playlistItem};renderInspector();openInspector();});
    $$('[data-event]').forEach(element=>element.onclick=()=>{app.selectedEvent=element.dataset.event;render();});
    $('[data-back-events]')?.addEventListener("click",()=>{app.selectedEvent=null;app.selected=null;render();});
    $$('[data-event-step]').forEach(element=>element.onclick=()=>{app.selected={type:"event-step",id:element.dataset.eventStep};renderInspector();openInspector();});
    $('[data-start-event]')?.addEventListener("click",event=>action("event.play",{screen:$("#eventTarget").value,event:event.currentTarget.dataset.startEvent},true));
    $('[data-simulate]')?.addEventListener("click",()=>toast(tx("Probabilities shown are normalized from 1,000 analysis selections.","Zobrazené pravděpodobnosti jsou normalizované z 1 000 analytických výběrů.")));
    $('[data-return-auto]')?.addEventListener("click",()=>{const target=$("#liveTarget").value;action(screenById(target)?"playback.return":"group.return",screenById(target)?{screen:target}:{group:target},true);});
    $('[data-take-live]')?.addEventListener("click",()=>action("media.play",{screen:$("#liveTarget").value,media:app.previewMedia,duration:30000},true));
    $('[data-cue-media]') && $$('[data-cue-media]').forEach(button=>button.onclick=()=>{app.previewMedia=button.dataset.cueMedia;render();});
    $("#previewSource")?.addEventListener("change",event=>{app.previewMedia=event.target.value;render();});
    $("#liveTarget")?.addEventListener("change",event=>{app.liveTarget=event.target.value;render();});
    $("#mediaSearch")?.addEventListener("input",event=>{app.mediaSearch=event.target.value;window.clearTimeout(app.searchTimer);app.searchTimer=setTimeout(()=>render(false),150);});
    $("#mediaFilter")?.addEventListener("change",event=>{app.mediaFilter=event.target.value;render();});
    $$('[data-open]').forEach(element=>element.onclick=()=>{app.view=element.dataset.open;if(element.dataset.id&&app.view==="screens"){app.detailScreen=element.dataset.id;app.selected={type:"screen",id:element.dataset.id};}render();});
    $("#diagnosticScreen")?.addEventListener("change",event=>{app.liveTarget=event.target.value;app.selected={type:"screen",id:event.target.value};render();});
    $('[data-create-playlist]')?.addEventListener("click",()=>createNamed("playlist.create",$("#newPlaylistName").value));
    $('[data-create-event]')?.addEventListener("click",()=>createNamed("event.create",$("#newEventName").value));
    $('[data-create-group]')?.addEventListener("click",()=>action("group.create",{name:$("#newGroupName").value,screens:$("#newGroupScreens").value}));
    $('[data-create-schedule]')?.addEventListener("click",()=>action("schedule.create",{name:$("#newScheduleName").value,time:$("#newScheduleTime").value,target:$("#newScheduleTarget").value,scheduleAction:$("#newScheduleAction").value,value:$("#newScheduleValue").value}));
    $('[data-add-playlist-item]')?.addEventListener("click",event=>action("playlist.item.add",{playlist:event.currentTarget.dataset.addPlaylistItem,item:$("#playlistAddItem").value,media:$("#playlistAddMedia").value}));
    $('[data-add-event-step]')?.addEventListener("click",event=>action("event.step.add",{event:event.currentTarget.dataset.addEventStep,step:$("#eventAddStep").value,media:$("#eventAddMedia").value}));
    bindActions($("#workspaceContent"));
  }

  function createNamed(actionName,name){if(!name?.trim()){toast(tx("Enter a valid name first.","Nejdřív zadej platný název."),true);return;}action(actionName,{name:name.trim()});}

  function bindActions(root){
    root.querySelectorAll('[data-action]').forEach(button=>button.onclick=()=>action(button.dataset.action,{screen:button.dataset.screenId},["screen.stop","playback.skip"].includes(button.dataset.action)));
    root.querySelectorAll('[data-stage]').forEach(button=>button.onclick=()=>stage(button.dataset.stage,root));
    root.querySelectorAll('[data-publish]').forEach(button=>button.onclick=publish);
    root.querySelectorAll('[data-discard]').forEach(button=>button.onclick=discard);
    root.querySelectorAll('[data-media-queue]').forEach(button=>button.onclick=()=>action("media.queue",{screen:$("#mediaTarget").value,media:button.dataset.mediaQueue,duration:30000}));
    root.querySelectorAll('[data-media-live]').forEach(button=>button.onclick=()=>action("media.play",{screen:$("#mediaTarget").value,media:button.dataset.mediaLive,duration:30000},true));
    root.querySelectorAll('[data-test-conditions]').forEach(button=>button.onclick=()=>action("conditions.test",{screen:$("#conditionTestScreen").value,playlist:app.selectedPlaylist,item:app.selected?.id}));
  }

  async function action(name,data={},confirm=false){
    const run=async()=>{try{const result=await request("/api/action",{action:name,...data});toast(result.message);await refresh();}catch(error){toast(error.message,true);}};
    if(confirm)return ask(tx("Apply live action?","Provést živou akci?"),tx("This can immediately change what players see.","Toto může okamžitě změnit obsah, který hráči vidí."),run);
    return run();
  }

  async function stage(path,root=document){
    const input=root.querySelector(`[data-draft-input="${CSS.escape(path)}"]`);
    if(!input)return;
    try{const result=await request("/api/draft",{path,value:input.value,type:input.dataset.type||"string"});toast(result.message);await refresh();}catch(error){toast(error.message,true);}
  }
  async function publish(){ask(tx("Publish this draft?","Publikovat tento koncept?"),tx("Studio will create a backup, apply staged values and safely reload LuigiScreen.","Studio vytvoří zálohu, použije připravené hodnoty a bezpečně načte LuigiScreen."),async()=>{try{const result=await request("/api/publish",{action:"publish"});toast(result.message);await refresh();}catch(error){toast(error.message,true);}});}
  async function discard(){try{const result=await request("/api/publish",{action:"discard"});toast(result.message);await refresh();}catch(error){toast(error.message,true);}}
  async function refresh(){if(app.refreshing)return;app.refreshing=true;try{app.state=await request("/api/state");updateChrome();render(false);}finally{app.refreshing=false;}}

  function screenById(id){return app.state?.screens?.find(screen=>screen.id===id);}
  function screenOptions(selected){return app.state.screens.map(screen=>`<option value="${esc(screen.id)}" ${screen.id===selected?"selected":""}>${esc(screen.id)}</option>`).join("");}
  function targetOptions(selected){const screens=`<optgroup label="Screens">${screenOptions(selected)}</optgroup>`;const groups=app.state.groups.length?`<optgroup label="Groups">${app.state.groups.map(group=>`<option value="${esc(group.id)}" ${group.id===selected?"selected":""}>${esc(group.id)} (${group.screens.length})</option>`).join("")}</optgroup>`:"";return screens+groups;}
  function mediaOptions(){const options=app.state.media.filter(item=>item.valid).map(item=>`<option value="${esc(item.id)}">${esc(item.id)}</option>`).join("");return options||`<option value="">${tx("No valid media", "Žádná platná média")}</option>`;}
  function targetScreen(id){const direct=screenById(id);if(direct)return direct;const group=app.state?.groups?.find(item=>item.id===id);return group?.screens?.map(screenById).find(Boolean);}
  function decorateHelp(root){
    root.querySelectorAll('[data-help]:not(.info)').forEach(element=>{if(element.dataset.helpDecorated)return;element.dataset.helpDecorated="true";element.appendChild(infoBadge(element.dataset.help));element.removeAttribute("data-help");});
    root.querySelectorAll('.property > span:first-child, .field > label, .card-meta > span, th').forEach(element=>{if(element.querySelector('.info'))return;const label=element.childNodes[0]?.textContent?.trim()||element.textContent.trim();element.appendChild(infoBadge(fallbackHelp(label)));});
  }
  function infoBadge(help){const badge=document.createElement("span");badge.className="info";badge.tabIndex=0;badge.dataset.help=help;badge.textContent="i";return badge;}
  function fallbackHelp(label){const key=label.toLowerCase();const explanations={state:["Current runtime state reported by the plugin.","Aktuální provozní stav hlášený pluginem."],content:["Media or logical step currently controlling the display.","Médium nebo krok, který právě řídí plátno."],controller:["The playlist, event or direct source responsible for playback.","Playlist, event nebo přímý zdroj odpovědný za přehrávání."],error:["Last sanitized problem reported by this subsystem.","Poslední očištěný problém hlášený touto částí."],type:["The typed source or operation category.","Typ zdroje nebo kategorie operace."],resolution:["Pixel width and height detected from the source.","Šířka a výška zdroje v pixelech."],size:["Stored file size on the server.","Velikost souboru uloženého na serveru."],validation:["Whether the value can be safely used by LuigiScreen.","Zda může LuigiScreen tuto hodnotu bezpečně použít."],"used by":["Configured playlists and events referencing this item.","Playlisty a eventy, které tuto položku používají."],probability:["Estimated chance after weights and eligibility rules.","Odhadovaná šance po vyhodnocení vah a pravidel."],conditions:["Rules that must pass before this item is eligible.","Pravidla, která musí položka splnit před výběrem."],weight:["Relative selection weight among eligible items.","Relativní váha výběru mezi způsobilými položkami."],duration:["How long this item or stage remains active.","Jak dlouho položka nebo krok zůstane aktivní."],cooldown:["Minimum delay before an item may be selected again.","Minimální prodleva před dalším možným výběrem."],enabled:["Whether this object participates in live operation.","Zda se objekt účastní živého provozu."],source:["Media input currently assigned to the screen.","Mediální vstup aktuálně přiřazený plátnu."],performance:["Rendering speed and frame processing cost.","Rychlost vykreslování a cena zpracování snímků."],location:["Stored Minecraft world and block coordinates.","Uložený svět Minecraftu a souřadnice bloků."],"now playing":["Item players are receiving right now.","Položka, kterou hráči právě přijímají."],viewers:["Players currently eligible and close enough to receive this screen.","Hráči, kteří mají oprávnění a jsou dostatečně blízko plátnu."],output:["Physical map dimensions of the screen.","Fyzické mapové rozměry plátna."],"actual fps":["Frames actually rendered after adaptive limits.","Snímky skutečně vykreslené po adaptivním omezení."]};const pair=explanations[key]||["Explains the value or control shown next to this icon.","Vysvětluje hodnotu nebo ovládací prvek vedle této ikony."];return lang()==="cs"?pair[1]:pair[0];}
  function openInspector(){if(innerWidth<=900)$("#inspector").classList.add("open");}
  function toast(message,error=false){const item=document.createElement("div");item.className=`toast ${error?"error":""}`;item.textContent=message;$("#toasts").append(item);setTimeout(()=>item.remove(),4200);}
  function ask(title,text,callback){app.confirmAction=callback;$("#confirmTitle").textContent=title;$("#confirmText").textContent=text;$("#confirmModal").hidden=false;}

  function buildPalette(){
    const items=[];
    Object.keys(routes).filter(view=>can(viewCapability(view))).forEach(view=>items.push({label:routes[view][1],detail:routes[view][0],run:()=>{app.view=view;render();}}));
    app.state?.screens.forEach(screen=>items.push({label:`${tx("Open screen","Otevřít plátno")} ${screen.id}`,detail:"SCREEN",run:()=>{app.view="screens";app.detailScreen=screen.id;app.selected={type:"screen",id:screen.id};render();}}));
    app.state?.media.slice(0,40).forEach(media=>items.push({label:media.name,detail:"MEDIA",run:()=>{app.view="media";app.selected={type:"media",id:media.id};render();openInspector();}}));
    items.push({label:tx("Return all screens to automation","Vrátit všechna plátna automatizaci"),detail:"ACTION",run:()=>app.state.screens.forEach(screen=>action("playback.return",{screen:screen.id}))});
    app.paletteItems=items;
  }
  function openPalette(){buildPalette();$("#commandPalette").hidden=false;$("#commandInput").value="";renderPalette();setTimeout(()=>$("#commandInput").focus(),0);}
  function closePalette(){$("#commandPalette").hidden=true;}
  function renderPalette(){const query=$("#commandInput").value.toLowerCase();const items=app.paletteItems.filter(item=>item.label.toLowerCase().includes(query)).slice(0,12);$("#commandResults").innerHTML=items.map((item,index)=>`<div class="palette-result ${index===0?"selected":""}" data-palette="${index}"><span>${esc(item.label)}</span><small>${esc(item.detail)}</small></div>`).join("");$$('[data-palette]').forEach((element,index)=>element.onclick=()=>{items[index].run();closePalette();});app.filteredPalette=items;}

  $$('[data-view]').forEach(button=>button.onclick=()=>{app.view=button.dataset.view;app.detailScreen=null;app.selected=null;render();if(innerWidth<=900)$("#sidebar").classList.remove("open");});
  $("#sidebarToggle").onclick=()=>innerWidth<=900?$("#sidebar").classList.toggle("open"):$("#app").classList.toggle("collapsed");
  $("#closeInspector").onclick=()=>$("#inspector").classList.remove("open");
  $("#commandTrigger").onclick=openPalette;
  $("#commandInput").oninput=renderPalette;
  $("#commandPalette").onclick=event=>{if(event.target===$("#commandPalette"))closePalette();};
  $("#confirmCancel").onclick=()=>{$("#confirmModal").hidden=true;app.confirmAction=null;};
  $("#confirmAccept").onclick=()=>{const callback=app.confirmAction;$("#confirmModal").hidden=true;app.confirmAction=null;callback?.();};
  $("#publishButton").onclick=publish;
  $("#discardButton").onclick=discard;
  document.addEventListener("keydown",event=>{if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==="k"){event.preventDefault();openPalette();}if(event.key==="Escape"){closePalette();$("#confirmModal").hidden=true;$("#inspector").classList.remove("open");}if(event.key==="Enter"&&!$("#commandPalette").hidden&&app.filteredPalette?.[0]){app.filteredPalette[0].run();closePalette();}});
  $$('[data-mobile]').forEach(button=>button.onclick=()=>{const screen=screenById(app.liveTarget)||app.state?.screens?.[0];if(!screen)return;if(button.dataset.mobile==="preview"){app.view="live";render();}if(button.dataset.mobile==="take")app.view="live",render();if(button.dataset.mobile==="next")action("playback.skip",{screen:screen.id},true);if(button.dataset.mobile==="pause")action(screen.paused?"playback.resume":"playback.pause",{screen:screen.id});if(button.dataset.mobile==="emergency")action(app.state.emergency?"emergency.disable":"emergency.enable",{},true);});
  decorateHelp(document);
  load();
})();
