(() => {
  'use strict';

  function applyCapabilities() {
    document.querySelectorAll('#navigation [data-page-target]').forEach(button => {
      const visible = typeof window.pageAccessible === 'function' ? window.pageAccessible(button.dataset.pageTarget) : true;
      button.classList.toggle('hidden', !visible);
    });
    document.querySelectorAll('#navigation .nav-group').forEach(group => {
      const buttons = [...group.querySelectorAll('[data-page-target]')];
      const anyVisible = buttons.some(button => !button.classList.contains('hidden'));
      group.classList.toggle('hidden', !anyVisible);
      const heading = document.getElementById(group.getAttribute('aria-labelledby'));
      if (heading) heading.classList.toggle('hidden', !anyVisible);
    });
  }

  document.addEventListener('paradigm:capabilities-changed', applyCapabilities);
  document.addEventListener('paradigm:session-changed', applyCapabilities);
  if (window.ParadigmDashboardRuntime && typeof window.ParadigmDashboardRuntime.afterPageLoad === 'function') {
    window.ParadigmDashboardRuntime.afterPageLoad(applyCapabilities);
  }
  applyCapabilities();
})();
