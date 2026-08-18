(() => {
  const STORAGE_KEY = 'paradigm-dashboard-language';
  const SUPPORTED = new Set(['en', 'cs', 'ru']);
  const nativeFetch = window.fetch.bind(window);
  const sourceText = new WeakMap();
  const appliedText = new WeakMap();
  const sourceAttrs = new WeakMap();
  const appliedAttrs = new WeakMap();
  let choice = localStorage.getItem(STORAGE_KEY) || 'auto';
  let locale = resolveLocale(choice);
  let catalog = { meta: { code: 'en', name: 'English' }, strings: {}, fields: {}, patterns: {} };
  let observer = null;
  let loadGeneration = 0;

  function resolveLocale(value) {
    if (SUPPORTED.has(value)) return value;
    const languages = Array.isArray(navigator.languages) && navigator.languages.length
      ? navigator.languages
      : [navigator.language || 'en'];
    for (const language of languages) {
      const code = String(language || '').toLowerCase().split('-')[0];
      if (SUPPORTED.has(code)) return code;
    }
    return 'en';
  }

  async function loadCatalog(nextLocale) {
    if (nextLocale === 'en') {
      try {
        const response = await nativeFetch('/lang/en.json', { credentials: 'same-origin', cache: 'no-cache' });
        if (response.ok) return await response.json();
      } catch (_) {}
      return { meta: { code: 'en', name: 'English' }, strings: {}, fields: {}, patterns: {} };
    }
    try {
      const response = await nativeFetch(`/lang/${nextLocale}.json`, { credentials: 'same-origin', cache: 'no-cache' });
      if (response.ok) return await response.json();
    } catch (_) {}
    return { meta: { code: nextLocale, name: nextLocale.toUpperCase() }, strings: {}, fields: {}, patterns: {} };
  }

  function translatedString(value) {
    const source = String(value ?? '');
    if (!source.trim()) return source;
    const leading = source.match(/^\s*/)?.[0] || '';
    const trailing = source.match(/\s*$/)?.[0] || '';
    const core = source.slice(leading.length, source.length - trailing.length || undefined);
    const exact = catalog.strings?.[core];
    if (typeof exact === 'string') return leading + exact + trailing;
    const pattern = translatePattern(core);
    return pattern == null ? source : leading + pattern + trailing;
  }

  function translatePattern(source) {
    const patterns = catalog.patterns || {};
    let match = source.match(/^Saved (\d+) changes?\.(.*)$/);
    if (match && patterns.savedChanges) {
      const count = Number(match[1]);
      let text = template(patterns.savedChanges, { count });
      const suffix = match[2] || '';
      if (suffix.includes('server restart') && patterns.restartRequired) text += ' ' + patterns.restartRequired;
      else if (suffix.includes('module reload') && patterns.reloadRequired) text += ' ' + patterns.reloadRequired;
      return text;
    }
    match = source.match(/^(\d+) unsaved changes?$/);
    if (match && patterns.unsavedChanges) return template(patterns.unsavedChanges, { count: Number(match[1]) });
    match = source.match(/^Page (\d+) of (\d+)$/);
    if (match && patterns.pageOf) return template(patterns.pageOf, { page: match[1], pages: match[2] });
    match = source.match(/^Showing (\d+)[–-](\d+) of (\d+)$/);
    if (match && patterns.showingRange) return template(patterns.showingRange, { from: match[1], to: match[2], total: match[3] });
    return null;
  }

  function template(value, replacements) {
    let result = String(value ?? '');
    for (const [key, replacement] of Object.entries(replacements || {})) {
      result = result.replaceAll(`{${key}}`, String(replacement));
    }
    return result;
  }

  function skipTextNode(node) {
    const parent = node?.parentElement;
    if (!parent) return true;
    if (parent.matches('[data-field-row] .config-label > strong, [data-field-row] .config-label > small')) return true;
    if (parent.tagName === 'OPTION' && !parent.hasAttribute('value')) return true;
    return Boolean(parent.closest('script, style, textarea, input, code, pre, [data-i18n-ignore]'));
  }

  function translateTextNode(node, externalMutation = false) {
    if (!node || skipTextNode(node)) return;
    const current = node.nodeValue || '';
    if (externalMutation && appliedText.get(node) !== current) sourceText.set(node, current);
    if (!sourceText.has(node)) sourceText.set(node, current);
    const next = locale === 'en' ? sourceText.get(node) : translatedString(sourceText.get(node));
    if (current !== next) node.nodeValue = next;
    appliedText.set(node, next);
  }

  function attributeState(element) {
    if (!sourceAttrs.has(element)) sourceAttrs.set(element, new Map());
    if (!appliedAttrs.has(element)) appliedAttrs.set(element, new Map());
    return { source: sourceAttrs.get(element), applied: appliedAttrs.get(element) };
  }

  function translateAttribute(element, name, externalMutation = false) {
    if (!element?.hasAttribute?.(name)) return;
    const current = element.getAttribute(name) || '';
    const state = attributeState(element);
    if (externalMutation && state.applied.get(name) !== current) state.source.set(name, current);
    if (!state.source.has(name)) state.source.set(name, current);
    const original = state.source.get(name);
    const next = locale === 'en' ? original : translatedString(original);
    if (current !== next) element.setAttribute(name, next);
    state.applied.set(name, next);
  }

  function translateFieldRow(row) {
    const key = row?.dataset?.fieldRow;
    if (!key) return;
    const label = row.querySelector('.config-label > strong');
    const help = row.querySelector('.config-label > small');
    if (label) {
      if (!row.dataset.i18nOriginalLabel) row.dataset.i18nOriginalLabel = label.textContent || '';
      const translated = catalog.fields?.[key]?.label;
      label.textContent = locale !== 'en' && translated ? translated : row.dataset.i18nOriginalLabel;
    }
    if (help) {
      if (!row.dataset.i18nOriginalHelp) row.dataset.i18nOriginalHelp = help.textContent || '';
      const translated = catalog.fields?.[key]?.help;
      help.textContent = locale !== 'en' && translated ? translated : row.dataset.i18nOriginalHelp;
    }
  }

  function translateElement(element, externalMutation = false) {
    if (!(element instanceof Element)) return;
    if (element.matches('[data-field-row]')) translateFieldRow(element);
    for (const name of ['aria-label', 'title', 'placeholder']) translateAttribute(element, name, externalMutation);
  }

  function translateTree(root, externalMutation = false) {
    if (!root) return;
    if (root.nodeType === Node.TEXT_NODE) {
      translateTextNode(root, externalMutation);
      return;
    }
    if (root.nodeType !== Node.ELEMENT_NODE && root.nodeType !== Node.DOCUMENT_NODE && root.nodeType !== Node.DOCUMENT_FRAGMENT_NODE) return;
    if (root.nodeType === Node.ELEMENT_NODE) translateElement(root, externalMutation);
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
    let node;
    while ((node = walker.nextNode())) {
      if (node.nodeType === Node.TEXT_NODE) translateTextNode(node, externalMutation);
      else translateElement(node, externalMutation);
    }
  }

  function ensureSelector() {
    let select = document.getElementById('dashboard-language');
    if (select) {
      select.value = choice;
      translateAttribute(select, 'aria-label');
      return;
    }
    const session = document.querySelector('.session');
    if (!session) return;
    select = document.createElement('select');
    select.id = 'dashboard-language';
    select.className = 'dashboard-language-select';
    select.setAttribute('aria-label', 'Dashboard language');
    select.style.width = 'auto';
    select.style.minWidth = '112px';
    select.style.maxWidth = '160px';
    select.innerHTML = [
      ['auto', 'Auto'],
      ['en', 'English'],
      ['cs', 'Čeština'],
      ['ru', 'Русский']
    ].map(([value, label]) => `<option value="${value}">${label}</option>`).join('');
    select.value = choice;
    select.addEventListener('change', () => setLanguage(select.value, true));
    const theme = document.getElementById('theme-toggle');
    session.insertBefore(select, theme || session.firstChild);
    translateAttribute(select, 'aria-label');
  }

  async function setLanguage(nextChoice, persist = true) {
    choice = ['auto', ...SUPPORTED].includes(nextChoice) ? nextChoice : 'auto';
    if (persist) localStorage.setItem(STORAGE_KEY, choice);
    const nextLocale = resolveLocale(choice);
    const generation = ++loadGeneration;
    const nextCatalog = await loadCatalog(nextLocale);
    if (generation !== loadGeneration) return;
    locale = nextLocale;
    catalog = nextCatalog || { meta: { code: locale }, strings: {}, fields: {}, patterns: {} };
    document.documentElement.lang = locale;
    ensureSelector();
    translateTree(document.body);
    document.dispatchEvent(new CustomEvent('paradigm:language-changed', { detail: { locale, choice } }));
  }

  function observe() {
    if (observer) return;
    observer = new MutationObserver(records => {
      const roots = new Set();
      for (const record of records) {
        if (record.type === 'characterData') {
          translateTextNode(record.target, true);
          continue;
        }
        if (record.type === 'attributes') {
          translateAttribute(record.target, record.attributeName, true);
          continue;
        }
        for (const node of record.addedNodes) roots.add(node);
      }
      for (const root of roots) translateTree(root, true);
      ensureSelector();
    });
    observer.observe(document.documentElement, {
      subtree: true,
      childList: true,
      characterData: true,
      attributes: true,
      attributeFilter: ['aria-label', 'title', 'placeholder']
    });
  }

  function installApiLocaleHeader() {
    window.fetch = (input, init = {}) => {
      let url;
      try {
        url = new URL(typeof input === 'string' ? input : input.url, window.location.href);
      } catch (_) {
        return nativeFetch(input, init);
      }
      if (url.origin !== window.location.origin || !url.pathname.startsWith('/api/')) {
        return nativeFetch(input, init);
      }
      const requestHeaders = input instanceof Request ? input.headers : undefined;
      const headers = new Headers(init.headers || requestHeaders || {});
      headers.set('X-Paradigm-Locale', locale);
      return nativeFetch(input, { ...init, headers });
    };
  }

  window.ParadigmI18n = {
    get locale() { return locale; },
    get choice() { return choice; },
    setLanguage,
    translateTree,
    translate: translatedString
  };

  installApiLocaleHeader();
  observe();
  setLanguage(choice, false);
})();
