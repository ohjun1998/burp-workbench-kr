(() => {
  if (globalThis.__featureMapperAttached) return;
  globalThis.__featureMapperAttached = true;
  let bodyEnabled = false, originsChanged = false;
  function syncBodyObserver() {
    window.postMessage({source: 'feature-mapper-body-control-v1', enabled: bodyEnabled}, location.origin);
  }
  chrome.storage.local.get({origins: []}).then(({origins}) => {
    if (originsChanged) return;
    bodyEnabled = origins.includes(location.origin); syncBodyObserver();
  }).catch(() => {});
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === 'local' && changes.origins) {
      originsChanged = true;
      bodyEnabled = (changes.origins.newValue || []).includes(location.origin); syncBodyObserver();
    }
  });
  window.addEventListener('message', event => {
    if (event.source !== window || event.origin !== location.origin) return;
    if (event.data?.source === 'feature-mapper-body-ready-v1') { syncBodyObserver(); return; }
    const value = event.data;
    if (!bodyEnabled || value?.source !== 'feature-mapper-body-v1'
        || typeof value.url !== 'string' || value.url.length > 8192
        || typeof value.method !== 'string' || !/^[A-Z]{1,32}$/.test(value.method)
        || typeof value.bodyHash !== 'string' || !/^[a-f0-9]{64}$/.test(value.bodyHash)
        || !Number.isInteger(value.size) || value.size < 0 || value.size > 512 * 1024
        || !Number.isFinite(value.time)) return;
    // Only hash metadata crosses worlds. Page data is untrusted and checked again by the worker.
    chrome.runtime.sendMessage({type: 'request-body', url: value.url, method: value.method,
      bodyHash: value.bodyHash, size: value.size, time: value.time}).catch(() => {});
  });
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
