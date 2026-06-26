(() => {
  "use strict";

  const app = {
    state: null,
    view: "dashboard",
    screenLayout: "grid",
    screenDetailTab: "overview",
    selected: null,
    detailScreen: null,
    selectedPlaylist: null,
    selectedEvent: null,
    selectedAutomation: null,
    previewMedia: null,
    liveTarget: null,
    mediaPlaylistTarget: null,
    playlistAssignTarget: null,
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
  const info = (en, cs = en) => `<span class="help-copy" data-help="${esc(tx(en, cs))}" aria-hidden="true"></span>`;
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
    schedule: ["OPERATE", "Automation Calendar", "Server-time automation rules and conflict detection.", "Časová pravidla automatizace a kontrola konfliktů."],
    monitoring: ["SYSTEM", "Monitoring", "Real-time load, frame flow and per-screen performance.", "Zátěž, tok snímků a výkon jednotlivých pláten."],
    diagnostics: ["SYSTEM", "Diagnostics", "Actionable checks and explanations, not raw errors.", "Srozumitelné kontroly a vysvětlení místo holých chyb."],
    configuration: ["SYSTEM", "Configuration", "Stage safe changes before publishing them.", "Připrav bezpečné změny před jejich publikováním."],
    settings: ["SYSTEM", "Settings", "Studio access, session and server information.", "Přístup ke Studiu, relace a informace o serveru."]
  };
  const czechTitles = { dashboard:"Přehled", screens:"Plátna", media:"Knihovna médií",
    playlists:"Editor playlistů", events:"Eventy", automations:"Automatizace",
    live:"Živé studio", schedule:"Kalendář automatizací", monitoring:"Monitoring",
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
      live: ["Live Studio", "Živé studio"], schedule: ["Calendar", "Kalendář"], monitoring: ["Monitoring", "Monitoring"],
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
      live: renderLive, schedule: renderAutomations, monitoring: renderMonitoring,
      diagnostics: renderDiagnostics, configuration: renderConfiguration, settings: renderSettings
    })[app.view]();
    bindView();
    decorateHelp(content);
    renderInspector();
    if (!resetScroll) $(".workspace").scrollTop = scroll;
  }

  const metric = (label, value, note, helpEn, helpCs, tone = "") => `<div class="metric ${tone}" data-help="${esc(tx(helpEn, helpCs))}"><label>${esc(label)}</label><strong>${esc(value)}</strong><small>${esc(note)}</small></div>`;
  const panelHead = (title, helpEn, helpCs, aside = "") => `<div class="panel-head"><div class="panel-heading-copy"><h2>${esc(title)}</h2><small>${esc(tx(helpEn, helpCs))}</small></div>${aside}</div>`;
  const empty = (en, cs) => `<div class="empty">${esc(tx(en, cs))}</div>`;

  function renderDashboard() {
    const s = app.state.server;
    const onAir = app.state.screens.filter(screen => screen.enabled);
    return `${renderLaunchpad()}<div class="metrics">
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

  function renderLaunchpad() {
    const screens = app.state.screens.length;
    const media = app.state.media.filter(item => item.valid).length;
    const playlists = app.state.playlists.length;
    const web = app.state.web || {};
    const nextStep = !screens ? "screen" : !media ? "media" : !playlists ? "playlist" : "live";
    const hosts = (web.lanHosts || []).slice(0, 2).join(", ") || tx("auto detected", "automaticky zjištěno");
    const step = (id, number, title, text, action, command = "") => `<article class="launch-step ${nextStep === id ? "active" : ""}">
      <span>${number}</span><div><h3>${esc(title)}</h3><p>${esc(text)}</p>
      ${command ? `<code>${esc(command)}</code>` : ""}</div><button class="button ${nextStep === id ? "primary" : "ghost"}" data-jump="${esc(action)}">${esc(tx("Open", "Otevřít"))}</button></article>`;
    return `<section class="launchpad">
      <div class="launch-copy"><span class="eyebrow">${esc(tx("START HERE", "ZAČNI TADY"))}</span>
        <h2>${esc(tx("No config hunting. Build the screen from here.", "Žádný lov v configu. Plátno postavíš odsud."))}</h2>
        <p>${esc(tx("Use one web link, drop media into the media folder, then control what players see.", "Použij jeden web odkaz, naházej média do složky a pak řídíš, co hráči vidí."))}</p>
        <div class="launch-badges"><span>${esc(web.lanReady ? tx("LAN ready", "LAN připraveno") : tx("Local only", "Jen lokálně"))}</span><span>Port ${esc(web.port || 8765)}</span><span>${esc(hosts)}</span></div>
      </div>
      <div class="launch-steps">
        ${step("screen", "01", tx("Place the canvas", "Postav plátno"), tx("Stand in Minecraft, look at a wall and create the map display.", "Stoupni si ve hře, koukni na zeď a vytvoř mapové plátno."), "screens", "/screen create main 7 4")}
        ${step("media", "02", tx("Add media", "Přidej média"), tx("Put images, GIFs or videos into the LuigiScreen media folder.", "Dej obrázky, GIFy nebo videa do media složky LuigiScreen."), "media", app.state.config.mediaDirectory)}
        ${step("playlist", "03", tx("Make rotation", "Udělej rotaci"), tx("Create a playlist so the screen can run without babysitting.", "Vytvoř playlist, aby plátno běželo samo bez hlídání."), "playlists")}
        ${step("live", "04", tx("Go live", "Pusť to live"), tx("Preview a source, then take it live only when it looks right.", "Nejdřív nahledni zdroj a až pak ho pošli hráčům."), "live")}
      </div>
    </section>`;
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
    const tabs = [["overview", tx("Overview", "Přehled")], ["automation", tx("Automation", "Automatika")], ["location", tx("Location", "Lokace")], ["performance", tx("Performance", "Výkon")], ["history", tx("History", "Historie")]];
    if (!tabs.some(([id]) => id === app.screenDetailTab)) app.screenDetailTab = "overview";
    const active = app.screenDetailTab;
    return `<div class="toolbar"><button class="button ghost" data-back-screens>${tx("Back to screens", "Zpět na plátna")}</button><span class="tag ${screen.mode === "event" ? "event" : "live"}">${esc(screen.mode)}</span><span class="tag">${screen.playlist ? esc(screen.playlist) : tx("direct source", "přímý zdroj")}</span></div>
      <section class="panel"><div class="panel-head"><div><span class="eyebrow">SCREEN</span><h2>${esc(screen.id)} ${info("The selected physical map display and its active controller.", "Vybrané fyzické mapové plátno a jeho aktivní řadič.")}</h2></div><small>${screen.viewers} ${tx("viewers", "diváků")}</small></div>
      <div class="grid-two"><div><div class="preview" style="aspect-ratio:${screen.width}/${screen.height}">${screen.preview ? `<img src="${esc(screen.preview)}" alt="">` : `<span class="signal"></span>`}${screen.enabled ? `<span class="on-air">ON AIR</span>` : ""}</div><div class="button-row"><button class="button ${screen.enabled ? "danger" : "success"}" data-action="screen.${screen.enabled ? "stop" : "start"}" data-screen-id="${screen.id}">${screen.enabled ? tx("Stop screen", "Zastavit plátno") : tx("Start screen", "Zapnout plátno")}</button><button class="button ghost" data-action="${screen.paused ? "playback.resume" : "playback.pause"}" data-screen-id="${screen.id}">${screen.paused ? tx("Resume", "Pokračovat") : tx("Pause", "Pozastavit")}</button><button class="button ghost" data-action="playback.skip" data-screen-id="${screen.id}">Skip</button><button class="button ghost" data-action="playback.repeat" data-screen-id="${screen.id}">Repeat</button><button class="button primary" data-action="playback.return" data-screen-id="${screen.id}">${tx("Return auto", "Vrátit automatiku")}</button></div></div>
      <div><h3>${tx("Now playing", "Nyní hraje")} ${info("The exact media label currently controlled by LuigiScreen.", "Přesný název média právě řízeného LuigiScreenem.")}</h3><h2>${esc(screen.current)}</h2><p class="muted">${esc(screen.controller)} - ${duration(screen.remaining)} ${tx("remaining", "zbývá")}</p><div class="inspector-section"><h3>${tx("Why is this playing?", "Proč se toto přehrává?")}</h3><p>${esc(screen.reason)}</p></div><div class="property"><span>${tx("Next", "Další")}</span><b>${esc(screen.next)}</b></div><div class="property"><span>${tx("Queue", "Fronta")}</span><b>${screen.queue.length}</b></div></div></div></section>
      <section class="panel screen-detail-panel"><div class="view-switch screen-tabs">${tabs.map(([id,label]) => `<button data-screen-tab="${id}" class="${active === id ? "active" : ""}">${esc(label)}</button>`).join("")}</div>${renderScreenDetailTab(screen, active)}</section>`;
  }

  function renderScreenDetailTab(screen, tab) {
    if (tab === "automation") return renderScreenAutomation(screen);
    if (tab === "location") return `<div class="grid-three screen-tab-content">${metric("World", screen.world, `${screen.x}, ${screen.y}, ${screen.z}`, "Stored Minecraft world and block coordinate.", "Uložený svět a souřadnice bloku.")}${metric("Facing", screen.facing, `${screen.width}x${screen.height} maps`, "Wall direction and physical map size.", "Směr stěny a fyzická velikost map.")}${metric("View permission", screen.permissionRequired ? screen.viewPermission : tx("not required", "není potřeba"), `${fmt(screen.distance)} blocks`, "Who can see this screen and from how far.", "Kdo vidí plátno a z jaké vzdálenosti.")}</div>`;
    if (tab === "performance") return `<div class="grid-three screen-tab-content">${metric("Target FPS", fmt(screen.targetFps), "configured", "Requested screen frame rate.", "Požadovaná snímková frekvence plátna.")}${metric("Actual FPS", fmt(screen.actualFps), `${screen.renderedFrames} rendered`, "Frames actually rendered after adaptive limits.", "Skutečně vykreslené snímky po limitech.")}${metric("Render cost", `${fmt(screen.renderMillis)} ms`, `${screen.droppedFrames} dropped`, "Average map conversion time and dropped frames.", "Průměrný převod map a zahozené snímky.")}</div><table><thead><tr><th>RECEIVED</th><th>RENDERED</th><th>DROPPED</th><th>RECONNECTS</th><th>LAST FRAME</th></tr></thead><tbody><tr><td>${screen.receivedFrames}</td><td>${screen.renderedFrames}</td><td>${screen.droppedFrames}</td><td>${screen.reconnects}</td><td>${duration(screen.lastFrameAge)}</td></tr></tbody></table>`;
    if (tab === "history") return screen.history?.length ? `<div class="upcoming-list screen-tab-content">${screen.history.slice().reverse().map(item => `<div class="upcoming-item"><span class="tag">LOG</span><div><strong>${esc(item)}</strong><small>${esc(screen.id)}</small></div></div>`).join("")}</div>` : empty("No playback history yet.", "Zatím tu není historie přehrávání.");
    return `<div class="grid-three screen-tab-content">${metric("Source", screen.sourceType, screen.source, "The active typed media source. Sensitive URL values are masked.", "Aktivní typovaný zdroj. Citlivé části URL jsou skryté.")}${metric("Automation", screen.playlist || tx("Direct", "Přímo"), screen.mode, "Playlist or event currently controlling this screen.", "Playlist nebo event, který teď řídí plátno.")}${metric("Location", screen.world, `${screen.x}, ${screen.y}, ${screen.z}`, "Stored world and upper-left wall coordinate.", "Uložený svět a souřadnice levého horního rohu.")}</div>`;
  }

  function renderScreenAutomation(screen) {
    const playlists = app.state.playlists;
    const events = app.state.events;
    const media = app.state.media.filter(item => item.valid);
    const selectedPlaylist = screen.playlist || playlists[0]?.id || "";
    const playlistBody = playlists.length
      ? `<div class="field"><label>${tx("Playlist", "Playlist")} ${info("Assigning a playlist saves it on this screen and starts its rotation immediately.", "Prirazeni playlist ulozi na platno a hned spusti rotaci.")}</label><select id="detailPlaylist">${playlistOptions(selectedPlaylist)}</select></div><div class="button-row"><button class="button primary" data-assign-playlist data-screen-id="${screen.id}">${tx("Assign and play", "Priradit a prehrat")}</button><button class="button ghost" data-clear-playlist data-screen-id="${screen.id}" ${screen.playlist ? "" : "disabled"}>${tx("Clear playlist", "Zrusit playlist")}</button><button class="button ghost" data-jump="playlists">${tx("Edit playlists", "Upravit playlisty")}</button></div>`
      : `<p class="muted">${tx("No playlists exist yet. Create one first, then assign it here.", "Zatim neexistuje zadny playlist. Nejdřív ho vytvor a pak ho tady prirad.")}</p><button class="button primary" data-jump="playlists">${tx("Create playlist", "Vytvorit playlist")}</button>`;
    const eventBody = events.length
      ? `<div class="field"><label>${tx("Event", "Event")} ${info("Events temporarily interrupt the screen, then return it to its playlist or source.", "Event docasne prerusi platno a potom ho vrati na playlist nebo zdroj.")}</label><select id="detailEvent">${eventOptions("")}</select></div><div class="button-row"><button class="button warning" data-start-event-detail data-screen-id="${screen.id}">${tx("Start event", "Spustit event")}</button><button class="button ghost" data-stop-event-detail data-screen-id="${screen.id}">${tx("Stop event", "Zastavit event")}</button></div>`
      : `<p class="muted">${tx("Events are optional. Use them later for temporary announcements or shows.", "Eventy jsou volitelne. Pozdeji se hodi pro docasna oznameni nebo show.")}</p><button class="button ghost" data-jump="events">${tx("Open events", "Otevrit eventy")}</button>`;
    const manualBody = media.length
      ? `<div class="field"><label>${tx("Media", "Medium")} ${info("Play Now interrupts the screen until you return it to automation.", "Play Now docasne prerusi platno, dokud ho nevratis do automatiky.")}</label><select id="screenManualMedia">${mediaOptions()}</select></div><div class="button-row"><button class="button danger" data-screen-play-media data-screen-id="${screen.id}">${tx("Play now", "Prehrat hned")}</button><button class="button primary" data-action="playback.return" data-screen-id="${screen.id}">${tx("Return auto", "Vratit automatiku")}</button></div>`
      : `<p class="muted">${tx("No valid media found yet. Add files to the media folder or configure a source first.", "Zatim nejsou zadna platna media. Pridej soubory do media slozky nebo nastav zdroj.")}</p><button class="button ghost" data-jump="media">${tx("Open media", "Otevrit media")}</button>`;
    return `<div class="automation-flow screen-tab-content"><section class="panel nested-panel choice-card primary-choice">${panelHead(tx("1. Normal playback", "1. Normalni prehravani"), "Most screens should use a playlist. It keeps playing automatically.", "Vetsina platen ma pouzivat playlist. Bezi automaticky.", `<span class="tag">${screen.playlist ? esc(screen.playlist) : tx("not assigned", "neprirazeno")}</span>`)}${playlistBody}</section><section class="panel nested-panel choice-card">${panelHead(tx("2. Temporary event", "2. Docasny event"), "Use this only when you want to override the playlist for a moment.", "Pouzij to jen kdyz chces playlist na chvili prebit.", `<span class="tag ${screen.mode === "event" ? "event" : ""}">${esc(screen.mode)}</span>`)}${eventBody}</section><section class="panel nested-panel choice-card">${panelHead(tx("3. Manual source", "3. Manualni zdroj"), "Test or force one media item right now.", "Otestuj nebo vynut jedno medium hned ted.")}${manualBody}</section></div><div class="inspector-section"><h3>${tx("Rule of thumb", "Jednoduche pravidlo")}</h3><p class="muted">${tx("Use playlists for normal automatic playback, events for temporary overrides, and manual media only for testing or urgent takeovers.", "Playlist je normalni automatika, event je docasne preruseni a manualni medium je hlavne test nebo rychle prevzeti.")}</p></div>`;
  }
  function playlistOptions(selected = "", includeEmpty = false) {
    const emptyOption = includeEmpty ? `<option value="">${tx("No playlist", "Žádný playlist")}</option>` : "";
    return emptyOption + app.state.playlists.map(playlist => `<option value="${esc(playlist.id)}" ${playlist.id === selected ? "selected" : ""}>${esc(playlist.id)} (${playlist.items.length})</option>`).join("");
  }

  function eventOptions(selected = "") {
    return app.state.events.map(event => `<option value="${esc(event.id)}" ${event.id === selected ? "selected" : ""}>${esc(event.id)} (${event.steps.length})</option>`).join("");
  }

  function renderMedia() {
    const playlists = app.state.playlists;
    if (playlists.length && !playlists.some(playlist => playlist.id === app.mediaPlaylistTarget)) {
      app.mediaPlaylistTarget = playlists[0].id;
    } else if (!playlists.length) {
      app.mediaPlaylistTarget = null;
    }
    const filtered = app.state.media.filter(item => (app.mediaFilter === "all" || item.type === app.mediaFilter)
      && (!app.mediaSearch || item.id.toLowerCase().includes(app.mediaSearch.toLowerCase())));
    const playlistPicker = playlists.length
      ? `<label>${tx("Add media to", "Pridat medium do")} ${info("Every Add to playlist button below uses this target playlist.", "Kazde tlacitko Add to playlist pouzije tento playlist.")}</label><select id="mediaPlaylistTarget">${playlistOptions(app.mediaPlaylistTarget)}</select>`
      : `<button class="button primary" data-jump="playlists">${tx("Create playlist first", "Nejdriv vytvor playlist")}</button>`;
    return `<section class="panel builder-hero">${panelHead(tx("Media first", "Nejdriv media"), "Pick a file or stream and send it into a playlist. You do not need to know YAML or source types.", "Vyber soubor nebo stream a posli ho do playlistu. Nemusis znat YAML ani typy zdroju.")}<div class="toolbar"><input id="mediaSearch" value="${esc(app.mediaSearch)}" placeholder="${tx("Search media...", "Hledat media...")}"><select id="mediaFilter"><option value="all">${tx("All media", "Vsechna media")}</option>${["video","image","gif"].map(type => `<option ${app.mediaFilter === type ? "selected" : ""}>${type}</option>`).join("")}</select>${playlistPicker}<span class="tag">${filtered.length}/${app.state.media.length}</span></div></section>
      ${filtered.length ? `<div class="media-grid">${filtered.map(mediaCard).join("")}</div>` : empty("No matching media.", "Zadna odpovidajici media.")}`;
  }

  function mediaCard(media) {
    const canAdd = media.valid && app.state.playlists.length;
    const canPlay = media.valid && app.state.screens.length;
    return `<article class="media-card ${app.selected?.type === "media" && app.selected.id === media.id ? "selected" : ""}" data-media="${esc(media.id)}"><div class="preview">${media.thumbnail ? `<img loading="lazy" src="${esc(media.thumbnail)}" alt="">` : `<span class="signal"></span>`}<span class="tag" style="position:absolute;right:8px;top:8px">${esc(media.type)}</span></div><div class="card-body"><div class="card-title"><h3 title="${esc(media.id)}">${esc(media.name)} ${info("Select this media to inspect metadata, references and live controls.", "Vyber medium pro metadata, pouziti a zive ovladani.")}</h3><span class="status ${media.valid ? "running" : "connecting"}">${media.valid ? "ready" : "problem"}</span></div><div class="card-meta"><span>${tx("Resolution", "Rozliseni")}<b>${media.width && media.height ? `${media.width}x${media.height}` : "--"}</b></span><span>${tx("Size", "Velikost")}<b>${bytes(media.size)}</b></span><span>${tx("Used by", "Pouziva")}<b>${media.references.length}</b></span><span>${tx("Plays", "Prehrani")}<b>${media.statistics.plays}</b></span></div><div class="card-actions"><button class="button primary" data-add-media-to-playlist="${esc(media.id)}" ${canAdd ? "" : "disabled"}>${tx("Add to playlist", "Pridat do playlistu")}</button><button class="button ghost" data-media-live="${esc(media.id)}" ${canPlay ? "" : "disabled"}>${tx("Play now", "Prehrat nyni")}</button></div></div></article>`;
  }

  function renderPlaylists() {
    const creator = can("playlists") ? `<section class="panel builder-hero">${panelHead(tx("Build a playlist", "Vytvor playlist"), "Add media, choose how long it plays, then assign the playlist to a screen.", "Pridej media, nastav jak dlouho hraji a pak playlist prirad k platnu.")}<div class="toolbar"><input id="newPlaylistName" placeholder="${tx("my_playlist", "muj_playlist")}"><button class="button primary" data-create-playlist>${tx("Create playlist", "Vytvorit playlist")}</button><button class="button ghost" data-jump="media">${tx("Open media library", "Otevrit knihovnu medii")}</button></div></section>` : "";
    if (!app.selectedPlaylist) {
      const list = app.state.playlists.map(playlist => playlistCard(playlist)).join("");
      return creator + (list ? `<div class="playlist-list">${list}</div>` : `<section class="panel empty-builder"><h2>${tx("No playlists yet", "Zatim zadne playlisty")}</h2><p class="muted">${tx("Create one above, then add media from this page or from the Media Library.", "Vytvor jeden nahore a potom pridej media tady nebo z knihovny medii.")}</p></section>`);
    }
    const playlist = app.state.playlists.find(item => item.id === app.selectedPlaylist);
    if (!playlist) { app.selectedPlaylist = null; return renderPlaylists(); }
    if (app.state.screens.length && !app.playlistAssignTarget) {
      app.playlistAssignTarget = app.liveTarget || app.state.screens[0].id;
    }
    const selectedItem = playlist.items.find(item => app.selected?.type === "playlist-item" && app.selected.id === item.id);
    const addForm = can("playlists") ? `<section class="panel playlist-add-panel">${panelHead(tx("Add media", "Pridat medium"), "This writes the item directly into the playlist. Use the inspector for advanced conditions.", "Toto zapise polozku rovnou do playlistu. Pokrocile podminky jsou v inspectoru.")}<div class="field-row playlist-add-grid"><div class="field"><label>${tx("Media source", "Zdroj media")}</label><select id="playlistAddMedia">${mediaOptions()}</select></div><div class="field"><label>${tx("Item name", "Nazev polozky")}</label><input id="playlistAddItem" placeholder="${tx("optional", "volitelne")}"></div></div><div class="field-row playlist-add-grid"><div class="field"><label>${tx("Duration", "Delka")}</label><input id="playlistAddDuration" value="30s"></div><div class="field"><label>${tx("Weight", "Vaha")}</label><input id="playlistAddWeight" type="number" min="1" value="1"></div></div><button class="button primary" data-add-playlist-item="${esc(playlist.id)}">${tx("Add item", "Pridat polozku")}</button></section>` : "";
    const assignBox = app.state.screens.length ? `<section class="panel playlist-action-panel">${panelHead(tx("Play this playlist", "Prehrat tento playlist"), "Choose a screen and start the playlist immediately.", "Vyber platno a hned spust playlist.")}<div class="field"><label>${tx("Screen", "Platno")}</label><select id="playlistAssignTarget">${screenOptions(app.playlistAssignTarget)}</select></div><button class="button success" data-assign-selected-playlist="${esc(playlist.id)}">${tx("Assign and play", "Priradit a prehrat")}</button></section>` : "";
    const duplicateBox = can("playlists") ? `<section class="panel playlist-action-panel">${panelHead(tx("Copy or delete", "Kopirovat nebo smazat"), "Duplicate keeps the same items. Delete also clears this playlist from assigned screens.", "Duplikace zachova polozky. Smazani ho odebere i z prirazenych platen.")}<div class="field"><label>${tx("New playlist name", "Novy nazev playlistu")}</label><input id="duplicatePlaylistName" placeholder="${esc(playlist.id)}_copy"></div><div class="button-row"><button class="button ghost" data-duplicate-playlist="${esc(playlist.id)}">${tx("Duplicate", "Duplikovat")}</button><button class="button danger" data-delete-playlist="${esc(playlist.id)}">${tx("Delete playlist", "Smazat playlist")}</button></div></section>` : "";
    const items = playlist.items.length
      ? `<div class="playlist-items">${playlist.items.map((item, index) => playlistItemCard(playlist, item, index)).join("")}</div>`
      : `<div class="empty-builder"><h2>${tx("This playlist is empty", "Tento playlist je prazdny")}</h2><p class="muted">${tx("Add the first media item with the form above, or open the Media Library and press Add to playlist.", "Pridej prvni medium formularem nahore, nebo otevri knihovnu a zmackni Add to playlist.")}</p><button class="button primary" data-jump="media">${tx("Open media library", "Otevrit knihovnu medii")}</button></div>`;
    return `<div class="toolbar"><button class="button ghost" data-back-playlists>${tx("All playlists", "Vsechny playlisty")}</button><span class="tag ${playlistStatus(playlist)}">${playlistStatusText(playlist)}</span><button class="button ghost" data-simulate="${esc(playlist.id)}">${tx("Simulate 1,000 picks", "Simulovat 1 000 vyberu")}</button></div>
      <div class="playlist-workspace"><main class="playlist-builder"><section class="panel playlist-header-panel">${panelHead(playlist.id, "A playlist is a normal automatic loop for one or more screens.", "Playlist je normalni automaticka smycka pro jedno nebo vice platen.", `<span class="tag">${playlist.items.length} ${tx("items", "polozek")}</span><span class="tag">${playlist.assignedScreens || 0} ${tx("screens", "platen")}</span>`)}<p class="muted">${tx("Start with media, then use weights only when you want some items to appear more often.", "Zacni medii a vahy res az kdyz chces, aby se neco ukazovalo casteji.")}</p></section>${addForm}<section class="panel">${panelHead(tx("Timeline", "Timeline"), "Click an item to edit advanced settings. Delete is always visible.", "Klikni na polozku pro pokrocile upravy. Smazani je vzdy videt.")}${items}</section></main><aside class="playlist-side">${assignBox}${duplicateBox}<section class="panel playlist-action-panel">${panelHead(tx("Selected item", "Vybrana polozka"), "Advanced item editing still lives in the inspector, so the main page stays simple.", "Pokrocile upravy zustavaji v inspectoru, aby hlavni stranka byla jednoducha.")}${selectedItem ? `<div class="property"><span>${tx("Item", "Polozka")}</span><b>${esc(selectedItem.id)}</b></div><div class="property"><span>${tx("Source", "Zdroj")}</span><b>${esc(selectedItem.value)}</b></div><div class="property"><span>${tx("Duration", "Delka")}</span><b>${duration(selectedItem.duration)}</b></div><button class="button ghost" data-delete-playlist-item="${esc(selectedItem.id)}" data-playlist-id="${esc(playlist.id)}">${tx("Delete item", "Smazat polozku")}</button>` : `<p class="muted">${tx("Select an item in the timeline to inspect it here.", "Vyber polozku v timeline a tady uvidis detail.")}</p>`}</section><section class="panel playlist-action-panel">${panelHead(tx("Rotation analysis", "Analyza rotace"), "Estimated chance among currently usable items.", "Odhad sance mezi pouzitelnymi polozkami.")}${probabilityBars(playlist.items)}</section></aside></div>`;
  }

  function playlistCard(playlist) {
    return `<article class="playlist-card panel" data-playlist="${esc(playlist.id)}"><div class="playlist-card-top"><div><span class="eyebrow">${tx("PLAYLIST", "PLAYLIST")}</span><h2>${esc(playlist.id)}</h2></div><span class="status ${playlistStatus(playlist)}">${playlistStatusText(playlist)}</span></div><p class="muted">${playlist.items.length ? tx("Ready to edit or assign to a screen.", "Pripraveno k uprave nebo prirazeni.") : tx("Empty. Add the first media item next.", "Prazdne. Pridej prvni medium.")}</p><div class="playlist-card-stats"><span>${playlist.items.length}<small>${tx("items", "polozek")}</small></span><span>${playlist.assignedScreens || 0}<small>${tx("screens", "platen")}</small></span></div>${probabilityBars(playlist.items)}<div class="card-actions"><button class="button primary" data-open-playlist="${esc(playlist.id)}">${tx("Open builder", "Otevrit builder")}</button><button class="button danger" data-delete-playlist="${esc(playlist.id)}">${tx("Delete", "Smazat")}</button></div></article>`;
  }

  function playlistItemCard(playlist, item, index) {
    const media = app.state.media.find(entry => entry.id === item.value);
    const selected = app.selected?.type === "playlist-item" && app.selected.id === item.id;
    return `<article class="playlist-item-card ${selected ? "selected" : ""} ${item.enabled ? "" : "disabled"}" data-playlist-item="${esc(item.id)}"><div class="playlist-item-preview">${media?.thumbnail ? `<img loading="lazy" src="${esc(media.thumbnail)}" alt="">` : `<span class="signal"></span>`}</div><div class="playlist-item-main"><span class="step-number">${String(index + 1).padStart(2, "0")}</span><h3>${esc(item.id)}</h3><div class="item-source"><span class="tag">${esc(item.type)}</span><code>${esc(item.value)}</code></div><div class="weight-bar"><span style="width:${Math.min(100, item.probability)}%"></span></div></div><div class="playlist-item-meta"><div class="property"><span>${tx("Duration", "Delka")}</span><b>${duration(item.duration)}</b></div><div class="property"><span>${tx("Weight", "Vaha")}</span><b>${item.weight}</b></div><div class="property"><span>${tx("Chance", "Sance")}</span><b>${fmt(item.probability)}%</b></div><button class="button ghost" data-delete-playlist-item="${esc(item.id)}" data-playlist-id="${esc(playlist.id)}">${tx("Delete", "Smazat")}</button></div></article>`;
  }

  function playlistStatus(playlist) {
    if (!playlist.items.length) return "empty";
    return playlist.valid ? "running" : "connecting";
  }

  function playlistStatusText(playlist) {
    if (!playlist.items.length) return tx("empty", "prazdny");
    return playlist.valid ? tx("ready", "pripraven") : tx("problem", "problem");
  }
  function probabilityBars(items) {
    if (!items.length) return empty("No usable items.", "Žádné použitelné položky.");
    return items.map(item => `<div class="property"><span>${esc(item.id)} ${info("Estimated share among currently eligible weighted items.", "Odhadovaný podíl mezi aktuálně způsobilými váženými položkami.")}</span><b>${fmt(item.probability)}%</b></div><div class="weight-bar"><span style="width:${Math.min(100,item.probability)}%"></span></div>`).join("");
  }

  function renderEvents() {
    const events = app.state.events;
    const screens = app.state.screens;
    const selectedTarget = app.liveTarget || screens[0]?.id || "";
    const creator = can("events") ? `<section class="panel builder-hero event-hero">${panelHead(tx("Build an event", "Vytvor event"), "Events are temporary takeovers. Build a short timeline, pick a screen, then start it when you need it.", "Eventy jsou docasne prevzeti. Postav kratkou osu, vyber platno a spust ji, kdyz ji potrebujes.")}<div class="toolbar"><input id="newEventName" placeholder="${tx("restart_warning", "restart_warning")}"><button class="button primary" data-create-event>${tx("Create event", "Vytvorit event")}</button><button class="button ghost" data-jump="media">${tx("Open media library", "Otevrit media")}</button></div></section>` : "";
    if (!app.selectedEvent) {
      return creator + (events.length ? `<div class="playlist-list event-list">${events.map(event => `<article class="playlist-card event-card panel" data-event="${esc(event.id)}"><div class="playlist-card-top"><div><span class="tag event">${tx("event", "event")}</span><h2>${esc(event.id)}</h2></div><span class="status ${eventStatus(event)}">${eventStatusText(event)}</span></div><div class="playlist-card-stats"><span>${event.steps.length}<small>${tx("steps", "kroku")}</small></span><span>${event.valid ? "yes" : "no"}<small>${tx("ready", "ready")}</small></span></div><div class="timeline event-mini-timeline">${event.steps.slice(0, 5).map(step => `<span class="tag">${esc(step.type)}</span>`).join("") || `<span class="muted">${tx("No steps yet", "Zatim zadne kroky")}</span>`}</div><div class="card-actions"><button class="button primary" data-open-event="${esc(event.id)}">${tx("Open builder", "Otevrit builder")}</button><button class="button ghost" data-delete-event="${esc(event.id)}">${tx("Delete", "Smazat")}</button></div></article>`).join("")}</div>` : `<section class="empty-builder">${empty("No events yet. Create one, add a few steps, then start it on a screen.", "Zatim nemas zadny event. Vytvor ho, pridej par kroku a potom ho pust na platno.")}</section>`);
    }
    const event = app.state.events.find(item => item.id === app.selectedEvent);
    if (!event) { app.selectedEvent = null; return renderEvents(); }
    const stepBuilder = can("events") ? `<section class="panel playlist-add-panel event-add-panel">${panelHead(tx("Add a step", "Pridat krok"), "A step is one thing the event does: play media, show text, run a countdown, or wait.", "Krok je jedna vec, kterou event udela: prehraje medium, ukaze text, odpocet nebo pocka.")}<div class="playlist-add-grid field-row"><div class="field"><label>${tx("Step type", "Typ kroku")}</label><select id="eventStepType"><option value="media">${tx("Media from library", "Medium z knihovny")}</option><option value="text">${tx("Text card", "Textova karta")}</option><option value="countdown">${tx("Countdown", "Odpocet")}</option><option value="wait-manual">${tx("Wait / hold", "Cekani / hold")}</option></select></div><div class="field"><label>${tx("Media", "Medium")}</label><select id="eventAddMedia">${mediaOptions()}</select></div><div class="field"><label>${tx("Step name", "Nazev kroku")}</label><input id="eventAddStep" placeholder="${tx("intro", "intro")}"></div><div class="field"><label>${tx("Duration", "Delka")}</label><input id="eventAddDuration" value="30s"></div></div><div class="field"><label>${tx("Text", "Text")}</label><input id="eventAddText" placeholder="${tx("Shown for text, countdown and wait steps", "Pouzije se pro text, odpocet a cekani")}"></div><button class="button primary" data-add-event-step="${esc(event.id)}">${tx("Add step", "Pridat krok")}</button></section>` : "";
    return `<div class="toolbar"><button class="button ghost" data-back-events>${tx("All events", "Vsechny eventy")}</button><span class="tag event">${esc(event.id)}</span><span class="status ${eventStatus(event)}">${eventStatusText(event)}</span></div><div class="playlist-workspace event-workspace"><div class="playlist-builder"><section class="panel playlist-header-panel event-header-panel">${panelHead(event.id, "Timeline order is the exact order players will see. Click a step to inspect advanced settings.", "Poradi v timeline je presne poradi, ktere hraci uvidi. Klikni na krok pro pokrocile nastaveni.", `<span class="tag">${event.steps.length} ${tx("steps", "kroku")}</span>`)}<div class="event-timeline">${event.steps.length ? event.steps.map((step, index) => renderEventStepCard(event, step, index)).join("") : `<div class="empty-builder">${empty("No steps yet. Add media or a text card on the right.", "Zatim tu nejsou kroky. Pridej medium nebo text vpravo.")}</div>`}</div></section></div><aside class="playlist-side">${stepBuilder}<section class="panel playlist-action-panel">${panelHead(tx("Run this event", "Spustit event"), "Starting an event temporarily overrides the target screen, then returns it to normal playback.", "Spusteni eventu docasne prebije cilove platno a potom ho vrati do normalniho prehravani.")}<div class="field"><label>${tx("Target screen", "Cilove platno")}</label><select id="eventTarget">${screenOptions(selectedTarget)}</select></div><div class="button-row"><button class="button success" data-start-event="${esc(event.id)}" ${event.valid ? "" : "disabled"}>${tx("Start event", "Spustit event")}</button><button class="button ghost" data-stop-event="${esc(event.id)}">${tx("Stop event", "Zastavit event")}</button></div></section><section class="panel playlist-action-panel">${panelHead(tx("Manage event", "Sprava eventu"), "Duplicate before experimenting, delete when the event is no longer needed.", "Duplikuj pred experimentem, smaz kdyz event uz nepotrebujes.")}<div class="field"><label>${tx("Duplicate as", "Duplikovat jako")}</label><input id="duplicateEventName" placeholder="${esc(event.id)}_copy"></div><div class="button-row"><button class="button ghost" data-duplicate-event="${esc(event.id)}">${tx("Duplicate", "Duplikovat")}</button><button class="button danger" data-delete-event="${esc(event.id)}">${tx("Delete event", "Smazat event")}</button></div></section></aside></div>`;
  }

  function renderEventStepCard(event, step, index) {
    const media = app.state.media.find(item => item.id === step.value || item.name === step.value);
    const selected = app.selected?.type === "event-step" && app.selected.id === step.id;
    return `<article class="playlist-item-card event-step-card ${selected ? "selected" : ""} ${step.enabled ? "" : "disabled"}" data-event-step="${esc(step.id)}"><div class="playlist-item-preview">${media?.thumbnail ? `<img loading="lazy" src="${esc(media.thumbnail)}" alt="">` : `<span class="signal"></span>`}</div><div class="playlist-item-main"><span class="step-number">${String(index + 1).padStart(2, "0")}</span><h3>${esc(step.id)}</h3><div class="item-source"><span class="tag event">${esc(step.type)}</span><code>${esc(step.value || tx("no value", "bez hodnoty"))}</code></div><p class="muted">${esc(step.conditions || tx("No extra conditions", "Zadne extra podminky"))}</p></div><div class="playlist-item-meta"><div class="property"><span>${tx("Duration", "Delka")}</span><b>${duration(step.duration)}</b></div><div class="property"><span>${tx("Enabled", "Zapnuto")}</span><b>${step.enabled ? "yes" : "no"}</b></div><button class="button ghost" data-delete-event-step="${esc(step.id)}" data-event-id="${esc(event.id)}">${tx("Delete", "Smazat")}</button></div></article>`;
  }

  function eventStatus(event) {
    if (!event.steps.length) return "empty";
    return event.valid ? "running" : "connecting";
  }

  function eventStatusText(event) {
    if (!event.steps.length) return tx("empty", "prazdny");
    return event.valid ? tx("ready", "pripraven") : tx("problem", "problem");
  }

  function renderAutomations() {
    const schedules = app.state.schedules;
    const canEdit = can("automations") || can("schedules");
    const firstTarget = app.liveTarget || app.state.screens[0]?.id || "";
    const creator = canEdit ? `<section class="panel builder-hero automation-hero">${panelHead(tx("Build an automation", "Vytvor automatizaci"), "A rule says WHEN something should happen and THEN what LuigiScreen should do.", "Pravidlo rika KDY se ma neco stat a POTOM co ma LuigiScreen udelat.")}<div class="automation-create-grid"><input id="newScheduleName" placeholder="${tx("evening_show", "vecerni_show")}"><input id="newScheduleTime" type="time" value="20:00"><select id="newScheduleTarget">${targetOptions(firstTarget)}</select><select id="newScheduleAction">${automationActionOptions("event")}</select><select id="newScheduleValue">${automationValueOptions("event", "")}</select><button class="button primary" data-create-schedule>${tx("Create rule", "Vytvorit pravidlo")}</button></div></section>` : "";
    const groupCreator = can("groups") ? `<section class="panel automation-groups-panel">${panelHead(tx("Screen groups", "Skupiny platen"), "A group lets one automation target multiple physical displays.", "Skupina umozni jednim pravidlem cilit na vice fyzickych platen.")}<div class="toolbar"><input id="newGroupName" placeholder="spawn_screens"><input id="newGroupScreens" placeholder="main,lobby"><button class="button primary" data-create-group>${tx("Create group", "Vytvorit skupinu")}</button></div><div class="quick-sources">${app.state.groups.map(group=>`<span class="tag">${esc(group.id)}: ${esc(group.screens.join(", "))}</span>`).join("") || `<span class="muted">${tx("No groups yet", "Zatim zadne skupiny")}</span>`}</div></section>` : "";
    if (!app.selectedAutomation) {
      return creator + `<section class="panel">${panelHead(tx("Automation rules", "Automaticka pravidla"), "Readable WHEN / THEN cards. Open one to edit, duplicate, delete or run it immediately.", "Citelne WHEN / THEN karty. Otevri pravidlo pro upravu, duplikaci, smazani nebo okamzite spusteni.")}<div class="playlist-list automation-list">${schedules.length ? schedules.map(automationCard).join("") : `<div class="empty-builder">${empty("No automation rules yet. Create one above.", "Zatim nemas zadna automaticka pravidla. Vytvor jedno nahore.")}</div>`}</div></section>${groupCreator}`;
    }
    const schedule = schedules.find(item => item.id === app.selectedAutomation);
    if (!schedule) { app.selectedAutomation = null; return renderAutomations(); }
    const conflicts = schedule.conflicts || [];
    return `<div class="toolbar"><button class="button ghost" data-back-automations>${tx("All automations", "Vsechny automatizace")}</button><span class="tag">${esc(schedule.id)}</span><span class="status ${schedule.enabled ? "running" : "empty"}">${schedule.enabled ? tx("enabled", "zapnuto") : tx("disabled", "vypnuto")}</span></div><div class="playlist-workspace automation-workspace"><div class="playlist-builder"><section class="panel automation-rule-canvas">${panelHead(schedule.id, "This is the rule in plain language.", "Toto je pravidlo normalni reci.", `<span class="tag">${esc(schedule.nextLabel)}</span>`)}${automationRuleVisual(schedule)}${conflicts.length ? `<div class="alert warning"><span class="alert-marker"></span><div><strong>${tx("Conflict detected", "Nalezen konflikt")}</strong><small>${esc(conflicts.join(", "))}</small></div></div>` : ""}</section></div><aside class="playlist-side"><section class="panel playlist-add-panel automation-edit-panel">${panelHead(tx("Edit rule", "Upravit pravidlo"), "Save writes this automation directly. No YAML editing needed.", "Save ulozi automatizaci primo. Bez YAML editace.")}<div class="field"><label>${tx("Enabled", "Zapnuto")}</label><select id="automationEnabled"><option value="true" ${schedule.enabled ? "selected" : ""}>${tx("Enabled", "Zapnuto")}</option><option value="false" ${!schedule.enabled ? "selected" : ""}>${tx("Disabled", "Vypnuto")}</option></select></div><div class="field"><label>WHEN</label><input id="automationTime" type="time" value="${esc(schedule.time)}"></div><div class="field"><label>${tx("Target", "Cil")}</label><select id="automationTarget">${targetOptions(schedule.target)}</select></div><div class="field"><label>THEN</label><select id="automationAction">${automationActionOptions(schedule.action)}</select></div><div class="field"><label>${tx("Value", "Hodnota")}</label><select id="automationValue">${automationValueOptions(schedule.action, schedule.value)}</select></div><div class="field-row"><div class="field"><label>${tx("Priority", "Priorita")}</label><input id="automationPriority" type="number" min="0" value="${schedule.priority}"></div><div class="field"><label>${tx("Conflict", "Konflikt")}</label><select id="automationConflict"><option value="priority" ${schedule.conflict === "priority" ? "selected" : ""}>priority</option><option value="allow" ${schedule.conflict === "allow" ? "selected" : ""}>allow</option></select></div></div><button class="button primary" data-save-automation="${esc(schedule.id)}">${tx("Save rule", "Ulozit pravidlo")}</button></section><section class="panel playlist-action-panel">${panelHead(tx("Operate", "Ovládat"), "Run now ignores the clock and executes this rule immediately.", "Run now ignoruje cas a spusti pravidlo hned.")}<div class="button-row"><button class="button success" data-run-automation="${esc(schedule.id)}">${tx("Run now", "Spustit hned")}</button><button class="button ghost" data-jump="schedule">${tx("Open calendar", "Otevrit kalendar")}</button></div></section><section class="panel playlist-action-panel">${panelHead(tx("Manage rule", "Sprava pravidla"), "Duplicate before experimenting, delete when the rule is no longer needed.", "Duplikuj pred experimentem, smaz kdyz pravidlo uz nepotrebujes.")}<div class="field"><label>${tx("Duplicate as", "Duplikovat jako")}</label><input id="duplicateAutomationName" placeholder="${esc(schedule.id)}_copy"></div><div class="button-row"><button class="button ghost" data-duplicate-automation="${esc(schedule.id)}">${tx("Duplicate", "Duplikovat")}</button><button class="button danger" data-delete-automation="${esc(schedule.id)}">${tx("Delete rule", "Smazat pravidlo")}</button></div></section></aside></div>${groupCreator}`;
  }

  function automationCard(schedule) {
    const conflicts = schedule.conflicts || [];
    return `<article class="playlist-card automation-rule-card panel ${schedule.enabled ? "" : "disabled"}" data-automation="${esc(schedule.id)}"><div class="playlist-card-top"><div><span class="tag">${tx("automation", "automatizace")}</span><h2>${esc(schedule.id)}</h2></div><span class="status ${schedule.enabled ? "running" : "empty"}">${schedule.enabled ? tx("enabled", "zapnuto") : tx("disabled", "vypnuto")}</span></div>${automationRuleVisual(schedule)}<div class="playlist-card-stats"><span>${schedule.priority}<small>${tx("priority", "priorita")}</small></span><span>${conflicts.length}<small>${tx("conflicts", "konflikty")}</small></span></div><div class="card-actions"><button class="button primary" data-open-automation="${esc(schedule.id)}">${tx("Open builder", "Otevrit builder")}</button><button class="button success" data-run-automation="${esc(schedule.id)}">${tx("Run now", "Spustit hned")}</button><button class="button ghost" data-delete-automation="${esc(schedule.id)}">${tx("Delete", "Smazat")}</button></div></article>`;
  }

  function automationRuleVisual(schedule) {
    const thenValue = schedule.value ? ` ${schedule.value}` : "";
    return `<div class="automation-rule-flow"><div class="condition"><span><b>WHEN</b><br>${esc(schedule.nextLabel || schedule.time)}</span></div><div class="condition"><span><b>IF</b><br>${esc((schedule.days || []).join(", "))}</span></div><div class="condition"><span><b>THEN</b><br>${esc(schedule.action + thenValue)} on ${esc(schedule.target)}</span></div></div><small class="muted">${tx("Conflict policy", "Reseni konfliktu")}: ${esc(schedule.conflict)} · ${tx("server time", "cas serveru")} ${esc(schedule.time)}</small>`;
  }

  function automationActionOptions(selected) {
    return ["event", "playlist", "start", "stop", "return"].map(action => `<option value="${action}" ${action === selected ? "selected" : ""}>${action}</option>`).join("");
  }

  function automationValueOptions(action, selected = "") {
    if (action === "event") {
      const options = eventOptions(selected);
      return options || `<option value="">${tx("No events", "Zadne eventy")}</option>`;
    }
    if (action === "playlist") {
      const options = playlistOptions(selected);
      return options || `<option value="">${tx("No playlists", "Zadne playlisty")}</option>`;
    }
    return `<option value="">${tx("No value needed", "Hodnota neni potreba")}</option>`;
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
    return renderAutomations();
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
    $("#app").classList.toggle("inspector-active", Boolean(app.selected));
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
    $$('[data-jump]').forEach(button=>button.onclick=()=>{app.view=button.dataset.jump;app.detailScreen=null;app.selected=null;app.selectedAutomation=null;render();});
    $$('[data-screen-tab]').forEach(button=>button.onclick=()=>{app.screenDetailTab=button.dataset.screenTab;render(false);});
    $$('[data-screen]').forEach(element=>element.onclick=()=>{app.selected={type:"screen",id:element.dataset.screen};app.detailScreen=element.dataset.screen;render();openInspector();});
    $$('[data-media]').forEach(element=>element.onclick=()=>{app.selected={type:"media",id:element.dataset.media};renderInspector();openInspector();$$('[data-media]').forEach(card=>card.classList.toggle("selected",card===element));});
    $$('[data-layout]').forEach(button=>button.onclick=()=>{app.screenLayout=button.dataset.layout;render();});
    $('[data-back-screens]')?.addEventListener("click",()=>{app.detailScreen=null;render();});
    $$('[data-playlist]').forEach(element=>element.onclick=event=>{if(event.target.closest("button,input,select,a"))return;app.selectedPlaylist=element.dataset.playlist;app.selected=null;render();});
    $$('[data-open-playlist]').forEach(button=>button.onclick=event=>{event.stopPropagation();app.selectedPlaylist=button.dataset.openPlaylist;app.selected=null;render();});
    $('[data-back-playlists]')?.addEventListener("click",()=>{app.selectedPlaylist=null;app.selected=null;render();});
    $$('[data-playlist-item]').forEach(element=>element.onclick=event=>{if(event.target.closest("button,input,select,a"))return;app.selected={type:"playlist-item",id:element.dataset.playlistItem};render();renderInspector();openInspector();});
    $$('[data-event]').forEach(element=>element.onclick=event=>{if(event.target.closest("button,input,select,a"))return;app.selectedEvent=element.dataset.event;app.selected=null;render();});
    $$('[data-open-event]').forEach(button=>button.onclick=event=>{event.stopPropagation();app.selectedEvent=button.dataset.openEvent;app.selected=null;render();});
    $('[data-back-events]')?.addEventListener("click",()=>{app.selectedEvent=null;app.selected=null;render();});
    $$('[data-event-step]').forEach(element=>element.onclick=event=>{if(event.target.closest("button,input,select,a"))return;app.selected={type:"event-step",id:element.dataset.eventStep};render();renderInspector();openInspector();});
    $$('[data-automation]').forEach(element=>element.onclick=event=>{if(event.target.closest("button,input,select,a"))return;app.selectedAutomation=element.dataset.automation;app.selected=null;render();});
    $$('[data-open-automation]').forEach(button=>button.onclick=event=>{event.stopPropagation();app.selectedAutomation=button.dataset.openAutomation;app.selected=null;render();});
    $('[data-back-automations]')?.addEventListener("click",()=>{app.selectedAutomation=null;app.selected=null;render();});
    $$('[data-start-event]').forEach(button=>button.onclick=event=>action("event.play",{screen:$("#eventTarget")?.value || app.liveTarget || "",event:event.currentTarget.dataset.startEvent},true));
    $$('[data-stop-event]').forEach(button=>button.onclick=event=>action("event.stop",{screen:$("#eventTarget")?.value || app.liveTarget || "",event:event.currentTarget.dataset.stopEvent},true));
    $('[data-simulate]')?.addEventListener("click",()=>toast(tx("Probabilities shown are normalized from 1,000 analysis selections.","Zobrazené pravděpodobnosti jsou normalizované z 1 000 analytických výběrů.")));
    $('[data-return-auto]')?.addEventListener("click",()=>{const target=$("#liveTarget").value;action(screenById(target)?"playback.return":"group.return",screenById(target)?{screen:target}:{group:target},true);});
    $('[data-take-live]')?.addEventListener("click",()=>action("media.play",{screen:$("#liveTarget").value,media:app.previewMedia,duration:30000},true));
    $('[data-cue-media]') && $$('[data-cue-media]').forEach(button=>button.onclick=()=>{app.previewMedia=button.dataset.cueMedia;render();});
    $("#previewSource")?.addEventListener("change",event=>{app.previewMedia=event.target.value;render();});
    $("#liveTarget")?.addEventListener("change",event=>{app.liveTarget=event.target.value;render();});
    $("#eventTarget")?.addEventListener("change",event=>{app.liveTarget=event.target.value;render(false);});
    $("#newScheduleAction")?.addEventListener("change",event=>{$("#newScheduleValue").innerHTML=automationValueOptions(event.target.value, "");});
    $("#automationAction")?.addEventListener("change",event=>{$("#automationValue").innerHTML=automationValueOptions(event.target.value, "");});
    $("#mediaPlaylistTarget")?.addEventListener("change",event=>{app.mediaPlaylistTarget=event.target.value;render(false);});
    $("#playlistAssignTarget")?.addEventListener("change",event=>{app.playlistAssignTarget=event.target.value;render(false);});
    $("#mediaSearch")?.addEventListener("input",event=>{app.mediaSearch=event.target.value;window.clearTimeout(app.searchTimer);app.searchTimer=setTimeout(()=>render(false),150);});
    $("#mediaFilter")?.addEventListener("change",event=>{app.mediaFilter=event.target.value;render();});
    $$('[data-open]').forEach(element=>element.onclick=()=>{app.view=element.dataset.open;if(element.dataset.id&&app.view==="screens"){app.detailScreen=element.dataset.id;app.selected={type:"screen",id:element.dataset.id};}render();});
    $("#diagnosticScreen")?.addEventListener("change",event=>{app.liveTarget=event.target.value;app.selected={type:"screen",id:event.target.value};render();});
    $('[data-create-playlist]')?.addEventListener("click",()=>createNamed("playlist.create",$("#newPlaylistName").value));
    $('[data-create-event]')?.addEventListener("click",()=>createNamed("event.create",$("#newEventName").value));
    $('[data-create-group]')?.addEventListener("click",()=>action("group.create",{name:$("#newGroupName").value,screens:$("#newGroupScreens").value}));
    $('[data-create-schedule]')?.addEventListener("click",()=>action("schedule.create",{name:$("#newScheduleName").value,time:$("#newScheduleTime").value,target:$("#newScheduleTarget").value,scheduleAction:$("#newScheduleAction").value,value:$("#newScheduleValue").value}));
    $('[data-add-playlist-item]')?.addEventListener("click",event=>action("playlist.item.add",{playlist:event.currentTarget.dataset.addPlaylistItem,item:$("#playlistAddItem")?.value || "",media:$("#playlistAddMedia")?.value || "",duration:$("#playlistAddDuration")?.value || "30s",weight:$("#playlistAddWeight")?.value || "1"}));
    $('[data-add-event-step]')?.addEventListener("click",event=>action("event.step.add",{event:event.currentTarget.dataset.addEventStep,step:$("#eventAddStep")?.value || "",stepType:$("#eventStepType")?.value || "media",media:$("#eventAddMedia")?.value || "",text:$("#eventAddText")?.value || "",duration:$("#eventAddDuration")?.value || "30s"}));
    bindActions($("#workspaceContent"));
  }

  function createNamed(actionName,name){if(!name?.trim()){toast(tx("Enter a valid name first.","Nejdřív zadej platný název."),true);return;}action(actionName,{name:name.trim()});}

  function bindActions(root){
    root.querySelectorAll('[data-action]').forEach(button=>button.onclick=()=>action(button.dataset.action,{screen:button.dataset.screenId},["screen.stop","playback.skip"].includes(button.dataset.action)));
    root.querySelectorAll('[data-assign-playlist]').forEach(button=>button.onclick=()=>action("playlist.assign",{screen:button.dataset.screenId,playlist:$("#detailPlaylist")?.value || ""},true));
    root.querySelectorAll('[data-assign-selected-playlist]').forEach(button=>button.onclick=()=>action("playlist.assign",{screen:$("#playlistAssignTarget")?.value || "",playlist:button.dataset.assignSelectedPlaylist},true));
    root.querySelectorAll('[data-clear-playlist]').forEach(button=>button.onclick=()=>action("playlist.clear",{screen:button.dataset.screenId},true));
    root.querySelectorAll('[data-start-event-detail]').forEach(button=>button.onclick=()=>action("event.play",{screen:button.dataset.screenId,event:$("#detailEvent")?.value || ""},true));
    root.querySelectorAll('[data-stop-event-detail]').forEach(button=>button.onclick=()=>action("event.stop",{screen:button.dataset.screenId},true));
    root.querySelectorAll('[data-stage]').forEach(button=>button.onclick=()=>stage(button.dataset.stage,root));
    root.querySelectorAll('[data-publish]').forEach(button=>button.onclick=publish);
    root.querySelectorAll('[data-discard]').forEach(button=>button.onclick=discard);
    root.querySelectorAll('[data-media-queue]').forEach(button=>button.onclick=event=>{event.stopPropagation();action("media.queue",{screen:selectedLiveTarget(),media:button.dataset.mediaQueue,duration:30000});});
    root.querySelectorAll('[data-media-live]').forEach(button=>button.onclick=event=>{event.stopPropagation();action("media.play",{screen:selectedLiveTarget(),media:button.dataset.mediaLive,duration:30000},true);});
    root.querySelectorAll('[data-screen-play-media]').forEach(button=>button.onclick=()=>action("media.play",{screen:button.dataset.screenId,media:$("#screenManualMedia")?.value || "",duration:30000},true));
    root.querySelectorAll('[data-add-media-to-playlist]').forEach(button=>button.onclick=event=>{event.stopPropagation();action("playlist.item.add",{playlist:$("#mediaPlaylistTarget")?.value || app.mediaPlaylistTarget || "",media:button.dataset.addMediaToPlaylist,duration:"30s",weight:"1"});});
    root.querySelectorAll('[data-delete-playlist]').forEach(button=>button.onclick=event=>{event.stopPropagation();action("playlist.delete",{playlist:button.dataset.deletePlaylist},true);});
    root.querySelectorAll('[data-duplicate-playlist]').forEach(button=>button.onclick=event=>{event.stopPropagation();const name=$("#duplicatePlaylistName")?.value?.trim() || "";if(!name){toast(tx("Enter a new playlist name first.","Nejdriv zadej novy nazev playlistu."),true);return;}action("playlist.duplicate",{playlist:button.dataset.duplicatePlaylist,name});});
    root.querySelectorAll('[data-delete-playlist-item]').forEach(button=>button.onclick=event=>{event.stopPropagation();action("playlist.item.delete",{playlist:button.dataset.playlistId,item:button.dataset.deletePlaylistItem},true);});
    root.querySelectorAll('[data-delete-event]').forEach(button=>button.onclick=event=>{event.stopPropagation();action("event.delete",{event:button.dataset.deleteEvent},true);});
    root.querySelectorAll('[data-duplicate-event]').forEach(button=>button.onclick=event=>{event.stopPropagation();const name=$("#duplicateEventName")?.value?.trim() || "";if(!name){toast(tx("Enter a new event name first.","Nejdriv zadej novy nazev eventu."),true);return;}action("event.duplicate",{event:button.dataset.duplicateEvent,name});});
    root.querySelectorAll('[data-delete-event-step]').forEach(button=>button.onclick=event=>{event.stopPropagation();action("event.step.delete",{event:button.dataset.eventId,step:button.dataset.deleteEventStep},true);});
    root.querySelectorAll('[data-save-automation]').forEach(button=>button.onclick=event=>{event.stopPropagation();action("schedule.update",{schedule:button.dataset.saveAutomation,time:$("#automationTime")?.value || "20:00",target:$("#automationTarget")?.value || "",scheduleAction:$("#automationAction")?.value || "event",value:$("#automationValue")?.value || "",enabled:$("#automationEnabled")?.value || "true",priority:$("#automationPriority")?.value || "50",conflict:$("#automationConflict")?.value || "priority"});});
    root.querySelectorAll('[data-run-automation]').forEach(button=>button.onclick=event=>{event.stopPropagation();action("schedule.run",{schedule:button.dataset.runAutomation},true);});
    root.querySelectorAll('[data-delete-automation]').forEach(button=>button.onclick=event=>{event.stopPropagation();action("schedule.delete",{schedule:button.dataset.deleteAutomation},true);});
    root.querySelectorAll('[data-duplicate-automation]').forEach(button=>button.onclick=event=>{event.stopPropagation();const name=$("#duplicateAutomationName")?.value?.trim() || "";if(!name){toast(tx("Enter a new rule name first.","Nejdriv zadej novy nazev pravidla."),true);return;}action("schedule.duplicate",{schedule:button.dataset.duplicateAutomation,name});});
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
  function mediaOptions(selected=""){const options=app.state.media.filter(item=>item.valid).map(item=>`<option value="${esc(item.id)}" ${item.id===selected?"selected":""}>${esc(item.id)}</option>`).join("");return options||`<option value="">${tx("No valid media", "Zadna platna media")}</option>`;}
  function selectedLiveTarget(){return $("#mediaTarget")?.value || app.liveTarget || app.state.screens[0]?.id || "";}
  function targetScreen(id){const direct=screenById(id);if(direct)return direct;const group=app.state?.groups?.find(item=>item.id===id);return group?.screens?.map(screenById).find(Boolean);}
  function decorateHelp(root){
    root.querySelectorAll('.help-copy').forEach(copy=>{const target=copy.parentElement;attachHelp(target,copy.dataset.help);copy.remove();});
    root.querySelectorAll('[data-help]').forEach(element=>{attachHelp(element,element.dataset.help);element.removeAttribute("data-help");});
    root.querySelectorAll('.property > span:first-child, .field > label, .card-meta > span, th').forEach(element=>{if(element.title)return;const label=element.childNodes[0]?.textContent?.trim()||element.textContent.trim();attachHelp(element,fallbackHelp(label));});
  }
  function attachHelp(element,help){if(!element||!help)return;element.title=help;element.setAttribute("aria-description",help);element.classList.add("has-help");}
  function fallbackHelp(label){const key=label.toLowerCase();const explanations={state:["Current runtime state reported by the plugin.","Aktuální provozní stav hlášený pluginem."],content:["Media or logical step currently controlling the display.","Médium nebo krok, který právě řídí plátno."],controller:["The playlist, event or direct source responsible for playback.","Playlist, event nebo přímý zdroj odpovědný za přehrávání."],error:["Last sanitized problem reported by this subsystem.","Poslední očištěný problém hlášený touto částí."],type:["The typed source or operation category.","Typ zdroje nebo kategorie operace."],resolution:["Pixel width and height detected from the source.","Šířka a výška zdroje v pixelech."],size:["Stored file size on the server.","Velikost souboru uloženého na serveru."],validation:["Whether the value can be safely used by LuigiScreen.","Zda může LuigiScreen tuto hodnotu bezpečně použít."],"used by":["Configured playlists and events referencing this item.","Playlisty a eventy, které tuto položku používají."],probability:["Estimated chance after weights and eligibility rules.","Odhadovaná šance po vyhodnocení vah a pravidel."],conditions:["Rules that must pass before this item is eligible.","Pravidla, která musí položka splnit před výběrem."],weight:["Relative selection weight among eligible items.","Relativní váha výběru mezi způsobilými položkami."],duration:["How long this item or stage remains active.","Jak dlouho položka nebo krok zůstane aktivní."],cooldown:["Minimum delay before an item may be selected again.","Minimální prodleva před dalším možným výběrem."],enabled:["Whether this object participates in live operation.","Zda se objekt účastní živého provozu."],source:["Media input currently assigned to the screen.","Mediální vstup aktuálně přiřazený plátnu."],performance:["Rendering speed and frame processing cost.","Rychlost vykreslování a cena zpracování snímků."],location:["Stored Minecraft world and block coordinates.","Uložený svět Minecraftu a souřadnice bloků."],"now playing":["Item players are receiving right now.","Položka, kterou hráči právě přijímají."],viewers:["Players currently eligible and close enough to receive this screen.","Hráči, kteří mají oprávnění a jsou dostatečně blízko plátnu."],output:["Physical map dimensions of the screen.","Fyzické mapové rozměry plátna."],"actual fps":["Frames actually rendered after adaptive limits.","Snímky skutečně vykreslené po adaptivním omezení."]};const pair=explanations[key]||["Explains the value or control shown next to this icon.","Vysvětluje hodnotu nebo ovládací prvek vedle této ikony."];return lang()==="cs"?pair[1]:pair[0];}
  function openInspector(){$("#app").classList.add("inspector-active");if(innerWidth<=900)$("#inspector").classList.add("open");}
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
  $("#closeInspector").onclick=()=>{app.selected=null;$("#inspector").classList.remove("open");$("#app").classList.remove("inspector-active");renderInspector();};
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
