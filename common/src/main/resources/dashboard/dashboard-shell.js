(() => {
  'use strict';

  const COPY = {
    en: {
      overview: 'Overview', configuration: 'Configuration', administration: 'Administration', network: 'Network', system: 'System', settings: 'Settings',
      searchParadigm: 'Search Paradigm...', configureServer: 'Configure your server', configureServerHelp: 'Choose an area. The existing editors stay exactly where they are, but you no longer need to remember where every setting lives.',
      general: 'General', generalHelp: 'Modules, command families and administration features.', communication: 'Communication', communicationHelp: 'Chat, announcements and Discord.',
      gameplay: 'Gameplay', gameplayHelp: 'Teleports, command availability and command timing.', presentation: 'Presentation', presentationHelp: 'MOTD, tablist and holograms.',
      automation: 'Automation', automationHelp: 'Restarts, scheduled messages and custom commands.', integrations: 'Integrations', integrationsHelp: 'Discord and storage-backed services.',
      open: 'Open', settingsHub: 'Dashboard & storage', settingsHubHelp: 'Local dashboard security, storage runtime and provider configuration.', dashboardSettings: 'Dashboard settings',
      storageRuntime: 'Storage runtime', storageConfiguration: 'Storage configuration', serverNetwork: 'Server network', backConfiguration: 'Configuration', backSettings: 'Settings',
      paletteTitle: 'Search Paradigm', paletteHint: 'Pages and settings', pages: 'Page', setting: 'Setting', noResults: 'No matching pages or settings.',
      searchHelp: 'Type a page, feature, setting label or config key.', esc: 'Esc', enter: 'Enter', generalSettings: 'General settings'
    },
    cs: {
      overview: 'Přehled', configuration: 'Konfigurace', administration: 'Administrace', network: 'Síť', system: 'Systém', settings: 'Nastavení',
      searchParadigm: 'Hledat v Paradigmu...', configureServer: 'Nastavení serveru', configureServerHelp: 'Vyber oblast. Existující editory zůstávají na svém místě, ale nemusíš si pamatovat, kde je každá volba.',
      general: 'Obecné', generalHelp: 'Moduly, skupiny příkazů a administrační funkce.', communication: 'Komunikace', communicationHelp: 'Chat, oznámení a Discord.',
      gameplay: 'Gameplay', gameplayHelp: 'Teleporty, dostupnost příkazů a jejich časování.', presentation: 'Vzhled', presentationHelp: 'MOTD, tablist a hologramy.',
      automation: 'Automatizace', automationHelp: 'Restarty, plánované zprávy a vlastní příkazy.', integrations: 'Integrace', integrationsHelp: 'Discord a služby využívající storage.',
      open: 'Otevřít', settingsHub: 'Dashboard a storage', settingsHubHelp: 'Zabezpečení dashboardu, stav storage a nastavení provideru.', dashboardSettings: 'Nastavení dashboardu',
      storageRuntime: 'Stav storage', storageConfiguration: 'Nastavení storage', serverNetwork: 'Síť serverů', backConfiguration: 'Konfigurace', backSettings: 'Nastavení',
      paletteTitle: 'Hledat v Paradigmu', paletteHint: 'Stránky a nastavení', pages: 'Stránka', setting: 'Nastavení', noResults: 'Žádná odpovídající stránka ani nastavení.',
      searchHelp: 'Napiš stránku, funkci, název nastavení nebo config key.', esc: 'Esc', enter: 'Enter', generalSettings: 'Obecná nastavení'
    },
    ru: {
      overview: 'Обзор', configuration: 'Конфигурация', administration: 'Администрирование', network: 'Сеть', system: 'Система', settings: 'Настройки',
      searchParadigm: 'Поиск в Paradigm...', configureServer: 'Настройка сервера', configureServerHelp: 'Выберите раздел. Существующие редакторы остаются на месте, но больше не нужно помнить, где находится каждая настройка.',
      general: 'Общие', generalHelp: 'Модули, группы команд и административные функции.', communication: 'Связь', communicationHelp: 'Чат, объявления и Discord.',
      gameplay: 'Геймплей', gameplayHelp: 'Телепорты, доступность команд и тайминги.', presentation: 'Оформление', presentationHelp: 'MOTD, tablist и голограммы.',
      automation: 'Автоматизация', automationHelp: 'Перезапуски, запланированные сообщения и пользовательские команды.', integrations: 'Интеграции', integrationsHelp: 'Discord и сервисы, использующие хранилище.',
      open: 'Открыть', settingsHub: 'Dashboard и хранилище', settingsHubHelp: 'Безопасность dashboard, состояние хранилища и настройка провайдера.', dashboardSettings: 'Настройки dashboard',
      storageRuntime: 'Состояние хранилища', storageConfiguration: 'Настройка хранилища', serverNetwork: 'Сеть серверов', backConfiguration: 'Конфигурация', backSettings: 'Настройки',
      paletteTitle: 'Поиск в Paradigm', paletteHint: 'Страницы и настройки', pages: 'Страница', setting: 'Настройка', noResults: 'Подходящих страниц или настроек нет.',
      searchHelp: 'Введите страницу, функцию, название настройки или ключ конфигурации.', esc: 'Esc', enter: 'Enter', generalSettings: 'Общие настройки'
    }
  };

  const PAGE_FALLBACKS = {
    overview: 'Overview', general: 'Configuration', teleports: 'Teleports', chat: 'Chat Editor', announcements: 'Announcements', restart: 'Restart',
    motd: 'MOTD Editor', tablist: 'Tablist', holograms: 'Holograms', customCommands: 'Custom Commands', commands: 'Command Settings', cooldowns: 'Cooldowns',
    discord: 'Discord', permissions: 'Permission Editor', moderation: 'Moderation', audit: 'Audit', servers: 'Servers', storage: 'Storage', dashboard: 'Settings', storageConfig: 'Storage Configuration'
  };

  const CONFIG_CARDS = [
    { title: 'general', help: 'generalHelp', links: [['general', 'generalSettings']] },
    { title: 'communication', help: 'communicationHelp', links: [['chat'], ['announcements'], ['discord']] },
    { title: 'gameplay', help: 'gameplayHelp', links: [['teleports'], ['commands'], ['cooldowns']] },
    { title: 'presentation', help: 'presentationHelp', links: [['motd'], ['tablist'], ['holograms']] },
    { title: 'automation', help: 'automationHelp', links: [['restart'], ['announcements'], ['customCommands']] },
    { title: 'integrations', help: 'integrationsHelp', links: [['discord'], ['storage']] }
  ];

  const CONFIG_DETAIL_PAGES = ['teleports', 'chat', 'announcements', 'restart', 'motd', 'tablist', 'holograms', 'customCommands', 'commands', 'cooldowns', 'discord'];
  const SETTINGS_DETAIL_PAGES = ['storage', 'storageConfig'];
  const SEARCH_PAGES = ['overview', 'general', ...CONFIG_DETAIL_PAGES, 'permissions', 'moderation', 'audit', 'servers', 'storage', 'dashboard', 'storageConfig'];

  let networkActive = false;
  let palette = null;
  let paletteResults = [];
  let paletteIndex = 0;
  let paletteReturnFocus = null;
  let headerSyncing = false;
  let wasAuthenticated = document.body.classList.contains('is-authenticated');

  function locale() {
    const value = window.ParadigmI18n?.locale || document.documentElement.lang || 'en';
    return COPY[value] ? value : 'en';
  }

  function tr(key) {
    return COPY[locale()]?.[key] || COPY.en[key] || key;
  }

  function currentPage() {
    return document.querySelector('.page.active')?.dataset.page || location.hash.slice(1) || 'overview';
  }

  function pageLabel(page) {
    if (page === 'general') return tr('configuration');
    if (page === 'dashboard') return tr('settings');
    const heading = document.querySelector(`[data-page="${page}"] > .page-toolbar strong`);
    return heading?.textContent?.trim() || PAGE_FALLBACKS[page] || page;
  }

  function pageDescription(page) {
    if (page === 'general') return tr('configureServerHelp');
    if (page === 'dashboard') return tr('settingsHubHelp');
    return document.querySelector(`[data-page="${page}"] > .page-toolbar span`)?.textContent?.trim() || '';
  }

  async function go(page, fieldKey = null) {
    closePalette();
    if (typeof window.requestNavigate === 'function') await window.requestNavigate(page);
    else location.hash = `#${page}`;
    syncHeader();
    if (fieldKey) window.setTimeout(() => revealField(fieldKey), 50);
  }

  function revealField(fieldKey) {
    const row = [...document.querySelectorAll('[data-field-row]')].find(node => node.dataset.fieldRow === fieldKey);
    if (!row) return;
    row.scrollIntoView({ behavior: 'smooth', block: 'center' });
    row.classList.remove('shell-field-pulse');
    void row.offsetWidth;
    row.classList.add('shell-field-pulse');
    const focusable = row.querySelector('input:not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled])');
    focusable?.focus({ preventScroll: true });
    window.setTimeout(() => row.classList.remove('shell-field-pulse'), 1800);
  }

  function makeNavGroup(title, entries, options = {}) {
    const section = document.createElement('section');
    section.className = `nav-group ${options.className || ''}`.trim();
    if (options.network) section.dataset.shellNetwork = 'true';
    if (title) {
      const heading = document.createElement('h2');
      heading.className = 'nav-heading';
      heading.textContent = title;
      section.appendChild(heading);
    }
    for (const [page, label] of entries) {
      const button = document.createElement('button');
      button.type = 'button';
      button.dataset.pageTarget = page;
      button.textContent = label;
      if (page === currentPage()) {
        button.classList.add('active');
        button.setAttribute('aria-current', 'page');
      }
      button.addEventListener('click', () => go(page));
      section.appendChild(button);
    }
    return section;
  }

  function renderNavigation() {
    const navigation = document.getElementById('navigation');
    if (!navigation) return;
    const empty = document.createElement('p');
    empty.id = 'nav-empty';
    empty.className = 'nav-empty hidden';
    empty.setAttribute('aria-live', 'polite');
    empty.textContent = 'No matching pages.';
    navigation.replaceChildren(
      makeNavGroup('', [['overview', tr('overview')], ['general', tr('configuration')]], { className: 'shell-primary-nav' }),
      makeNavGroup(tr('administration'), [['permissions', pageLabel('permissions')], ['moderation', pageLabel('moderation')], ['audit', pageLabel('audit')]]),
      makeNavGroup(tr('network'), [['servers', pageLabel('servers')]], { network: true }),
      makeNavGroup(tr('system'), [['dashboard', tr('settings')]]),
      empty
    );
    updateNetworkVisibility();
  }

  function hubButton(page, labelOverride = null) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'shell-card-link';
    button.textContent = labelOverride ? tr(labelOverride) : pageLabel(page);
    button.addEventListener('click', () => {
      if (page === currentPage()) {
        const localTarget = page === 'general' ? 'general-fields' : page === 'dashboard' ? 'dashboard-fields' : null;
        if (localTarget) {
          document.getElementById(localTarget)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
          return;
        }
      }
      go(page);
    });
    return button;
  }

  function createHub(id, title, help, cards) {
    const hub = document.createElement('section');
    hub.id = id;
    hub.className = 'shell-hub';
    const intro = document.createElement('div');
    intro.className = 'shell-hub-intro';
    intro.innerHTML = `<span class="shell-kicker">Paradigm</span><h2></h2><p></p>`;
    intro.querySelector('h2').textContent = title;
    intro.querySelector('p').textContent = help;
    const grid = document.createElement('div');
    grid.className = 'shell-hub-grid';
    for (const cardSpec of cards) {
      const card = document.createElement('article');
      card.className = 'shell-hub-card';
      const h3 = document.createElement('h3');
      h3.textContent = tr(cardSpec.title);
      const p = document.createElement('p');
      p.textContent = tr(cardSpec.help);
      const links = document.createElement('div');
      links.className = 'shell-card-links';
      for (const [page, labelOverride] of cardSpec.links) links.appendChild(hubButton(page, labelOverride));
      card.append(h3, p, links);
      grid.appendChild(card);
    }
    hub.append(intro, grid);
    return hub;
  }

  function installConfigurationHub() {
    document.getElementById('configuration-hub')?.remove();
    document.getElementById('general-settings-anchor')?.remove();
    const page = document.querySelector('[data-page="general"]');
    const fields = document.getElementById('general-fields');
    if (!page || !fields) return;
    const hub = createHub('configuration-hub', tr('configureServer'), tr('configureServerHelp'), CONFIG_CARDS);
    fields.before(hub);
    const anchor = document.createElement('div');
    anchor.id = 'general-settings-anchor';
    anchor.className = 'shell-section-heading';
    anchor.innerHTML = `<span></span><h2></h2>`;
    anchor.querySelector('span').textContent = tr('configuration');
    anchor.querySelector('h2').textContent = tr('generalSettings');
    fields.before(anchor);
  }

  function installSettingsHub() {
    document.getElementById('settings-hub')?.remove();
    const page = document.querySelector('[data-page="dashboard"]');
    const fields = document.getElementById('dashboard-fields');
    if (!page || !fields) return;
    const cards = [
      { title: 'dashboardSettings', help: 'settingsHubHelp', links: [['dashboard', 'dashboardSettings']] },
      { title: 'storageRuntime', help: 'integrationsHelp', links: [['storage', 'storageRuntime']] },
      { title: 'storageConfiguration', help: 'integrationsHelp', links: [['storageConfig', 'storageConfiguration']] }
    ];
    if (networkActive) cards.push({ title: 'serverNetwork', help: 'settingsHubHelp', links: [['servers', 'serverNetwork']] });
    const hub = createHub('settings-hub', tr('settingsHub'), tr('settingsHubHelp'), cards);
    fields.before(hub);
  }

  function installBackLinks() {
    document.querySelectorAll('.shell-backlink').forEach(node => node.remove());
    for (const pageName of CONFIG_DETAIL_PAGES) {
      const page = document.querySelector(`[data-page="${pageName}"]`);
      if (!page) continue;
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'shell-backlink';
      button.textContent = `← ${tr('backConfiguration')}`;
      button.addEventListener('click', () => go('general'));
      page.prepend(button);
    }
    for (const pageName of SETTINGS_DETAIL_PAGES) {
      const page = document.querySelector(`[data-page="${pageName}"]`);
      if (!page) continue;
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'shell-backlink';
      button.textContent = `← ${tr('backSettings')}`;
      button.addEventListener('click', () => go('dashboard'));
      page.prepend(button);
    }
  }

  function syncHeader() {
    if (headerSyncing) return;
    const page = currentPage();
    const override = page === 'general'
      ? [tr('configuration'), tr('configureServerHelp')]
      : page === 'dashboard'
        ? [tr('settings'), tr('settingsHubHelp')]
        : null;
    if (!override) return;
    const title = document.getElementById('page-title');
    const subtitle = document.getElementById('page-subtitle');
    if (!title || !subtitle) return;
    headerSyncing = true;
    try {
      if (title.textContent !== override[0]) title.textContent = override[0];
      if (subtitle.textContent !== override[1]) subtitle.textContent = override[1];
      document.title = `${override[0]} · Paradigm Dashboard`;
    } finally {
      headerSyncing = false;
    }
  }

  function updateNetworkVisibility() {
    document.querySelectorAll('[data-shell-network]').forEach(group => group.classList.toggle('hidden', !networkActive));
  }

  async function detectNetwork() {
    if (!document.body.classList.contains('is-authenticated')) {
      networkActive = false;
      updateNetworkVisibility();
      installSettingsHub();
      return;
    }
    try {
      const data = await api('/api/servers');
      if (!document.body.classList.contains('is-authenticated')) return;
      networkActive = Boolean(data?.networkActive) || (data?.servers?.length || 0) > 1;
    } catch (_) {
      networkActive = false;
    }
    updateNetworkVisibility();
    installSettingsHub();
  }

  function pageSearchEntries() {
    const registered = typeof pageInfo === 'object' && pageInfo ? Object.keys(pageInfo) : [];
    const pages = [...new Set([...SEARCH_PAGES, ...registered])];
    return pages.map(page => ({ kind: tr('pages'), page, label: pageLabel(page), detail: pageDescription(page) }));
  }

  function settingSearchEntries() {
    const seen = new Set();
    const entries = [];
    for (const row of document.querySelectorAll('.page .config-row[data-field-row]')) {
      const key = row.dataset.fieldRow;
      const page = row.closest('.page')?.dataset.page;
      if (!key || !page || seen.has(`${page}:${key}`)) continue;
      seen.add(`${page}:${key}`);
      entries.push({
        kind: tr('setting'), page, key,
        label: row.querySelector('.config-label > strong')?.textContent?.trim() || key,
        detail: row.querySelector('.config-label > small')?.textContent?.trim() || key
      });
    }
    return entries;
  }

  function normalized(value) {
    return String(value || '').toLocaleLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');
  }

  function scoreEntry(entry, query) {
    const label = normalized(entry.label);
    const detail = normalized(entry.detail);
    const key = normalized(entry.key);
    let score = 0;
    if (label === query) score += 140;
    else if (label.startsWith(query)) score += 110;
    else if (label.includes(query)) score += 80;
    if (key === query) score += 130;
    else if (key.startsWith(query)) score += 90;
    else if (key.includes(query)) score += 65;
    if (detail.includes(query)) score += 35;
    if (entry.kind === tr('pages')) score += 5;
    return score;
  }

  function ensurePalette() {
    if (palette) return palette;
    const backdrop = document.createElement('div');
    backdrop.className = 'shell-palette-backdrop hidden';
    backdrop.innerHTML = `
      <section class="shell-palette" role="dialog" aria-modal="true" aria-labelledby="shell-palette-title">
        <header class="shell-palette-head">
          <div><span class="shell-kicker">Paradigm</span><h2 id="shell-palette-title"></h2></div>
          <kbd>${tr('esc')}</kbd>
        </header>
        <label class="shell-palette-search"><span></span><input id="shell-palette-input" type="search" autocomplete="off" spellcheck="false"></label>
        <div id="shell-palette-results" class="shell-palette-results" role="listbox"></div>
        <footer><span>${tr('searchHelp')}</span><span><kbd>↑↓</kbd> <kbd>${tr('enter')}</kbd></span></footer>
      </section>`;
    document.body.appendChild(backdrop);
    backdrop.addEventListener('mousedown', event => { if (event.target === backdrop) closePalette(); });
    const input = backdrop.querySelector('#shell-palette-input');
    input.addEventListener('input', renderPaletteResults);
    input.addEventListener('keydown', handlePaletteKeydown);
    palette = backdrop;
    refreshPaletteCopy();
    return palette;
  }

  function refreshPaletteCopy() {
    if (!palette) return;
    palette.querySelector('#shell-palette-title').textContent = tr('paletteTitle');
    const label = palette.querySelector('.shell-palette-search > span');
    if (label) label.textContent = tr('paletteHint');
    const input = palette.querySelector('#shell-palette-input');
    if (input) input.placeholder = tr('searchParadigm');
    const footer = palette.querySelector('footer > span:first-child');
    if (footer) footer.textContent = tr('searchHelp');
  }

  function openPalette(initial = '') {
    const node = ensurePalette();
    paletteReturnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    node.classList.remove('hidden');
    document.body.classList.add('shell-palette-open');
    const input = node.querySelector('#shell-palette-input');
    input.value = initial;
    paletteIndex = 0;
    renderPaletteResults();
    window.setTimeout(() => input.focus(), 0);
  }

  function closePalette() {
    if (!palette || palette.classList.contains('hidden')) return;
    palette.classList.add('hidden');
    document.body.classList.remove('shell-palette-open');
    const target = paletteReturnFocus;
    paletteReturnFocus = null;
    if (target?.isConnected) window.setTimeout(() => target.focus(), 0);
  }

  function renderPaletteResults() {
    if (!palette) return;
    const input = palette.querySelector('#shell-palette-input');
    const root = palette.querySelector('#shell-palette-results');
    const query = normalized(input.value.trim());
    let entries = pageSearchEntries();
    if (query.length >= 2) entries = entries.concat(settingSearchEntries());
    if (query) {
      entries = entries.map(entry => ({ ...entry, score: scoreEntry(entry, query) }))
        .filter(entry => entry.score > 0)
        .sort((a, b) => b.score - a.score || a.label.localeCompare(b.label));
    } else {
      const priority = new Map([['overview', 0], ['general', 1], ['permissions', 2], ['moderation', 3], ['dashboard', 4]]);
      entries = entries.filter(entry => priority.has(entry.page)).sort((a, b) => priority.get(a.page) - priority.get(b.page));
    }
    paletteResults = entries.slice(0, 12);
    paletteIndex = Math.min(paletteIndex, Math.max(0, paletteResults.length - 1));
    root.replaceChildren();
    if (!paletteResults.length) {
      const empty = document.createElement('div');
      empty.className = 'shell-palette-empty';
      empty.textContent = tr('noResults');
      root.appendChild(empty);
      return;
    }
    paletteResults.forEach((entry, index) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = `shell-palette-result${index === paletteIndex ? ' is-active' : ''}`;
      button.setAttribute('role', 'option');
      button.setAttribute('aria-selected', String(index === paletteIndex));
      button.innerHTML = '<span class="shell-result-kind"></span><span class="shell-result-copy"><strong></strong><small></small></span><span class="shell-result-page"></span>';
      button.querySelector('.shell-result-kind').textContent = entry.kind;
      button.querySelector('strong').textContent = entry.label;
      button.querySelector('small').textContent = entry.key ? `${entry.detail} · ${entry.key}` : entry.detail;
      button.querySelector('.shell-result-page').textContent = pageLabel(entry.page);
      button.addEventListener('mouseenter', () => { paletteIndex = index; syncPaletteSelection(); });
      button.addEventListener('click', () => go(entry.page, entry.key));
      root.appendChild(button);
    });
  }

  function syncPaletteSelection() {
    if (!palette) return;
    palette.querySelectorAll('.shell-palette-result').forEach((button, index) => {
      const active = index === paletteIndex;
      button.classList.toggle('is-active', active);
      button.setAttribute('aria-selected', String(active));
      if (active) button.scrollIntoView({ block: 'nearest' });
    });
  }

  function handlePaletteKeydown(event) {
    if (event.key === 'Escape') {
      event.preventDefault();
      closePalette();
      return;
    }
    if (event.key === 'ArrowDown' && paletteResults.length) {
      event.preventDefault();
      paletteIndex = (paletteIndex + 1) % paletteResults.length;
      syncPaletteSelection();
      return;
    }
    if (event.key === 'ArrowUp' && paletteResults.length) {
      event.preventDefault();
      paletteIndex = (paletteIndex - 1 + paletteResults.length) % paletteResults.length;
      syncPaletteSelection();
      return;
    }
    if (event.key === 'Enter' && paletteResults[paletteIndex]) {
      event.preventDefault();
      const entry = paletteResults[paletteIndex];
      go(entry.page, entry.key);
    }
  }

  function wireSearchTrigger() {
    const search = document.getElementById('nav-search');
    if (!search) return;
    search.placeholder = tr('searchParadigm');
    search.setAttribute('aria-haspopup', 'dialog');
    search.setAttribute('aria-label', tr('paletteTitle'));
    search.readOnly = true;
    search.value = '';
    if (!search.dataset.shellWired) {
      search.dataset.shellWired = 'true';
      search.addEventListener('focus', () => openPalette());
      search.addEventListener('click', () => openPalette());
    }
    const label = search.closest('.nav-search')?.querySelector('span');
    if (label) label.textContent = tr('paletteTitle');
  }

  function renderShell() {
    renderNavigation();
    installConfigurationHub();
    installSettingsHub();
    installBackLinks();
    wireSearchTrigger();
    refreshPaletteCopy();
    syncHeader();
  }

  document.addEventListener('keydown', event => {
    if ((event.ctrlKey || event.metaKey) && event.key.toLocaleLowerCase() === 'k') {
      event.preventDefault();
      event.stopImmediatePropagation();
      openPalette();
    }
  }, true);

  window.addEventListener('popstate', () => window.setTimeout(syncHeader, 0));
  window.addEventListener('hashchange', () => window.setTimeout(syncHeader, 0));

  document.addEventListener('paradigm:language-changed', () => {
    renderShell();
    if (palette && !palette.classList.contains('hidden')) renderPaletteResults();
  });

  const headerObserver = new MutationObserver(() => {
    if (!headerSyncing) window.queueMicrotask(syncHeader);
  });
  const pageTitle = document.getElementById('page-title');
  const pageSubtitle = document.getElementById('page-subtitle');
  if (pageTitle) headerObserver.observe(pageTitle, { childList: true, characterData: true, subtree: true });
  if (pageSubtitle) headerObserver.observe(pageSubtitle, { childList: true, characterData: true, subtree: true });

  const authObserver = new MutationObserver(() => {
    const next = document.body.classList.contains('is-authenticated');
    if (next === wasAuthenticated) return;
    wasAuthenticated = next;
    detectNetwork();
  });
  authObserver.observe(document.body, { attributes: true, attributeFilter: ['class'] });

  renderShell();
  detectNetwork();
})();