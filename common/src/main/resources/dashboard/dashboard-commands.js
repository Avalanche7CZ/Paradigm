(() => {
  'use strict';

  const COPY = {
    en: {
      commands: 'Commands', help: 'Custom definitions, built-in command availability, cooldowns and warmups in one workspace.', configuration: 'Configuration',
      custom: 'Custom commands', customHelp: 'Create structured commands with actions, permissions and aliases.',
      builtIn: 'Built-in commands', builtInHelp: 'Enable or disable Paradigm command roots.',
      timing: 'Timing', timingHelp: 'Configure cooldowns and warmups for commands.',
      definitions: 'Definitions', enabled: 'Enabled', settings: 'Settings', cooldowns: 'Cooldowns', warmups: 'Warmups',
      open: 'Open', back: 'Commands', unavailable: 'Unavailable'
    },
    cs: {
      commands: 'Příkazy', help: 'Vlastní definice, dostupnost vestavěných příkazů, cooldowny a warmupy na jednom místě.', configuration: 'Konfigurace',
      custom: 'Vlastní příkazy', customHelp: 'Strukturované příkazy s akcemi, oprávněními a aliasy.',
      builtIn: 'Vestavěné příkazy', builtInHelp: 'Zapnutí a vypnutí command rootů Paradigmu.',
      timing: 'Časování', timingHelp: 'Cooldowny a warmupy jednotlivých příkazů.',
      definitions: 'Definice', enabled: 'Zapnuto', settings: 'Nastavení', cooldowns: 'Cooldowny', warmups: 'Warmupy',
      open: 'Otevřít', back: 'Příkazy', unavailable: 'Nedostupné'
    },
    ru: {
      commands: 'Команды', help: 'Пользовательские команды, встроенные команды, cooldown и warmup в одном рабочем пространстве.', configuration: 'Конфигурация',
      custom: 'Пользовательские команды', customHelp: 'Структурированные команды с действиями, правами и псевдонимами.',
      builtIn: 'Встроенные команды', builtInHelp: 'Включение и отключение корневых команд Paradigm.',
      timing: 'Тайминги', timingHelp: 'Cooldown и warmup для команд.',
      definitions: 'Определения', enabled: 'Включено', settings: 'Настройки', cooldowns: 'Cooldown', warmups: 'Warmup',
      open: 'Открыть', back: 'Команды', unavailable: 'Недоступно'
    }
  };

  const CHILDREN = new Set(['customCommands', 'commands', 'cooldowns']);
  let installed = false;
  let renderingHub = false;
  let snapshotEpoch = -1;
  let snapshotLoading = null;
  let snapshotLoadingEpoch = -1;
  let loadGeneration = 0;

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

  function setText(node, value) {
    if (node && node.textContent !== value) node.textContent = value;
  }

  function unavailable() {
    return `<span class="command-center-unavailable">${esc(tr('unavailable'))}</span>`;
  }

  function currentPage() {
    return document.querySelector('.page.active')?.dataset.page || location.hash.slice(1) || 'overview';
  }

  function currentAuthEpoch() {
    return window.ParadigmDashboardRuntime?.authEpoch?.() ?? 0;
  }

  function effectiveValue(field) {
    if (!field) return null;
    if (state?.edits?.has?.(field.key)) return state.edits.get(field.key);
    return field.value?.value;
  }

  function ensurePage() {
    let page = document.querySelector('[data-page="commandCenter"]');
    if (page) return page;
    const app = document.getElementById('app-panel');
    const saveBar = document.getElementById('save-bar');
    if (!app) return null;
    page = document.createElement('section');
    page.dataset.page = 'commandCenter';
    page.className = 'page commands-center';
    page.innerHTML = `
      <button type="button" class="command-center-home-back"></button>
      <div class="page-toolbar">
        <div><strong data-command-center-copy="commands"></strong><span data-command-center-copy="help"></span></div>
        <button type="button" id="command-center-refresh"></button>
      </div>
      <div id="command-center-cards" class="command-center-cards"></div>`;
    if (saveBar) app.insertBefore(page, saveBar);
    else app.appendChild(page);
    page.querySelector('.command-center-home-back')?.addEventListener('click', () => requestNavigate('general'));
    page.querySelector('#command-center-refresh')?.addEventListener('click', () => loadCommandCenter(true));
    translatePage(page);
    return page;
  }

  function translatePage(page = document.querySelector('[data-page="commandCenter"]')) {
    if (!page) return;
    page.querySelectorAll('[data-command-center-copy]').forEach(node => {
      setText(node, tr(node.dataset.commandCenterCopy));
    });
    setText(page.querySelector('#command-center-refresh'), window.ParadigmI18n?.t?.('Refresh') || 'Refresh');
    setText(page.querySelector('.command-center-home-back'), `← ${tr('configuration')}`);
  }

  function childLabels() {
    const labels = [];
    for (const page of CHILDREN) {
      const heading = document.querySelector(`[data-page="${page}"] > .page-toolbar strong`)?.textContent?.trim();
      if (heading) labels.push(heading);
      const fallback = pageInfo?.[page]?.[0];
      if (fallback) labels.push(fallback);
    }
    return labels;
  }

  function ensureConfigHubEntry() {
    if (renderingHub) return;
    const grid = document.querySelector('#configuration-hub .shell-hub-grid');
    if (!grid) return;
    renderingHub = true;
    try {
      const directLabels = new Set(childLabels());
      grid.querySelectorAll('.shell-card-links button').forEach(button => {
        if (directLabels.has(button.textContent.trim())) button.remove();
      });

      let card = grid.querySelector('[data-command-center-hub]');
      if (!card) {
        card = document.createElement('article');
        card.className = 'shell-hub-card command-center-hub-card';
        card.dataset.commandCenterHub = 'true';
        card.innerHTML = '<h3></h3><p></p><div class="shell-card-links"><button type="button" class="shell-card-link"></button></div>';
        card.querySelector('button').addEventListener('click', () => requestNavigate('commandCenter'));
        grid.appendChild(card);
      }
      setText(card.querySelector('h3'), tr('commands'));
      setText(card.querySelector('p'), tr('help'));
      setText(card.querySelector('button'), tr('open'));
    } finally {
      renderingHub = false;
    }
  }

  function ensureChildBreadcrumb(page) {
    document.querySelectorAll('.command-center-breadcrumb').forEach(node => {
      if (node.closest('.page')?.dataset.page !== page) node.remove();
    });
    if (!CHILDREN.has(page)) return;
    const section = document.querySelector(`[data-page="${page}"]`);
    if (!section) return;
    let breadcrumb = section.querySelector(':scope > .command-center-breadcrumb');
    if (!breadcrumb) {
      breadcrumb = document.createElement('div');
      breadcrumb.className = 'command-center-breadcrumb';
      breadcrumb.innerHTML = '<button type="button"></button><span aria-hidden="true">/</span><strong></strong>';
      breadcrumb.querySelector('button').addEventListener('click', () => requestNavigate('commandCenter'));
      section.prepend(breadcrumb);
    }
    setText(breadcrumb.querySelector('button'), `← ${tr('back')}`);
    setText(breadcrumb.querySelector('strong'), document.querySelector(`[data-page="${page}"] > .page-toolbar strong`)?.textContent?.trim() || pageInfo?.[page]?.[0] || page);
  }

  function stats(rows) {
    return `<div class="command-center-stats">${rows.map(([label, value]) => `<div><span>${esc(label)}</span><strong>${esc(value)}</strong></div>`).join('')}</div>`;
  }

  function card(page, title, help, body) {
    return `<article class="command-center-card">
      <div><span class="shell-kicker">${esc(title)}</span><h2>${esc(title)}</h2><p>${esc(help)}</p>${body}</div>
      <button type="button" data-command-center-go="${esc(page)}">${esc(tr('open'))}</button>
    </article>`;
  }

  async function refreshSnapshot(force) {
    const epoch = currentAuthEpoch();
    const needsRefresh = force || !state.snapshot || snapshotEpoch !== epoch;
    if (!needsRefresh) return true;
    if (snapshotLoading && snapshotLoadingEpoch === epoch) return snapshotLoading;
    if (typeof loadConfigSnapshot !== 'function') {
      snapshotEpoch = -1;
      return false;
    }

    let request;
    request = (async () => {
      const before = state.snapshot;
      snapshotEpoch = -1;
      await loadConfigSnapshot();
      if (currentAuthEpoch() !== epoch) return false;
      if (state.snapshot && state.snapshot !== before) {
        snapshotEpoch = epoch;
        return true;
      }
      return false;
    })();
    snapshotLoading = request;
    snapshotLoadingEpoch = epoch;
    try {
      return await request;
    } finally {
      if (snapshotLoading === request) {
        snapshotLoading = null;
        snapshotLoadingEpoch = -1;
      }
    }
  }

  async function loadCommandCenter(forceSnapshot = false) {
    const root = document.getElementById('command-center-cards');
    if (!root) return;
    const generation = ++loadGeneration;
    root.classList.add('is-loading');
    try {
      const snapshotAvailable = await refreshSnapshot(forceSnapshot);
      if (generation !== loadGeneration) return;
      const fields = snapshotAvailable ? (state.snapshot?.fields || []) : [];
      const commandFields = fields.filter(field => field.category === 'commands');
      const timingFields = fields.filter(field => field.category === 'cooldowns');
      const enabled = commandFields.filter(field => field.type === 'BOOLEAN' && effectiveValue(field) === true).length;
      const cooldownCount = timingFields.filter(field => String(field.key || '').startsWith('cooldowns.cooldown.')).length;
      const warmupCount = timingFields.filter(field => String(field.key || '').startsWith('cooldowns.warmup.')).length;

      let customBody;
      try {
        const data = await api('/api/custom-commands?query=');
        if (generation !== loadGeneration) return;
        const commands = Array.isArray(data?.commands) ? data.commands : [];
        customBody = stats([[tr('definitions'), commands.length]]);
      } catch (_) {
        if (generation !== loadGeneration) return;
        customBody = unavailable();
      }

      root.innerHTML = [
        card('customCommands', tr('custom'), tr('customHelp'), customBody),
        card('commands', tr('builtIn'), tr('builtInHelp'), snapshotAvailable ? stats([[tr('enabled'), enabled], [tr('settings'), commandFields.length]]) : unavailable()),
        card('cooldowns', tr('timing'), tr('timingHelp'), snapshotAvailable ? stats([[tr('cooldowns'), cooldownCount], [tr('warmups'), warmupCount]]) : unavailable())
      ].join('');
      root.querySelectorAll('[data-command-center-go]').forEach(button => button.addEventListener('click', () => requestNavigate(button.dataset.commandCenterGo)));
    } catch (_) {
      if (generation !== loadGeneration) return;
      root.innerHTML = [
        card('customCommands', tr('custom'), tr('customHelp'), unavailable()),
        card('commands', tr('builtIn'), tr('builtInHelp'), unavailable()),
        card('cooldowns', tr('timing'), tr('timingHelp'), unavailable())
      ].join('');
      root.querySelectorAll('[data-command-center-go]').forEach(button => button.addEventListener('click', () => requestNavigate(button.dataset.commandCenterGo)));
    } finally {
      if (generation === loadGeneration) root.classList.remove('is-loading');
    }
  }

  function install() {
    if (installed) return;
    installed = true;
    pageInfo.commandCenter = [tr('commands'), tr('help')];
    ensurePage();
    ensureConfigHubEntry();
    ensureChildBreadcrumb(currentPage());
    window.ParadigmDashboardRuntime?.afterPageLoad(async page => {
      ensureConfigHubEntry();
      ensureChildBreadcrumb(page);
      if (page === 'commandCenter') await loadCommandCenter();
    });
  }

  const hubObserver = new MutationObserver(() => {
    if (!renderingHub) window.queueMicrotask(ensureConfigHubEntry);
  });
  const generalPage = document.querySelector('[data-page="general"]');
  if (generalPage) hubObserver.observe(generalPage, { childList: true, subtree: true });

  document.addEventListener('paradigm:language-changed', () => {
    pageInfo.commandCenter = [tr('commands'), tr('help')];
    translatePage();
    ensureConfigHubEntry();
    ensureChildBreadcrumb(currentPage());
    if (currentPage() === 'commandCenter') {
      setText(document.getElementById('page-title'), tr('commands'));
      setText(document.getElementById('page-subtitle'), tr('help'));
      document.title = `${tr('commands')} · Paradigm Dashboard`;
      loadCommandCenter();
    }
  });
  window.addEventListener('hashchange', () => window.setTimeout(() => ensureChildBreadcrumb(currentPage()), 0));

  install();
})();
