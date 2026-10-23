/* MAIN-world observer. Page code can influence this metadata; the worker requires a real
 * webRequest and Mapper independently compares its captured bytes. Never send body bytes. */
(() => {
  if (globalThis.__featureMapperBodyAttached) return;
  globalThis.__featureMapperBodyAttached = true;
  const LIMIT = 512 * 1024;
  const bufferLength = Object.getOwnPropertyDescriptor(ArrayBuffer.prototype, 'byteLength').get;
  const typedPrototype = Object.getPrototypeOf(Uint8Array.prototype);
  const viewGetters = [typedPrototype, DataView.prototype].map(prototype => ({
    length: Object.getOwnPropertyDescriptor(prototype, 'byteLength').get,
    offset: Object.getOwnPropertyDescriptor(prototype, 'byteOffset').get,
    buffer: Object.getOwnPropertyDescriptor(prototype, 'buffer').get
  }));
  const blobSize = Object.getOwnPropertyDescriptor(Blob.prototype, 'size').get;
  const blobBytes = Blob.prototype.arrayBuffer;
  const copyBytes = Uint8Array.prototype.set;
  let enabled = false, pending = 0, generation = 0;
  const xhrRequests = new WeakMap();
  window.addEventListener('message', event => {
    if (event.source === window && event.origin === location.origin
        && event.data?.source === 'feature-mapper-body-control-v1') {
      const next = event.data.enabled === true;
      if (next !== enabled) generation++;
      enabled = next;
    }
  });
  // The isolated observer may have initialized before this script.
  window.postMessage({source: 'feature-mapper-body-ready-v1'}, location.origin);
  function describe(method, url, body) {
    if (!enabled || pending >= 4 || !crypto?.subtle || typeof method !== 'string' || typeof url !== 'string') return null;
    method = method.toUpperCase();
    if (['GET', 'HEAD'].includes(method)) return null;
    let target;
    try { target = new URL(url, location.href); } catch { return null; }
    if (target.origin !== location.origin) return null;
    target.hash = '';
    let bytes, blob = false, size;
    if (typeof body === 'string') {
      if (body.length > LIMIT) return null;
      bytes = new TextEncoder().encode(body);
    } else if (body instanceof URLSearchParams) {
      const text = URLSearchParams.prototype.toString.call(body);
      if (text.length > LIMIT) return null;
      bytes = new TextEncoder().encode(text);
    } else if (body instanceof ArrayBuffer) {
      const length = bufferLength.call(body);
      if (length > LIMIT) return null;
      // ArrayBuffer.slice consults the input's constructor/species. Copy without invoking it.
      bytes = new Uint8Array(length);
      copyBytes.call(bytes, new Uint8Array(body));
    } else if (ArrayBuffer.isView(body)) {
      let view;
      for (const getters of viewGetters) {
        try { view = {size: getters.length.call(body), offset: getters.offset.call(body), buffer: getters.buffer.call(body)}; break; } catch {}
      }
      if (!view || view.size > LIMIT) return null;
      bytes = new Uint8Array(view.size);
      copyBytes.call(bytes, new Uint8Array(view.buffer, view.offset, view.size));
    } else if (body instanceof Blob) {
      size = blobSize.call(body);
      if (size > LIMIT) return null;
      bytes = body; blob = true;
    } else return null; // FormData boundaries and arbitrary/streaming BodyInit are not guessed.
    if (!blob) size = viewGetters[0].length.call(bytes);
    if (size > LIMIT) return null;
    return {method, url: target.href, bytes, blob, size, time: Date.now(), generation};
  }
  function observe(value) {
    if (!value) return;
    pending++;
    (async () => {
      const bytes = value.blob ? await blobBytes.call(value.bytes) : value.bytes;
      const hash = await crypto.subtle.digest('SHA-256', bytes);
      if (!enabled || value.generation !== generation) return;
      const bodyHash = Array.from(new Uint8Array(hash), x => x.toString(16).padStart(2, '0')).join('');
      window.postMessage({source: 'feature-mapper-body-v1', method: value.method, url: value.url,
        time: value.time, size: value.size, bodyHash}, location.origin);
    })().catch(() => {}).finally(() => { pending--; });
  }
  const originalFetch = window.fetch;
  window.fetch = function(input, init) {
    let observation;
    try {
      // Reading accessors twice changes application behavior. Only inspect ordinary data properties.
      const method = init && Object.getOwnPropertyDescriptor(init, 'method');
      const body = init && Object.getOwnPropertyDescriptor(init, 'body');
      if (typeof input === 'string' && body && 'value' in body && (!method || 'value' in method))
        observation = describe(method?.value || 'GET', input, body.value);
    } catch {}
    const result = Reflect.apply(originalFetch, this, arguments);
    observe(observation);
    return result;
  };
  const originalOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url) {
    const result = Reflect.apply(originalOpen, this, arguments);
    xhrRequests.set(this, {method, url});
    return result;
  };
  const originalSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function(body) {
    let observation;
    try { const value = xhrRequests.get(this); if (value) observation = describe(value.method, value.url, body); } catch {}
    const result = Reflect.apply(originalSend, this, arguments);
    observe(observation);
    return result;
  };
})();
