(() => {
  'use strict';

  function clearElement(id) {
    const node = document.getElementById(id);
    if (!node) return;
    node.replaceChildren();
    node.classList.remove('is-loading');
  }

  function clearPlayerWorkspace() {
    const search = document.getElementById('shell-player-search');
    if (search) search.value = '';
    const count = document.getElementById('shell-player-count');
    if (count) count.textContent = '0';
    const known = document.getElementById('shell-known-count');
    if (known) known.textContent = '0';
    document.getElementById('shell-known-count-wrap')?.classList.add('hidden');
    clearElement('shell-player-list');
    clearElement('shell-player-detail');
  }

  function clearWorkspaceSurfaces() {
    clearPlayerWorkspace();
    clearElement('overview-grid');
    clearElement('overview-activity');
    clearElement('administration-cards');
    clearElement('command-center-cards');
    clearElement('servers-table');
    document.getElementById('permission-ux-overview')?.remove();
  }

  document.addEventListener('paradigm:session-changed', clearWorkspaceSurfaces);
})();