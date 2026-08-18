(() => {
  'use strict';

  const COPY = {
    en: {
      tps: 'TPS', mspt: 'MSPT',
      lowTps: 'TPS is below 18.0.',
      highMspt: 'Average tick time is above 50 ms.'
    },
    cs: {
      tps: 'TPS', mspt: 'MSPT',
      lowTps: 'TPS je pod 18,0.',
      highMspt: 'Průměrná délka ticku je nad 50 ms.'
    },
    ru: {
      tps: 'TPS', mspt: 'MSPT',
      lowTps: 'TPS ниже 18,0.',
      highMspt: 'Среднее время тика превышает 50 мс.'
    }
  };

  let latestOverview = null;
  let overviewRefreshInFlight = false;

  function locale() {
    const code = window.ParadigmI18n?.locale || document.documentElement.lang || 'en';
    return COPY[code] ? code : 'en';
  }

  function tr(key) {
    return COPY[locale()]?.[key] || COPY.en[key] || key;
  }

  function overviewActive() {
    return document.querySelector('.page.active')?.dataset.page === 'overview';
  }

  function metric(label, value, key) {
    const node = document.createElement('div');
    node.className = 'shell-overview-metric';
    node.dataset.performanceMetric = key;
    const labelNode = document.createElement('span');
    const valueNode = document.createElement('strong');
    labelNode.textContent = label;
    valueNode.textContent = value;
    node.append(labelNode, valueNode);
    return node;
  }

  function formatTps(value) {
    const number = Number(value);
    return Number.isFinite(number) && number >= 0 ? number.toFixed(1) : '—';
  }

  function formatMspt(value) {
    const number = Number(value);
    return Number.isFinite(number) && number >= 0 ? `${number.toFixed(1)} ms` : '—';
  }

  function localizePerformanceProblems(data, grid) {
    const problems = Array.isArray(data?.problems) ? data.problems : [];
    const rows = [...grid.querySelectorAll('.shell-health-card li')];
    problems.forEach((problem, index) => {
      const text = rows[index]?.querySelector('span');
      if (!text) return;
      if (problem?.code === 'low_tps') text.textContent = tr('lowTps');
      if (problem?.code === 'high_mspt') text.textContent = tr('highMspt');
    });
  }

  function renderPerformance(data = latestOverview) {
    if (!data || !overviewActive()) return;
    const grid = document.getElementById('overview-grid');
    if (!grid) return;

    localizePerformanceProblems(data, grid);

    const tick = data?.runtime?.tick;
    if (!tick?.available) {
      grid.querySelectorAll('[data-performance-metric]').forEach(node => node.remove());
      return;
    }

    const health = grid.querySelector('.shell-health-card');
    if (!health) return;

    let tps = grid.querySelector('[data-performance-metric="tps"]');
    let mspt = grid.querySelector('[data-performance-metric="mspt"]');
    if (!tps) tps = metric(tr('tps'), formatTps(tick.tps), 'tps');
    if (!mspt) mspt = metric(tr('mspt'), formatMspt(tick.mspt), 'mspt');

    const tpsLabel = tps.querySelector('span');
    const tpsValue = tps.querySelector('strong');
    const msptLabel = mspt.querySelector('span');
    const msptValue = mspt.querySelector('strong');
    if (tpsLabel) tpsLabel.textContent = tr('tps');
    if (tpsValue) tpsValue.textContent = formatTps(tick.tps);
    if (msptLabel) msptLabel.textContent = tr('mspt');
    if (msptValue) msptValue.textContent = formatMspt(tick.mspt);

    // Keep live tick metrics first without recreating the nodes every five seconds.
    health.after(tps, mspt);
  }

  async function refreshOverviewLive() {
    if (overviewRefreshInFlight
        || document.visibilityState !== 'visible'
        || !document.body.classList.contains('is-authenticated')
        || !overviewActive()) {
      return;
    }
    overviewRefreshInFlight = true;
    try {
      const data = await api('/api/overview');
      if (!overviewActive() || !document.body.classList.contains('is-authenticated')) return;
      if (window.ParadigmDashboardPolish?.patchOverview) {
        window.ParadigmDashboardPolish.patchOverview(data);
      } else if (typeof loadOverview === 'function') {
        await loadOverview();
      }
    } catch (_) {
      // Manual rendering and session handling own visible request errors.
    } finally {
      overviewRefreshInFlight = false;
    }
  }

  window.ParadigmDashboardRuntime?.observeApi(({ path, method, data }) => {
    if (path !== '/api/overview' || method !== 'GET') return;
    latestOverview = data;
    window.setTimeout(() => renderPerformance(data), 0);
  });

  document.addEventListener('paradigm:session-changed', () => {
    latestOverview = null;
    document.querySelectorAll('#overview-grid [data-performance-metric]').forEach(node => node.remove());
  });
  document.addEventListener('paradigm:language-changed', () => window.setTimeout(() => renderPerformance(), 0));
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') window.setTimeout(refreshOverviewLive, 0);
  });
  window.addEventListener('hashchange', () => window.setTimeout(() => {
    renderPerformance();
    refreshOverviewLive();
  }, 0));
  window.setInterval(refreshOverviewLive, 5_000);
})();
