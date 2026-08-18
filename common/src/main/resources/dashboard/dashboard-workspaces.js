(() => {
  'use strict';

  const COPY = {
    en: {
      players: 'Players', playersHelp: 'Online and known players, live session details and administration shortcuts.', onlineNow: 'Online now', knownPlayers: 'Known players', offline: 'Offline', lastSeen: 'Last seen', search: 'Search players...', refresh: 'Refresh', view: 'View',
      noPlayers: 'No players are known yet.', noMatch: 'No matching players.', select: 'Select a player to inspect.', world: 'World', position: 'Position', ping: 'Ping', level: 'Level', health: 'Health', uuid: 'UUID',
      permissions: 'Permissions', moderation: 'Moderation', copyUuid: 'Copy UUID', copied: 'Copied', healthy: 'Everything looks healthy', attention: 'Needs attention',
      onlinePlayers: 'Online players', uptime: 'Uptime', storage: 'Storage', modules: 'Modules', recent: 'Recent activity', quick: 'Quick actions', configuration: 'Configuration', restart: 'Restart', settings: 'Settings',
      heap: 'JVM heap', processCpu: 'Process CPU', systemCpu: 'System CPU', threads: 'Threads', discord: 'Discord',
      heapPressure: 'JVM heap usage is above 90%.', remoteAccessWarning: 'Dashboard remote access is enabled or bound outside localhost.', storageFallback: 'Storage fallback is active', discordDisconnected: 'Discord integration is enabled but disconnected.'
    },
    cs: {
      players: 'Hráči', playersHelp: 'Online i známí hráči, živé informace o session a rychlé administrační akce.', onlineNow: 'Právě online', knownPlayers: 'Známí hráči', offline: 'Offline', lastSeen: 'Naposledy online', search: 'Hledat hráče...', refresh: 'Obnovit', view: 'Zobrazit',
      noPlayers: 'Zatím nejsou známí žádní hráči.', noMatch: 'Žádní odpovídající hráči.', select: 'Vyber hráče pro zobrazení detailu.', world: 'Svět', position: 'Pozice', ping: 'Ping', level: 'Level', health: 'Životy', uuid: 'UUID',
      permissions: 'Oprávnění', moderation: 'Moderace', copyUuid: 'Kopírovat UUID', copied: 'Zkopírováno', healthy: 'Všechno vypadá v pořádku', attention: 'Vyžaduje pozornost',
      onlinePlayers: 'Online hráči', uptime: 'Uptime', storage: 'Storage', modules: 'Moduly', recent: 'Nedávná aktivita', quick: 'Rychlé akce', configuration: 'Konfigurace', restart: 'Restart', settings: 'Nastavení',
      heap: 'JVM heap', processCpu: 'CPU procesu', systemCpu: 'CPU systému', threads: 'Vlákna', discord: 'Discord',
      heapPressure: 'Využití JVM heapu je nad 90 %.', remoteAccessWarning: 'Vzdálený přístup k dashboardu je povolen nebo není omezen na localhost.', storageFallback: 'Je aktivní fallback úložiště', discordDisconnected: 'Integrace Discordu je povolená, ale odpojená.'
    },
    ru: {
      players: 'Игроки', playersHelp: 'Игроки онлайн и известные игроки, данные текущей сессии и быстрые административные действия.', onlineNow: 'Сейчас онлайн', knownPlayers: 'Известные игроки', offline: 'Оффлайн', lastSeen: 'Последний вход', search: 'Поиск игроков...', refresh: 'Обновить', view: 'Открыть',
      noPlayers: 'Пока нет известных игроков.', noMatch: 'Подходящие игроки не найдены.', select: 'Выберите игрока для просмотра.', world: 'Мир', position: 'Позиция', ping: 'Пинг', level: 'Уровень', health: 'Здоровье', uuid: 'UUID',
      permissions: 'Права', moderation: 'Модерация', copyUuid: 'Копировать UUID', copied: 'Скопировано', healthy: 'Всё выглядит нормально', attention: 'Требует внимания',
      onlinePlayers: 'Игроки онлайн', uptime: 'Uptime', storage: 'Хранилище', modules: 'Модули', recent: 'Недавняя активность', quick: 'Быстрые действия', configuration: 'Конфигурация', restart: 'Перезапуск', settings: 'Настройки',
      heap: 'JVM heap', processCpu: 'CPU процесса', systemCpu: 'CPU системы', threads: 'Потоки', discord: 'Discord',
      heapPressure: 'Использование JVM heap выше 90%.', remoteAccessWarning: 'Удалённый доступ к панели включён или не ограничен localhost.', storageFallback: 'Активен резервный провайдер хранилища', discordDisconnected: 'Интеграция Discord включена, но отключена от Discord.'
    }
  };

  let players = [];
  let selectedUuid = null;
  let directoryTotal = 0;
  let onlineTotal = 0;
  let directoryAvailable = false;
  let routesInstalled = false;
  let playerSearchTimer = null;
  let playerLoadGeneration = 0;
  let overviewLoadGeneration = 0;

  function locale() {
    const code = window.ParadigmI18n?.locale || document.documentElement.lang || 'en';
    return COPY[code] ? code : 'en';
  }

  function tr(key) {
    return COPY[locale()]?.[key] || COPY.en[key] || key;
  }

  function esc(value) {
    return String(value ?? '')
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
  }

  function duration(ms) {
    const seconds = Math.max(0, Math.floor(Number(ms || 0) / 1000));
    const days = Math.floor(seconds / 86400);
    const hours = Math.floor((seconds % 86400) / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    if (days) return `${days}d ${hours}h`;
    if (hours) return `${hours}h ${minutes}m`;
    return `${minutes}m`;
  }

  function coordinate(value) {
    const number = Number(value);
    return Number.isFinite(number) ? number.toFixed(1) : '-';
  }

  function formatLastSeen(value) {
    const time = Number(value || 0);
    if (!Number.isFinite(time) || time <= 0) return '—';
    try {
      return new Intl.DateTimeFormat(locale(), { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(time));
    } catch (_) {
      return new Date(time).toLocaleString();
    }
  }

  function formatBytes(value) {
    const bytes = Number(value);
    if (!Number.isFinite(bytes) || bytes < 0) return '—';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let amount = bytes;
    let index = 0;
    while (amount >= 1024 && index < units.length - 1) {
      amount /= 1024;
      index += 1;
    }
    return `${amount >= 10 || index === 0 ? amount.toFixed(0) : amount.toFixed(1)} ${units[index]}`;
  }

  function formatPercent(value) {
    const number = Number(value);
    if (!Number.isFinite(number) || number < 0) return '—';
    return `${(number * 100).toFixed(number >= 0.1 ? 0 : 1)}%`;
  }

  function playerKey(player) {
    return String(player?.uuid || player?.name || '').toLowerCase();
  }

  function isOnline(player) {
    return player?.online === true;
  }

  function currentPage() {
    return document.querySelector('.page.active')?.dataset.page || location.hash.slice(1) || 'overview';
  }

  function currentSearch() {
    return String(document.getElementById('shell-player-search')?.value || '').trim();
  }

  function ensurePlayersPage() {
    if (document.querySelector('[data-page="players"]')) return;
    const app = document.getElementById('app-panel');
    const saveBar = document.getElementById('save-bar');
    if (!app) return;
    const page = document.createElement('section');
    page.dataset.page = 'players';
    page.className = 'page shell-players-page';
    page.innerHTML = `
      <div class="page-toolbar">
        <div><strong>${esc(tr('players'))}</strong><span>${esc(tr('playersHelp'))}</span></div>
        <button id="shell-player-refresh" type="button">${esc(tr('refresh'))}</button>
      </div>
      <div class="shell-player-summary">
        <div class="shell-player-counts">
          <div><span class="shell-kicker">${esc(tr('onlineNow'))}</span><strong id="shell-player-count">0</strong></div>
          <div id="shell-known-count-wrap" class="hidden"><span class="shell-kicker">${esc(tr('knownPlayers'))}</span><strong id="shell-known-count">0</strong></div>
        </div>
        <input id="shell-player-search" type="search" autocomplete="off" aria-label="${esc(tr('search'))}" placeholder="${esc(tr('search'))}">
      </div>
      <div class="shell-player-workspace">
        <div id="shell-player-list" class="shell-player-list"></div>
        <div id="shell-player-detail" class="shell-player-detail"><div class="shell-player-empty">${esc(tr('select'))}</div></div>
      </div>`;
    if (saveBar) app.insertBefore(page, saveBar);
    else app.appendChild(page);
    page.querySelector('#shell-player-refresh')?.addEventListener('click', () => loadPlayers(currentSearch()));
    page.querySelector('#shell-player-search')?.addEventListener('input', schedulePlayerSearch);
  }

  function schedulePlayerSearch() {
    renderPlayers();
    if (playerSearchTimer) window.clearTimeout(playerSearchTimer);
    playerSearchTimer = window.setTimeout(() => {
      playerSearchTimer = null;
      loadPlayers(currentSearch());
    }, 160);
  }

  function installPlayerNav() {
    const group = document.querySelector('#navigation .shell-primary-nav');
    if (!group || group.querySelector('[data-page-target="players"]')) return;
    const button = document.createElement('button');
    button.type = 'button';
    button.dataset.pageTarget = 'players';
    button.textContent = tr('players');
    if (currentPage() === 'players') {
      button.classList.add('active');
      button.setAttribute('aria-current', 'page');
    }
    button.addEventListener('click', () => requestNavigate('players'));
    const overview = group.querySelector('[data-page-target="overview"]');
    if (overview) overview.after(button);
    else group.prepend(button);
  }

  function syncPlayerHeader() {
    if (currentPage() !== 'players') return;
    const title = document.getElementById('page-title');
    const subtitle = document.getElementById('page-subtitle');
    if (title) title.textContent = tr('players');
    if (subtitle) subtitle.textContent = tr('playersHelp');
    document.title = `${tr('players')} · Paradigm Dashboard`;
  }

  function installRoutes() {
    if (routesInstalled) return;
    routesInstalled = true;
    pageInfo.players = [tr('players'), tr('playersHelp')];
    ensurePlayersPage();
    installPlayerNav();
    window.ParadigmDashboardRuntime?.afterPageLoad(async page => {
      if (page === 'players') await loadPlayers(currentSearch());
    });
    loadOverview = loadOverviewHealth;
  }

  async function loadDirectory(query = '') {
    try {
      const data = await api(`/api/players?query=${encodeURIComponent(query)}&page=1&pageSize=100`);
      return {
        rows: Array.isArray(data?.players) ? data.players : [],
        total: Math.max(0, Number(data?.total || 0)),
        available: true
      };
    } catch (_) {
      return { rows: [], total: 0, available: false };
    }
  }

  function mergePlayers(liveRows, directoryRows) {
    const merged = new Map();
    for (const row of directoryRows || []) {
      const key = playerKey(row);
      if (!key) continue;
      merged.set(key, { ...row, online: Boolean(row.online) });
    }
    for (const row of liveRows || []) {
      const key = playerKey(row);
      if (!key) continue;
      const previous = merged.get(key) || {};
      merged.set(key, { ...previous, ...row, online: true, lastSeenMs: Math.max(Number(previous.lastSeenMs || 0), Date.now()) });
    }
    return [...merged.values()].sort((a, b) => {
      if (isOnline(a) !== isOnline(b)) return isOnline(a) ? -1 : 1;
      return String(a.name || a.uuid || '').localeCompare(String(b.name || b.uuid || ''), undefined, { sensitivity: 'base' });
    });
  }

  async function loadPlayers(query = '') {
    const list = document.getElementById('shell-player-list');
    if (!list) return;
    const generation = ++playerLoadGeneration;
    try {
      const [overviewResult, directory] = await Promise.all([api('/api/overview'), loadDirectory(query)]);
      if (generation !== playerLoadGeneration) return;
      const liveAll = Array.isArray(overviewResult?.players) ? overviewResult.players : [];
      onlineTotal = Math.max(0, Number(overviewResult?.onlinePlayers ?? liveAll.length));
      const needle = String(query || '').toLocaleLowerCase();
      const live = needle
        ? liveAll.filter(player => [player.name, player.uuid, player.world].some(value => String(value || '').toLocaleLowerCase().includes(needle)))
        : liveAll;
      directoryAvailable = Boolean(directory.available);
      directoryTotal = Number(directory.total || 0);
      players = mergePlayers(live, directory.rows);
      if (!players.some(player => playerKey(player) === selectedUuid)) selectedUuid = playerKey(players[0]) || null;
      renderPlayers();
    } catch (error) {
      if (generation !== playerLoadGeneration) return;
      list.innerHTML = `<div class="shell-player-empty">${esc(error.message || String(error))}</div>`;
      const detail = document.getElementById('shell-player-detail');
      if (detail) detail.innerHTML = `<div class="shell-player-empty">${esc(tr('select'))}</div>`;
    }
  }

  function renderPlayers() {
    const list = document.getElementById('shell-player-list');
    const detail = document.getElementById('shell-player-detail');
    const count = document.getElementById('shell-player-count');
    const knownCount = document.getElementById('shell-known-count');
    const knownWrap = document.getElementById('shell-known-count-wrap');
    if (!list || !detail) return;
    if (count) count.textContent = String(onlineTotal);
    if (knownCount) knownCount.textContent = String(Math.max(directoryTotal, players.length));
    knownWrap?.classList.toggle('hidden', !directoryAvailable);
    const needle = currentSearch().toLocaleLowerCase();
    const visible = players.filter(player => !needle || [player.name, player.uuid, player.world].some(value => String(value || '').toLocaleLowerCase().includes(needle)));
    if (!visible.length) {
      list.innerHTML = `<div class="shell-player-empty">${esc(players.length || needle ? tr('noMatch') : tr('noPlayers'))}</div>`;
    } else {
      list.innerHTML = visible.map(player => {
        const online = isOnline(player);
        const subtitle = online ? (player.world || '-') : `${tr('lastSeen')}: ${formatLastSeen(player.lastSeenMs)}`;
        return `<button type="button" class="shell-player-row${playerKey(player) === selectedUuid ? ' is-active' : ''}${online ? '' : ' is-offline'}" data-shell-player="${esc(playerKey(player))}">
          <span><strong>${esc(player.name || player.uuid || '-')}</strong><small>${esc(subtitle)}</small></span>
          <span class="shell-player-row-state">${online ? `${Number(player.ping || 0)} ms` : esc(tr('offline'))}</span>
        </button>`;
      }).join('');
      list.querySelectorAll('[data-shell-player]').forEach(button => button.addEventListener('click', () => {
        const player = players.find(row => playerKey(row) === button.dataset.shellPlayer);
        if (!player) return;
        selectedUuid = playerKey(player);
        renderPlayers();
      }));
    }
    const selected = players.find(player => playerKey(player) === selectedUuid) || null;
    renderPlayerDetail(selected);
  }

  function renderPlayerDetail(player) {
    const root = document.getElementById('shell-player-detail');
    if (!root) return;
    if (!player) {
      root.innerHTML = `<div class="shell-player-empty">${esc(players.length ? tr('select') : tr('noPlayers'))}</div>`;
      return;
    }
    const online = isOnline(player);
    const health = player.health == null ? '-' : `${Number(player.health).toFixed(1)}${player.maxHealth == null ? '' : ` / ${Number(player.maxHealth).toFixed(1)}`}`;
    const facts = online ? `
        <div><span>${esc(tr('world'))}</span><strong>${esc(player.world || '-')}</strong></div>
        <div><span>${esc(tr('position'))}</span><strong>${coordinate(player.x)}, ${coordinate(player.y)}, ${coordinate(player.z)}</strong></div>
        <div><span>${esc(tr('level'))}</span><strong>${player.level ?? '-'}</strong></div>
        <div><span>${esc(tr('health'))}</span><strong>${esc(health)}</strong></div>` : `
        <div><span>${esc(tr('lastSeen'))}</span><strong>${esc(formatLastSeen(player.lastSeenMs))}</strong></div>`;
    root.innerHTML = `
      <div class="shell-player-detail-head${online ? '' : ' is-offline'}">
        <div><span class="shell-online-dot${online ? '' : ' is-offline'}"></span><span>${esc(online ? tr('onlineNow') : tr('offline'))}</span><h2>${esc(player.name || '-')}</h2></div>
        <span class="shell-ping">${online ? `${Number(player.ping || 0)} ms` : esc(tr('offline'))}</span>
      </div>
      <div class="shell-player-facts">
        ${facts}
        <div class="shell-player-uuid"><span>${esc(tr('uuid'))}</span><strong>${esc(player.uuid || '-')}</strong></div>
      </div>
      <div class="shell-player-actions">
        <button type="button" data-player-action="permissions">${esc(tr('permissions'))}</button>
        <button type="button" data-player-action="moderation">${esc(tr('moderation'))}</button>
        <button type="button" data-player-action="copy">${esc(tr('copyUuid'))}</button>
      </div>`;
    root.querySelector('[data-player-action="permissions"]')?.addEventListener('click', () => openPermissions(player));
    root.querySelector('[data-player-action="moderation"]')?.addEventListener('click', () => openModeration(player));
    root.querySelector('[data-player-action="copy"]')?.addEventListener('click', event => copyUuid(player, event.currentTarget));
  }

  async function waitFor(selector, attempts = 20) {
    for (let attempt = 0; attempt < attempts; attempt += 1) {
      const node = document.querySelector(selector);
      if (node) return node;
      await new Promise(resolve => window.setTimeout(resolve, 50));
    }
    return null;
  }

  async function openPermissions(player) {
    await requestNavigate('permissions');
    const users = await waitFor('[data-permission-view="users"]');
    users?.click();
    const search = await waitFor('#permissions-search');
    if (search) {
      search.value = player.name || player.uuid || '';
      search.dispatchEvent(new Event('input', { bubbles: true }));
      search.focus();
    }
  }

  async function openModeration(player) {
    await requestNavigate('moderation');
    const search = await waitFor('#moderation-search');
    if (search) search.value = player.name || player.uuid || '';
    const find = await waitFor('#moderation-find');
    find?.click();
  }

  async function copyUuid(player, button) {
    const value = player.uuid || '';
    if (!value) return;
    try {
      if (navigator.clipboard?.writeText) await navigator.clipboard.writeText(value);
      else {
        const textarea = document.createElement('textarea');
        textarea.value = value;
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        document.execCommand('copy');
        textarea.remove();
      }
      const before = button.textContent;
      button.textContent = tr('copied');
      window.setTimeout(() => { if (button.isConnected) button.textContent = before; }, 1200);
    } catch (_) {}
  }

  function metric(label, value) {
    return `<div class="shell-overview-metric"><span>${esc(label)}</span><strong>${esc(value)}</strong></div>`;
  }

  function runtimeMetrics(data) {
    const runtime = data?.runtime || {};
    const values = [];
    const heapMax = Number(runtime.heapMaxBytes || 0);
    const heapUsed = Number(runtime.heapUsedBytes || 0);
    if (heapMax > 0) values.push([tr('heap'), `${formatBytes(heapUsed)} / ${formatBytes(heapMax)}`]);
    if (Number(runtime.processCpuLoad) >= 0) values.push([tr('processCpu'), formatPercent(runtime.processCpuLoad)]);
    if (Number(runtime.systemCpuLoad) >= 0) values.push([tr('systemCpu'), formatPercent(runtime.systemCpuLoad)]);
    if (runtime.liveThreads != null) values.push([tr('threads'), String(runtime.liveThreads)]);
    if (data?.discord?.enabled) values.push([tr('discord'), data.discord.summary || data.discord.state || '—']);
    return values.map(([label, value]) => metric(label, value)).join('');
  }

  function healthProblems(data, warnings) {
    const problems = Array.isArray(data?.problems) ? data.problems : [];
    if (problems.length) return problems;
    return warnings.map(message => ({ message }));
  }

  function localizedProblemMessage(problem) {
    const message = problem?.message || String(problem || '');
    switch (problem?.code) {
      case 'heap_pressure':
        return tr('heapPressure');
      case 'dashboard_remote_access':
        return tr('remoteAccessWarning');
      case 'storage_fallback': {
        const separator = message.indexOf(':');
        const detail = separator >= 0 ? message.slice(separator + 1).trim() : '';
        return detail ? `${tr('storageFallback')}: ${detail}` : tr('storageFallback');
      }
      case 'discord_health':
        return message === 'Discord: integration is enabled but disconnected.' ? tr('discordDisconnected') : message;
      default:
        return message;
    }
  }

  function healthProblem(problem) {
    const message = localizedProblemMessage(problem);
    const target = typeof problem?.target === 'string' ? problem.target : '';
    return `<li><span>${esc(message)}</span>${target ? `<button type="button" data-problem-go="${esc(target)}">${esc(tr('view'))}</button>` : ''}</li>`;
  }

  async function loadOverviewHealth() {
    const grid = document.getElementById('overview-grid');
    const activity = document.getElementById('overview-activity');
    if (!grid || !activity) return;
    const generation = ++overviewLoadGeneration;
    try {
      const data = await api('/api/overview');
      if (generation !== overviewLoadGeneration) return;
      const warnings = Array.isArray(data.warnings) ? data.warnings : [];
      const problems = healthProblems(data, warnings);
      const live = Array.isArray(data.players) ? data.players.map(player => ({ ...player, online: true })) : [];
      onlineTotal = Math.max(0, Number(data.onlinePlayers ?? live.length));
      const offline = players.filter(player => !isOnline(player));
      players = mergePlayers(live, offline);
      const capacity = Number(data.maxPlayers || 0) > 0 ? `${data.onlinePlayers ?? live.length} / ${data.maxPlayers}` : String(data.onlinePlayers ?? live.length);
      const hasProblems = problems.length > 0;
      const healthClass = hasProblems ? 'has-warning' : 'is-healthy';
      const healthTitle = hasProblems ? tr('attention') : tr('healthy');
      const identity = [data.serverName || data.serverId, data.minecraftVersion, data.loader, data.version ? `Paradigm ${data.version}` : ''].filter(Boolean).join(' · ');
      grid.classList.add('shell-overview-grid');
      grid.innerHTML = `
        <section class="shell-health-card ${healthClass}">
          <div><span class="shell-health-icon">${hasProblems ? '!' : '✓'}</span><span><strong>${esc(healthTitle)}</strong><small>${esc(identity)}</small></span></div>
          ${hasProblems ? `<ul>${problems.map(healthProblem).join('')}</ul>` : ''}
        </section>
        ${metric(tr('onlinePlayers'), capacity)}
        ${metric(tr('uptime'), duration(data.uptimeMs))}
        ${metric(tr('storage'), data.activeProvider || '-')}
        ${metric(tr('modules'), `${data.modules?.enabled ?? 0} / ${data.modules?.total ?? 0}`)}
        ${runtimeMetrics(data)}`;
      grid.querySelectorAll('[data-problem-go]').forEach(button => button.addEventListener('click', () => requestNavigate(button.dataset.problemGo)));
      activity.innerHTML = `
        <section class="shell-overview-panel">
          <header><div><span class="shell-kicker">${esc(tr('onlineNow'))}</span><h2>${esc(tr('players'))}</h2></div><button type="button" data-workspace-go="players">${esc(tr('players'))}</button></header>
          <div class="shell-overview-player-list">${overviewPlayers(live)}</div>
          <div class="shell-quick-actions"><span>${esc(tr('quick'))}</span><div>
            <button type="button" data-workspace-go="general">${esc(tr('configuration'))}</button>
            <button type="button" data-workspace-go="permissions">${esc(tr('permissions'))}</button>
            <button type="button" data-workspace-go="restart">${esc(tr('restart'))}</button>
            <button type="button" data-workspace-go="dashboard">${esc(tr('settings'))}</button>
          </div></div>
        </section>
        <section class="shell-overview-panel">
          <header><div><span class="shell-kicker">Paradigm</span><h2>${esc(tr('recent'))}</h2></div></header>
          <div class="shell-activity-list">${recentActivity(data.recentActivity)}</div>
        </section>`;
      activity.querySelectorAll('[data-workspace-go]').forEach(button => button.addEventListener('click', () => requestNavigate(button.dataset.workspaceGo)));
      activity.querySelectorAll('[data-overview-player]').forEach(button => button.addEventListener('click', () => {
        selectedUuid = button.dataset.overviewPlayer;
        requestNavigate('players');
      }));
    } catch (error) {
      if (generation !== overviewLoadGeneration) return;
      grid.classList.remove('shell-overview-grid');
      if (typeof renderError === 'function') renderError('overview-grid', error.message);
      else grid.textContent = error.message || String(error);
    }
  }

  function overviewPlayers(liveRows = players.filter(isOnline)) {
    if (!liveRows.length) return `<div class="shell-player-empty">${esc(tr('noPlayers'))}</div>`;
    return liveRows.slice(0, 6).map(player => `
      <button type="button" data-overview-player="${esc(playerKey(player))}">
        <span class="shell-online-dot"></span>
        <span><strong>${esc(player.name || '-')}</strong><small>${esc(player.world || '-')}</small></span>
        <small>${Number(player.ping || 0)} ms</small>
      </button>`).join('');
  }

  function recentActivity(entries) {
    if (!Array.isArray(entries) || !entries.length) return `<div class="shell-player-empty">—</div>`;
    return entries.slice(0, 8).map(entry => `
      <div><strong>${esc(entry.actorName || '-')}</strong><span>${esc(entry.actionType || '-')}</span><small>${esc(entry.result || '-')}</small></div>`).join('');
  }

  function resetSessionState(event) {
    const authenticated = Boolean(event?.detail?.authenticated);
    if (!authenticated) {
      playerLoadGeneration += 1;
      overviewLoadGeneration += 1;
      if (playerSearchTimer) {
        window.clearTimeout(playerSearchTimer);
        playerSearchTimer = null;
      }
    }
    players = [];
    selectedUuid = null;
    directoryTotal = 0;
    onlineTotal = 0;
    directoryAvailable = false;
    const search = document.getElementById('shell-player-search');
    if (search) search.value = '';
    const count = document.getElementById('shell-player-count');
    if (count) count.textContent = '0';
    const knownCount = document.getElementById('shell-known-count');
    if (knownCount) knownCount.textContent = '0';
    document.getElementById('shell-known-count-wrap')?.classList.add('hidden');
    document.getElementById('shell-player-list')?.replaceChildren();
    document.getElementById('shell-player-detail')?.replaceChildren();
    document.getElementById('overview-grid')?.replaceChildren();
    document.getElementById('overview-activity')?.replaceChildren();
  }

  function refreshLanguage() {
    const active = currentPage() === 'players';
    const query = currentSearch();
    pageInfo.players = [tr('players'), tr('playersHelp')];
    document.querySelector('[data-page="players"]')?.remove();
    ensurePlayersPage();
    const search = document.getElementById('shell-player-search');
    if (search) search.value = query;
    if (active) document.querySelector('[data-page="players"]')?.classList.add('active');
    installPlayerNav();
    const navButton = document.querySelector('#navigation [data-page-target="players"]');
    if (navButton) navButton.textContent = tr('players');
    syncPlayerHeader();
    if (active) loadPlayers(query);
    if (currentPage() === 'overview') loadOverviewHealth();
  }

  document.addEventListener('paradigm:session-changed', resetSessionState);
  document.addEventListener('paradigm:language-changed', refreshLanguage);
  window.addEventListener('hashchange', () => window.setTimeout(() => {
    installPlayerNav();
    syncPlayerHeader();
  }, 0));

  installRoutes();
})();