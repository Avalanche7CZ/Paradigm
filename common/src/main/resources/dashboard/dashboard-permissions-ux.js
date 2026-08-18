(() => {
  'use strict';

  const COPY = {
    en: {
      groups: 'Groups', users: 'Users', nodes: 'Nodes',
      groupsHelp: 'Roles and inheritance', usersHelp: 'Configured subjects', nodesHelp: 'Discovered permission catalog',
      searchGroups: 'Search groups...', searchUsers: 'Search players...', searchNodes: 'Search permission nodes...',
      groupSettings: 'Group settings', saveMetadata: 'Save metadata', weight: 'Weight', noPrefix: 'No prefix',
      advancedOptions: 'Advanced options', global: 'Global', currentServer: 'Current server', currentNetwork: 'Current network', custom: 'Custom context', permanent: 'Permanent', temporary: 'Temporary'
    },
    cs: {
      groups: 'Skupiny', users: 'Uživatelé', nodes: 'Nody',
      groupsHelp: 'Role a dědičnost', usersHelp: 'Nastavené subjekty', nodesHelp: 'Katalog nalezených oprávnění',
      searchGroups: 'Hledat skupiny...', searchUsers: 'Hledat hráče...', searchNodes: 'Hledat permission nody...',
      groupSettings: 'Nastavení skupiny', saveMetadata: 'Uložit metadata', weight: 'Váha', noPrefix: 'Bez prefixu',
      advancedOptions: 'Pokročilé možnosti', global: 'Globální', currentServer: 'Aktuální server', currentNetwork: 'Aktuální síť', custom: 'Vlastní kontext', permanent: 'Trvalé', temporary: 'Dočasné'
    },
    ru: {
      groups: 'Группы', users: 'Пользователи', nodes: 'Узлы',
      groupsHelp: 'Роли и наследование', usersHelp: 'Настроенные субъекты', nodesHelp: 'Каталог обнаруженных прав',
      searchGroups: 'Поиск групп...', searchUsers: 'Поиск игроков...', searchNodes: 'Поиск прав...',
      groupSettings: 'Настройки группы', saveMetadata: 'Сохранить метаданные', weight: 'Вес', noPrefix: 'Без префикса',
      advancedOptions: 'Расширенные параметры', global: 'Глобально', currentServer: 'Текущий сервер', currentNetwork: 'Текущая сеть', custom: 'Пользовательский контекст', permanent: 'Постоянно', temporary: 'Временно'
    }
  };

  let scheduled = false;
  let summarySnapshot = null;
  let summaryEpoch = -1;

  function locale() {
    const code = window.ParadigmI18n?.locale || document.documentElement.lang || 'en';
    return COPY[code] ? code : 'en';
  }

  function tr(key) {
    return COPY[locale()]?.[key] || COPY.en[key] || key;
  }

  function setText(node, value) {
    if (node && node.textContent !== value) node.textContent = value;
  }

  function currentAuthEpoch() {
    return window.ParadigmDashboardRuntime?.authEpoch?.() ?? 0;
  }

  function permissionsPage() {
    return document.querySelector('[data-page="permissions"]');
  }

  function currentView() {
    return state?.permissionView || 'groups';
  }

  function schedule() {
    if (scheduled) return;
    scheduled = true;
    window.requestAnimationFrame(() => {
      scheduled = false;
      enhancePermissions();
    });
  }

  function ensureOverview() {
    const page = permissionsPage();
    const tabs = document.getElementById('permission-tabs');
    if (!page || !tabs) return;
    let root = document.getElementById('permission-ux-overview');
    if (!root) {
      root = document.createElement('div');
      root.id = 'permission-ux-overview';
      root.className = 'permission-ux-overview';
      tabs.before(root);
      root.addEventListener('click', event => {
        const card = event.target.closest('[data-permission-ux-view]');
        if (!card) return;
        document.querySelector(`[data-permission-view="${card.dataset.permissionUxView}"]`)?.click();
      });
    }

    const summary = summaryEpoch === currentAuthEpoch() && summarySnapshot ? summarySnapshot : {};
    const cards = [
      ['groups', tr('groups'), tr('groupsHelp'), summary.groups],
      ['users', tr('users'), tr('usersHelp'), summary.users],
      ['nodes', tr('nodes'), tr('nodesHelp'), summary.nodes]
    ];
    root.innerHTML = cards.map(([view, label, help, count]) => `
      <button type="button" data-permission-ux-view="${view}" class="permission-ux-card${currentView() === view ? ' is-active' : ''}">
        <span>${label}</span><strong>${Number.isFinite(Number(count)) ? Number(count) : '—'}</strong><small>${help}</small>
      </button>`).join('');
  }

  function contextualSearch() {
    const search = document.getElementById('permissions-search');
    if (!search) return;
    const view = currentView();
    search.placeholder = view === 'groups' ? tr('searchGroups') : view === 'users' ? tr('searchUsers') : tr('searchNodes');
  }

  function scopeLabel(value) {
    if (value === 'current_server') return tr('currentServer');
    if (value === 'current_network') return tr('currentNetwork');
    if (value === 'custom') return tr('custom');
    return tr('global');
  }

  function updateContextSummary(details, scope, expiry) {
    const summary = details?.querySelector(':scope > summary');
    if (!summary || !scope || !expiry) return;
    setText(summary.querySelector('strong'), tr('advancedOptions'));
    const expiryText = expiry.value === 'permanent' ? tr('permanent') : tr('temporary');
    setText(summary.querySelector('span'), `${scopeLabel(scope.value)} · ${expiryText}`);
    details.classList.toggle('is-nondefault', scope.value !== 'global' || expiry.value !== 'permanent');
  }

  function enhanceContextForm(form) {
    if (!form) return;
    const scope = [...form.querySelectorAll('select')].find(node => node.id.endsWith('-scope'));
    const expiry = [...form.querySelectorAll('select')].find(node => node.id.endsWith('-expiry-mode'));
    if (!scope || !expiry) return;

    if (form.dataset.permissionUxAdvanced === 'true') {
      updateContextSummary(form.querySelector(':scope > .permission-advanced-options'), scope, expiry);
      return;
    }

    const prefix = scope.id.slice(0, -'-scope'.length);
    const selectors = [
      `#${CSS.escape(prefix)}-scope`,
      `[data-context-custom="${CSS.escape(prefix)}"]`,
      `#${CSS.escape(prefix)}-expiry-mode`,
      `[data-expiry-duration="${CSS.escape(prefix)}"]`,
      `[data-expiry-exact="${CSS.escape(prefix)}"]`
    ];
    const labels = [];
    selectors.forEach(selector => {
      form.querySelectorAll(selector).forEach(node => {
        const label = node.matches('label') ? node : node.closest('label');
        if (label && !labels.includes(label)) labels.push(label);
      });
    });
    if (!labels.length) return;

    const details = document.createElement('details');
    details.className = 'permission-advanced-options';
    details.dataset.permissionPrefix = prefix;
    const summary = document.createElement('summary');
    summary.innerHTML = `<strong></strong><span></span>`;
    const grid = document.createElement('div');
    grid.className = 'permission-advanced-grid';
    labels.forEach(label => grid.appendChild(label));
    details.append(summary, grid);

    const action = [...form.children].find(node => node.tagName === 'BUTTON');
    if (action) form.insertBefore(details, action);
    else form.appendChild(details);
    form.dataset.permissionUxAdvanced = 'true';

    const update = () => updateContextSummary(details, scope, expiry);
    scope.addEventListener('change', update);
    expiry.addEventListener('change', update);
    update();
  }

  function updateGroupSettings(details, form, save) {
    const summary = details?.querySelector(':scope > summary');
    if (!summary || !form || !save) return;
    const weight = form.querySelector('#group-weight');
    const prefix = form.querySelector('#group-prefix');
    setText(summary.querySelector('strong'), tr('groupSettings'));
    const prefixText = String(prefix?.value || '').trim() || tr('noPrefix');
    setText(summary.querySelector('span'), `${tr('weight')} ${weight?.value || 0} · ${prefixText}`);
    setText(save, tr('saveMetadata'));
  }

  function enhanceGroupSettings(editor) {
    if (!editor) return;
    if (editor.dataset.permissionUxGroup === 'true') {
      const details = editor.querySelector(':scope > .permission-group-settings');
      const form = details?.querySelector('.compact-form');
      const save = details?.querySelector('#group-save-meta');
      if (details && form && save) {
        updateGroupSettings(details, form, save);
        return;
      }
      delete editor.dataset.permissionUxGroup;
    }

    const header = editor.querySelector('.permission-subject-header');
    const form = editor.querySelector(':scope > .compact-form');
    const save = editor.querySelector('#group-save-meta');
    if (!header || !form || !save) return;

    const details = document.createElement('details');
    details.className = 'permission-group-settings';
    const summary = document.createElement('summary');
    summary.innerHTML = '<strong></strong><span></span>';
    const body = document.createElement('div');
    body.className = 'permission-group-settings-body';
    form.before(details);
    body.appendChild(form);
    body.appendChild(save);
    details.append(summary, body);
    editor.dataset.permissionUxGroup = 'true';

    const update = () => updateGroupSettings(details, form, save);
    form.addEventListener('input', update);
    update();
  }

  function enhanceEditor() {
    const editor = document.getElementById('permission-editor');
    if (!editor) return;
    enhanceGroupSettings(editor);
    editor.querySelectorAll('.compact-form').forEach(enhanceContextForm);
  }

  function enhancePermissions() {
    ensureOverview();
    contextualSearch();
    enhanceEditor();
  }

  const page = permissionsPage();
  if (!page) return;

  const editor = document.getElementById('permission-editor');
  const summary = document.getElementById('permissions-summary');
  const targetList = document.getElementById('permission-target-list');
  const observer = new MutationObserver(schedule);
  if (editor) observer.observe(editor, { childList: true, subtree: true });
  if (summary) observer.observe(summary, { childList: true, characterData: true, subtree: true });
  if (targetList) observer.observe(targetList, { childList: true, subtree: true });

  window.ParadigmDashboardRuntime?.observeApi(({ path, method, data }) => {
    if (path !== '/api/permissions/summary' || method !== 'GET') return;
    summarySnapshot = data;
    summaryEpoch = currentAuthEpoch();
    schedule();
  });

  document.getElementById('permission-tabs')?.addEventListener('click', () => window.setTimeout(schedule, 0));
  document.addEventListener('paradigm:session-changed', () => {
    summarySnapshot = null;
    summaryEpoch = -1;
    document.getElementById('permission-ux-overview')?.remove();
  });
  document.addEventListener('paradigm:language-changed', schedule);
  window.addEventListener('hashchange', () => {
    if (location.hash === '#permissions') window.setTimeout(schedule, 0);
  });

  enhancePermissions();
})();
