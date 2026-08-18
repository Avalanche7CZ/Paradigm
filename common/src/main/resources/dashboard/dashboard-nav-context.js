(() => {
  'use strict';

  const CONFIG_CHILDREN = new Set([
    'teleports', 'chat', 'announcements', 'restart', 'motd', 'tablist', 'holograms',
    'customCommands', 'commands', 'cooldowns', 'discord', 'commandCenter'
  ]);
  const SETTINGS_CHILDREN = new Set(['storage', 'storageConfig']);
  let installed = false;

  function activePage() {
    return document.querySelector('.page.active')?.dataset.page || state?.page || location.hash.slice(1) || 'overview';
  }

  function setParentState(button, active, exact) {
    if (!button) return;
    button.classList.toggle('active', active);
    if (exact) button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');
  }

  function syncNavigationContext(page = activePage()) {
    const configuration = document.querySelector('#navigation [data-page-target="general"]');
    const settings = document.querySelector('#navigation [data-page-target="dashboard"]');
    setParentState(configuration, page === 'general' || CONFIG_CHILDREN.has(page), page === 'general');
    setParentState(settings, page === 'dashboard' || SETTINGS_CHILDREN.has(page), page === 'dashboard');
  }

  function install() {
    if (installed) return;
    installed = true;
    window.ParadigmDashboardRuntime?.afterPageLoad(syncNavigationContext);
    syncNavigationContext();
  }

  document.addEventListener('paradigm:language-changed', () => window.setTimeout(syncNavigationContext, 0));
  window.addEventListener('hashchange', () => window.setTimeout(syncNavigationContext, 0));
  window.addEventListener('popstate', () => window.setTimeout(syncNavigationContext, 0));

  install();
})();
