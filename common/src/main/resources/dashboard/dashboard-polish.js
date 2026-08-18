(() => {
  'use strict';

  /*
   * Small runtime corrections that are easier to keep isolated from app.js.
   * This file must not own navigation, themes, or page rendering. It only
   * normalizes page actions and patches already-rendered Overview values.
   */

  let forwardedAction = null;
  let forwardedToolbar = null;

  const legacyRestorePageAction = typeof window.restorePageAction === 'function'
    ? window.restorePageAction
    : null;

  try {
    legacyRestorePageAction?.();
  } catch (_) {
    // The old helper may have nothing mounted yet.
  }

  function restorePageActionPolish() {
    if (forwardedAction && forwardedToolbar && forwardedToolbar.isConnected) {
      forwardedToolbar.appendChild(forwardedAction);
      forwardedToolbar.classList.remove('page-action-forwarded');
    }
    forwardedAction = null;
    forwardedToolbar = null;
    document.getElementById('page-actions')?.replaceChildren();
  }

  function syncPageActionPolish() {
    restorePageActionPolish();
    const toolbar = document.querySelector('.page.active > .page-toolbar');
    const action = toolbar?.children?.[1];
    const mount = document.getElementById('page-actions');
    if (!toolbar || !action || !mount) return;

    forwardedAction = action;
    forwardedToolbar = toolbar;
    toolbar.classList.add('page-action-forwarded');
    mount.appendChild(action);
  }

  // app.js calls these globals after navigation, theme changes and logout.
  // Replace the old dark-only forwarding with the same behavior in both themes.
  window.restorePageAction = restorePageActionPolish;
  window.syncThemeLayout = syncPageActionPolish;

  function duration(value) {
    const seconds = Math.max(0, Math.floor(Number(value || 0) / 1000));
    const days = Math.floor(seconds / 86400);
    const hours = Math.floor((seconds % 86400) / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    return days ? `${days}d ${hours}h` : hours ? `${hours}h ${minutes}m` : `${minutes}m`;
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

  function overviewValues(data) {
    const runtime = data?.runtime || {};
    const live = Array.isArray(data?.players) ? data.players : [];
    const online = Number(data?.onlinePlayers ?? live.length);
    const max = Number(data?.maxPlayers || 0);
    const values = [
      ['online', max > 0 ? `${online} / ${max}` : String(online)],
      ['uptime', duration(data?.uptimeMs)],
      ['storage', data?.activeProvider || '-'],
      ['modules', `${data?.modules?.enabled ?? 0} / ${data?.modules?.total ?? 0}`]
    ];

    const heapMax = Number(runtime.heapMaxBytes || 0);
    const heapUsed = Number(runtime.heapUsedBytes || 0);
    if (heapMax > 0) values.push(['heap', `${formatBytes(heapUsed)} / ${formatBytes(heapMax)}`]);
    if (Number(runtime.processCpuLoad) >= 0) values.push(['processCpu', formatPercent(runtime.processCpuLoad)]);
    if (Number(runtime.systemCpuLoad) >= 0) values.push(['systemCpu', formatPercent(runtime.systemCpuLoad)]);
    if (runtime.liveThreads != null) values.push(['threads', String(runtime.liveThreads)]);
    if (data?.discord?.enabled) values.push(['discord', data.discord.summary || data.discord.state || '—']);
    return values;
  }

  function bindOverviewMetricKeys(grid, values) {
    const nodes = [...grid.querySelectorAll('.shell-overview-metric:not([data-performance-metric])')];
    nodes.forEach((node, index) => {
      if (!node.dataset.overviewMetric && values[index]) node.dataset.overviewMetric = values[index][0];
    });
  }

  function hasOverviewProblems(data) {
    if (Array.isArray(data?.problems) && data.problems.length) return true;
    return Array.isArray(data?.warnings) && data.warnings.length > 0;
  }

  function patchOverview(data) {
    if (document.querySelector('.page.active')?.dataset.page !== 'overview') return;
    const grid = document.getElementById('overview-grid');
    const health = grid?.querySelector('.shell-health-card');
    if (!grid || !health || !grid.classList.contains('shell-overview-grid')) {
      if (typeof window.loadOverview === 'function') window.loadOverview();
      return;
    }

    // Health transitions are rare but structural. Let the normal renderer own them.
    const currentlyWarning = health.classList.contains('has-warning');
    if (currentlyWarning !== hasOverviewProblems(data)) {
      if (typeof window.loadOverview === 'function') window.loadOverview();
      return;
    }

    const values = overviewValues(data);
    bindOverviewMetricKeys(grid, values);
    for (const [key, value] of values) {
      const target = grid.querySelector(`.shell-overview-metric[data-overview-metric="${CSS.escape(key)}"] strong`);
      if (target && target.textContent !== value) target.textContent = value;
    }

    const identity = [
      data?.serverName || data?.serverId,
      data?.minecraftVersion,
      data?.loader,
      data?.version ? `Paradigm ${data.version}` : ''
    ].filter(Boolean).join(' · ');
    const identityNode = health.querySelector('small');
    if (identityNode && identityNode.textContent !== identity) identityNode.textContent = identity;
  }

  window.ParadigmDashboardPolish = {
    ...(window.ParadigmDashboardPolish || {}),
    patchOverview,
    syncPageAction: syncPageActionPolish
  };

  syncPageActionPolish();
  window.addEventListener('hashchange', () => window.setTimeout(syncPageActionPolish, 0));
  document.addEventListener('paradigm:language-changed', () => window.setTimeout(syncPageActionPolish, 0));
})();
