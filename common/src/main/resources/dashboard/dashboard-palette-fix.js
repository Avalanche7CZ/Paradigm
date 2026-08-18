(() => {
  'use strict';

  function openPaletteFromTrigger() {
    document.dispatchEvent(new KeyboardEvent('keydown', {
      key: 'k',
      code: 'KeyK',
      ctrlKey: true,
      bubbles: true,
      cancelable: true
    }));
  }

  function replaceSearchTrigger() {
    const current = document.getElementById('nav-search');
    if (!current || current.dataset.paletteFocusFix === 'true') return;

    // dashboard-shell originally opens Ctrl+K on focus. closePalette() restores
    // focus to the trigger, which immediately reopens the dialog. Clone the
    // input to drop that anonymous focus listener and keep click/keyboard
    // activation explicit instead.
    const replacement = current.cloneNode(true);
    replacement.dataset.shellWired = 'true';
    replacement.dataset.paletteFocusFix = 'true';
    current.replaceWith(replacement);

    replacement.addEventListener('click', openPaletteFromTrigger);
    replacement.addEventListener('keydown', event => {
      if (event.key !== 'Enter' && event.key !== ' ') return;
      event.preventDefault();
      openPaletteFromTrigger();
    });
  }

  replaceSearchTrigger();
  document.addEventListener('paradigm:language-changed', () => window.setTimeout(replaceSearchTrigger, 0));
})();
