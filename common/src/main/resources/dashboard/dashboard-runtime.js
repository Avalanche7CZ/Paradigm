(() => {
  'use strict';

  if (window.ParadigmDashboardRuntime) return;

  const pageHooks = [];
  const serverHooks = [];
  const apiObservers = [];
  let authEpoch = 0;
  let wasAuthenticated = document.body.classList.contains('is-authenticated');

  async function runHooks(hooks, ...args) {
    for (const hook of [...hooks]) {
      try {
        await hook(...args);
      } catch (error) {
        console.error('[Paradigm Dashboard] lifecycle hook failed', error);
      }
    }
  }

  function observe(hooks, callback) {
    if (typeof callback !== 'function') return () => {};
    hooks.push(callback);
    return () => {
      const index = hooks.indexOf(callback);
      if (index >= 0) hooks.splice(index, 1);
    };
  }

  function publishSessionChange(authenticated) {
    document.dispatchEvent(new CustomEvent('paradigm:session-changed', {
      detail: { authenticated, epoch: authEpoch }
    }));
  }

  function syncAuthEpoch() {
    const authenticated = document.body.classList.contains('is-authenticated');
    if (authenticated !== wasAuthenticated) {
      wasAuthenticated = authenticated;
      authEpoch += 1;
      publishSessionChange(authenticated);
    }
    return authEpoch;
  }

  const baseApi = api;
  api = async function dashboardRuntimeApi(path, options = {}) {
    const requestEpoch = syncAuthEpoch();
    const data = await baseApi(path, options);
    syncAuthEpoch();
    if (requestEpoch !== authEpoch) {
      const error = new Error('Dashboard session changed while the request was in flight.');
      error.code = 'stale_session';
      throw error;
    }
    const method = String(options?.method || 'GET').toUpperCase();
    for (const observer of [...apiObservers]) {
      try {
        observer({ path, method, options, data });
      } catch (error) {
        console.error('[Paradigm Dashboard] API observer failed', error);
      }
    }
    return data;
  };

  const baseLoadServers = loadServers;
  loadServers = async function dashboardRuntimeLoadServers(...args) {
    const result = await baseLoadServers(...args);
    const failed = Boolean(document.querySelector('#servers-table > .notice-inline'));
    if (!failed) await runHooks(serverHooks, state?.servers || []);
    return result;
  };

  const baseLoadPage = loadPage;
  loadPage = async function dashboardRuntimeLoadPage(page, ...args) {
    const result = await baseLoadPage(page, ...args);
    await runHooks(pageHooks, page);
    return result;
  };

  const authObserver = new MutationObserver(syncAuthEpoch);
  authObserver.observe(document.body, { attributes: true, attributeFilter: ['class'] });

  window.ParadigmDashboardRuntime = Object.freeze({
    afterPageLoad(callback) {
      return observe(pageHooks, callback);
    },
    afterServersLoad(callback) {
      return observe(serverHooks, callback);
    },
    observeApi(callback) {
      return observe(apiObservers, callback);
    },
    authEpoch() {
      return syncAuthEpoch();
    }
  });
})();
