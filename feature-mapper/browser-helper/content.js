(() => {
  if (globalThis.__featureMapperAttached) return;
  globalThis.__featureMapperAttached = true;
  function label(element) {
    if (!element) return '';
    const aria = element.getAttribute('aria-label');
    // Never read form input values, including passwords and submitted field data.
    return (aria || element.textContent || element.getAttribute('title') || (element.tagName === 'INPUT' ? element.getAttribute('name') : '') || '').replace(/\s+/g, ' ').trim().slice(0, 80);
  }
  function send(name, kind) {
    if (!name) return;
    chrome.runtime.sendMessage({type: 'action', kind, time: Date.now(), label: name, title: document.title, page: location.origin + location.pathname}).catch(() => {});
  }
  document.addEventListener('click', event => {
    if (!event.isTrusted) return;
    const target = event.target.closest?.('button, a[href], [role="button"], input[type="submit"]');
    if (target) send(label(target), 'click');
  }, true);
  document.addEventListener('submit', event => {
    if (!event.isTrusted) return;
    send(label(event.submitter) || event.target.getAttribute('aria-label') || '폼 제출', 'submit');
  }, true);
})();
