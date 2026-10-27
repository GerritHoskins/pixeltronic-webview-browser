const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const ts = require('typescript');

const source = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/plugin.ts'), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2017, esModuleInterop: true },
}).outputText;

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function setup({ observeFailure = false, layout = false } = {}) {
  const calls = [], observers = [], images = [], revoked = [];
  const backend = new Proxy({}, { get: (target, key) => target[key] ?? (async (...args) => {
    calls.push([key, ...args]);
    if (key === 'addListener') return { remove: async () => calls.push(['remove']) };
    if (key === 'captureScreen') return { src: 'anBlZw==' };
  }) });
  class Observer {
    constructor(callback) { this.callback = callback; this.active = false; observers.push(this); }
    observe() { this.active = true; if (observeFailure) throw new Error('observer failed'); }
    disconnect() { this.active = false; }
  }
  class TestURL extends URL {
    static createObjectURL() { return 'blob:test-' + images.length; }
    static revokeObjectURL(url) { revoked.push(url); }
  }
  const platform = { native: true };
  function target() {
    const listeners = new Map();
    return { addEventListener: (event, callback) => listeners.set(event, callback),
      removeEventListener: event => listeners.delete(event), emit: event => listeners.get(event)?.(), listeners };
  }
  const frames = new Map(); let nextFrame = 0;
  const window = { ...target(), devicePixelRatio: 2,
    requestAnimationFrame: callback => { frames.set(++nextFrame, callback); return nextFrame; },
    cancelAnimationFrame: id => frames.delete(id) };
  if (layout) Object.assign(window, { innerWidth: 400, innerHeight: 800,
    visualViewport: { ...target(), width: 400, height: 800, offsetLeft: 0, offsetTop: 0 } });
  const context = {
    exports: {}, console, URL: TestURL, Blob, setTimeout, clearTimeout,
    window,
    fetch: async () => ({ arrayBuffer: async () => new ArrayBuffer(4) }),
    Image: class { constructor() { images.push(this); } },
    require: name => name === '@capacitor/core'
      ? { Capacitor: { isNativePlatform: () => platform.native }, registerPlugin: () => backend } : Observer,
  };
  vm.runInNewContext(source, context);
  const plugin = new context.exports.InAppBrowserClass();
  const element = { style: {}, getBoundingClientRect: () => ({ width: 300, height: 400, x: -5, y: 10 }) };
  const open = () => plugin.openWebView({ url: 'https://example.com', element });
  const flushLayout = async () => { const pending = [...frames.values()]; frames.clear(); pending.forEach(callback => callback()); await tick(); };
  return { plugin, backend, calls, observers, images, revoked, platform, element, open, window, frames, flushLayout };
}

const code = expected => error => error.code === expected;
const tick = () => new Promise(resolve => setImmediate(resolve));

test('every public event returns an independently removable handle', async () => {
  const s = setup(); const callbacks = new Map(); let removed = 0, received = 0;
  s.backend.addListener = async (name, callback) => {
    callbacks.set(name, callback); return { remove: async () => { removed++; } };
  };
  for (const [method, name, payload] of [
    ['onPageLoaded', 'pageLoaded', { isLoading: true }],
    ['onPageLoadError', 'pageLoadError', { errorCode: 500 }],
    ['onUpdateDimensions', 'updateDimensions', { width: 10, height: 20, x: 0, y: 0 }],
    ['onNavigation', 'navigationHandler', { allowed: true, reason: 'ALLOWED' }],
  ]) {
    const handle = await s.plugin[method](() => { received++; });
    callbacks.get(name)(payload); assert.equal(received, removed + 1);
    await Promise.all([handle.remove(), handle.remove()]);
    callbacks.get(name)(payload); assert.equal(received, removed);
    await handle.remove();
  }
  await s.plugin.closeWebView(); assert.equal(removed, 4);
});

test('one listener can be removed while another remains active; close suppresses queued callbacks', async () => {
  const s = setup(); const callbacks = []; let removes = 0, a = 0, b = 0;
  s.backend.addListener = async (_, callback) => { callbacks.push(callback); return { remove: async () => { removes++; } }; };
  const first = await s.plugin.onPageLoaded(() => { a++; });
  await s.plugin.onPageLoaded(() => { b++; });
  await first.remove(); callbacks.forEach(callback => callback({ isLoading: false }));
  assert.equal(a, 0); assert.equal(b, 1);
  await s.plugin.closeWebView(); callbacks.forEach(callback => callback({ isLoading: false }));
  assert.equal(a, 0); assert.equal(b, 1); assert.equal(removes, 2);
});

test('failed listener removal can be retried and suppresses callbacks immediately', async () => {
  const s = setup(); let emit, attempts = 0, received = 0;
  s.backend.addListener = async (_, callback) => {
    emit = callback; return { remove: () => { if (++attempts === 1) throw new Error('remove failed'); } };
  };
  const handle = await s.plugin.onPageLoaded(() => { received++; });
  await assert.rejects(handle.remove(), /remove failed/); emit({ isLoading: true }); assert.equal(received, 0);
  await handle.remove(); await s.plugin.closeWebView(); assert.equal(attempts, 2);
});

test('invalid event callbacks reject at registration', async () => {
  const s = setup();
  for (const method of ['onPageLoaded', 'onPageLoadError', 'onNavigation', 'onUpdateDimensions']) {
    await assert.rejects(s.plugin[method](null), code('INVALID_ARGUMENT'));
  }
  assert.equal(s.calls.length, 0);
});

test('scroll and viewport events coalesce, preserve CSS fractions and detach cleanly', async () => {
  const s = setup({ layout: true }); await s.open();
  s.element.getBoundingClientRect = () => ({ width: 300.25, height: 400.5, x: 11.5, y: 22.75 });
  s.window.emit('scroll'); s.window.emit('resize'); s.window.visualViewport.emit('scroll');
  assert.equal(s.frames.size, 1);
  await s.flushLayout();
  let updates = s.calls.filter(call => call[0] === 'updateDimensions');
  assert.equal(updates.length, 1);
  assert.equal(updates[0][1].x, 11.5); assert.equal(updates[0][1].width, 300.25);
  s.window.emit('scroll'); await s.flushLayout();
  assert.equal(s.calls.filter(call => call[0] === 'updateDimensions').length, 1);
  s.window.visualViewport.height = 300; s.window.visualViewport.offsetTop = 20;
  s.window.visualViewport.emit('resize'); await s.flushLayout();
  updates = s.calls.filter(call => call[0] === 'updateDimensions');
  assert.equal(updates.at(-1)[1].viewportHeight, 300); assert.equal(updates.at(-1)[1].offsetTop, 20);
  s.window.emit('scroll'); await s.plugin.closeWebView();
  assert.equal(s.frames.size, 0); assert.equal(s.window.listeners.size, 0);
  assert.equal(s.window.visualViewport.listeners.size, 0);
});

test('detached, zero size and offscreen containers hide without invalid dimension updates', async () => {
  const s = setup({ layout: true }); await s.open();
  s.element.isConnected = false; s.window.emit('scroll'); await s.flushLayout();
  assert.equal(s.calls.at(-1)[0], 'setLayoutVisibility'); assert.equal(s.calls.at(-1)[1].visible, false);
  await assert.rejects(s.plugin.captureScreen(true), code('NOT_VISIBLE'));
  s.element.isConnected = true;
  s.element.getBoundingClientRect = () => ({ width: 0, height: 0, x: 0, y: 0 });
  s.window.emit('resize'); await s.flushLayout();
  assert.equal(s.calls.filter(call => call[0] === 'updateDimensions').length, 0);
  s.element.getBoundingClientRect = () => ({ width: 100, height: 100, x: 0, y: 900 });
  s.window.emit('scroll'); await s.flushLayout();
  s.element.getBoundingClientRect = () => ({ width: 100, height: 100, x: 0, y: 10 });
  s.window.emit('scroll'); await s.flushLayout();
  assert.equal(s.calls.at(-1)[1].visible, true);
  await s.plugin.closeWebView();
});

test('automatic geometry updates serialize and deliver the latest position', async () => {
  const s = setup({ layout: true }); await s.open();
  const pending = deferred(); let active = 0, peak = 0, count = 0;
  s.backend.updateDimensions = async () => { peak = Math.max(peak, ++active); if (++count === 1) await pending.promise; active--; };
  s.element.getBoundingClientRect = () => ({ width: 100, height: 100, x: count ? 50 : 20, y: 10 });
  s.window.emit('scroll'); await s.flushLayout();
  s.window.emit('scroll'); await s.flushLayout();
  pending.resolve(); await tick();
  assert.equal(count, 2); assert.equal(peak, 1);
  await s.plugin.closeWebView();
});

test('snapshot release never captures again and restores owned background styles', async () => {
  const s = setup(); s.element.style.backgroundImage = 'linear-gradient(red, blue)';
  s.element.style.backgroundSize = 'cover'; await s.open();
  const capturing = s.plugin.captureScreen(true); await tick(); s.images[0].onload(); await capturing;
  await s.plugin.captureScreen(true);
  assert.equal(s.calls.filter(call => call[0] === 'captureScreen').length, 1);
  s.element.style.backgroundRepeat = 'repeat-x';
  await s.plugin.captureScreen(false);
  assert.equal(s.calls.filter(call => call[0] === 'captureScreen').length, 1);
  assert.equal(s.element.style.backgroundImage, 'linear-gradient(red, blue)');
  assert.equal(s.element.style.backgroundSize, 'cover'); assert.equal(s.element.style.backgroundRepeat, 'repeat-x');
  assert.equal(s.revoked.length, 1);
  await s.plugin.closeWebView(); assert.equal(s.revoked.length, 1);
});

test('manual hiding during decode cannot install a snapshot or reveal a hidden browser', async () => {
  const s = setup(); await s.open();
  const capture = assert.rejects(s.plugin.captureScreen(true), code('NOT_VISIBLE'));
  await tick(); await s.plugin.hideWebView(); s.images[0].onload(); await capture;
  assert.equal(s.calls.filter(call => call[0] === 'setSnapshotMode').length, 0);
  assert.equal(s.revoked.length, 1);
  await assert.rejects(s.plugin.captureScreen(true), code('NOT_VISIBLE'));
  await s.plugin.showWebView(); await s.plugin.closeWebView();
});

test('headers reject case duplicates and controls before native allocation', async () => {
  for (const headers of [{ Authorization: 'a', authorization: 'b' }, { Test: 'a\u0000b' }, { Test: 'a\u007fb' }]) {
    const s = setup();
    await assert.rejects(s.plugin.openWebView({ url: 'https://example.com', element: s.element, headers }), code('INVALID_ARGUMENT'));
    assert.equal(s.calls.length, 0);
  }
});

test('a page load invalidates the displayed snapshot and releases its URL', async () => {
  const s = setup(); let loading;
  s.backend.addListener = async (name, callback) => { if (name === 'pageLoaded') loading = callback; return { remove: async () => {} }; };
  await s.open();
  const capture = s.plugin.captureScreen(true); await tick(); s.images[0].onload(); await capture;
  loading({ isLoading: true });
  assert.equal(s.revoked.length, 1); assert.equal(s.element.style.backgroundImage, '');
  await s.plugin.closeWebView(); assert.equal(s.revoked.length, 1);
});

test('showing during screenshot decode cancels the stale snapshot', async () => {
  const s = setup(); await s.open();
  const capture = assert.rejects(s.plugin.captureScreen(true), code('CANCELLED'));
  await tick(); await s.plugin.showWebView(); s.images[0].onload(); await capture;
  assert.equal(s.calls.filter(call => call[0] === 'setSnapshotMode').length, 0);
  assert.equal(s.revoked.length, 1); await s.plugin.closeWebView();
});

test('a page change during the native snapshot switch restores live mode', async () => {
  const s = setup(); let loading;
  s.backend.addListener = async (name, callback) => { if (name === 'pageLoaded') loading = callback; return { remove: async () => {} }; };
  const modes = [];
  s.backend.setSnapshotMode = async ({ enabled }) => { modes.push(enabled); if (enabled) loading({ isLoading: true }); };
  await s.open();
  const capture = assert.rejects(s.plugin.captureScreen(true), code('CANCELLED'));
  await tick(); s.images[0].onload(); await capture;
  assert.deepEqual(modes, [true, false]); assert.equal(s.revoked.length, 1);
  await s.plugin.closeWebView();
});

test('all view commands reject before open; repeated close succeeds', async () => {
  const { plugin, calls } = setup();
  for (const invoke of [() => plugin.navigateBack(), () => plugin.navigateForward(), () => plugin.reload(),
    () => plugin.showWebView(), () => plugin.hideWebView(), () => plugin.captureScreen(true),
    () => plugin.updateDimensions({}), () => plugin.loadUrl({ url: 'https://example.com' })]) {
    await assert.rejects(invoke(), code('NOT_OPEN'));
  }
  await plugin.closeWebView();
  await plugin.closeWebView();
  assert.equal(calls.length, 0);
});

test('web platform rejects instead of accidentally invoking native loadUrl', async () => {
  const { plugin, platform, calls, open } = setup();
  platform.native = false;
  for (const invoke of [open, () => plugin.loadUrl({ url: 'https://example.com' }),
    () => plugin.closeWebView(), () => plugin.onPageLoaded(() => {})]) {
    await assert.rejects(invoke(), code('UNIMPLEMENTED'));
  }
  assert.equal(calls.length, 0);
});

test('invalid URLs, headers and initial bounds do not create a view or observer', async () => {
  const { plugin, calls, observers, element } = setup();
  for (const options of [{ url: 'relative', element }, { url: 'https://example.com/a b', element },
    { url: 'https://example.com', element, headers: { Authorization: 12 } },
    { url: 'https://example.com', element, headers: { Test: 'x\r\ny' } },
    { url: 'https://example.com', element: undefined }]) {
    await assert.rejects(plugin.openWebView(options), code('INVALID_ARGUMENT'));
  }
  element.getBoundingClientRect = () => ({ width: 0, height: 20, x: 0, y: 0 });
  await assert.rejects(plugin.openWebView({ url: 'https://example.com', element }), code('INVALID_ARGUMENT'));
  assert.equal(calls.length, 0);
  assert.equal(observers.length, 0);
});

test('concurrent opens are rejected and failed opening rolls back for retry', async () => {
  const { backend, plugin, observers, open } = setup();
  const pending = deferred();
  backend.openWebView = () => pending.promise;
  const first = open();
  await assert.rejects(open(), code('BUSY'));
  pending.reject(Object.assign(new Error('failure'), { code: 'LOAD_FAILED' }));
  await assert.rejects(first, code('LOAD_FAILED'));
  assert.equal(observers.length, 0);
  backend.openWebView = async () => {};
  await open();
  assert.equal(observers.filter(o => o.active).length, 1);
  await plugin.closeWebView();
  assert.equal(observers.filter(o => o.active).length, 0);
});

test('close while opening cancels the caller and concurrent closes share native work', async () => {
  const { backend, plugin, calls, open } = setup();
  const pending = deferred(), closing = deferred();
  backend.openWebView = () => pending.promise;
  backend.closeWebView = () => { calls.push(['close']); pending.reject(Object.assign(new Error(), { code: 'CANCELLED' })); return closing.promise; };
  const opening = assert.rejects(open(), code('CANCELLED'));
  await tick();
  const firstClose = plugin.closeWebView();
  const secondClose = plugin.closeWebView();
  closing.resolve();
  await Promise.all([opening, firstClose, secondClose]);
  assert.equal(calls.filter(call => call[0] === 'close').length, 1);
});

test('listeners are removed on close, including registrations completed after close', async () => {
  const { backend, plugin, calls } = setup();
  await plugin.onPageLoaded(() => {});
  await plugin.onPageLoadError(() => {});
  await plugin.closeWebView();
  assert.equal(calls.filter(call => call[0] === 'remove').length, 2);
  const pending = deferred();
  backend.addListener = () => pending.promise;
  const registering = assert.rejects(plugin.onPageLoaded(() => {}), code('CANCELLED'));
  await plugin.closeWebView();
  let removed = 0;
  pending.resolve({ remove: async () => { removed++; } });
  await registering;
  assert.equal(removed, 1);
});

test('close still reaches native and clears state when listener removal fails', async () => {
  const { backend, plugin, calls, open } = setup();
  let attempts = 0;
  backend.addListener = async () => ({ remove: async () => { if (++attempts === 1) throw new Error('removal failed'); } });
  await plugin.onPageLoaded(() => {});
  await open();
  await assert.rejects(plugin.closeWebView(), /removal failed/);
  assert.equal(calls.filter(call => call[0] === 'closeWebView').length, 1);
  await plugin.closeWebView();
  assert.equal(attempts, 4); // Three registrations, plus retry of the failed removal.
});

test('failure after native opening rolls the native view back', async () => {
  const { plugin, calls, observers, open } = setup({ observeFailure: true });
  await assert.rejects(open(), /observer failed/);
  assert.equal(calls.filter(call => call[0] === 'closeWebView').length, 1);
  assert.equal(observers.filter(observer => observer.active).length, 0);
  await plugin.closeWebView();
});

test('failed native close can be retried and prevents another open in the meantime', async () => {
  const { plugin, backend, open } = setup();
  await open();
  backend.closeWebView = async () => { throw new Error('bridge close failed'); };
  await assert.rejects(plugin.closeWebView(), /bridge close failed/);
  await assert.rejects(open(), code('BUSY'));
  backend.closeWebView = async () => {};
  await plugin.closeWebView();
  await open();
  await plugin.closeWebView();
});

test('invalid bounds are rejected without forwarding a partial native update', async () => {
  const { plugin, calls, open } = setup();
  await open();
  for (const bounds of [{ width: 1 }, { width: -1, height: 20, x: 0, y: 0 },
    { width: 1, height: Infinity, x: 0, y: 0 }, { width: 1, height: 20, x: NaN, y: 0 }]) {
    await assert.rejects(plugin.updateDimensions(bounds), code('INVALID_ARGUMENT'));
  }
  assert.equal(calls.filter(call => call[0] === 'updateDimensions').length, 0);
  await plugin.closeWebView();
});

test('terminal native load failure closes wrapper state and permits reopening', async () => {
  const { backend, plugin, open, observers } = setup();
  await open();
  backend.loadUrl = async () => { throw Object.assign(new Error(), { code: 'LOAD_TIMEOUT' }); };
  await assert.rejects(plugin.loadUrl({ url: 'https://example.com/next' }), code('LOAD_TIMEOUT'));
  assert.equal(observers.filter(o => o.active).length, 0);
  await assert.rejects(plugin.reload(), code('NOT_OPEN'));
  await open();
  await plugin.closeWebView();
});

test('late failure from an old load cannot clear a reopened session', async () => {
  const { backend, plugin, open } = setup();
  await open();
  const pending = deferred();
  backend.loadUrl = () => pending.promise;
  const oldLoad = assert.rejects(plugin.loadUrl({ url: 'https://example.com/next' }), code('LOAD_FAILED'));
  await plugin.closeWebView();
  await open();
  pending.reject(Object.assign(new Error(), { code: 'LOAD_FAILED' }));
  await oldLoad;
  await plugin.reload();
  await plugin.closeWebView();
});

test('screenshot promise waits for image decoding and native visibility; URL is released on close', async () => {
  const { plugin, backend, images, revoked, open } = setup();
  await open();
  const visibility = deferred();
  backend.setSnapshotMode = () => visibility.promise;
  let settled = false;
  const snapshot = plugin.captureScreen(true).then(() => { settled = true; });
  await tick();
  assert.equal(settled, false);
  images[0].onload();
  await tick();
  assert.equal(settled, false);
  visibility.resolve();
  await snapshot;
  await plugin.closeWebView();
  assert.equal(revoked.length, 1);
});

test('decode failure and close during screenshot both reject and release temporary URLs', async () => {
  for (const close of [false, true]) {
    const { plugin, images, revoked, open } = setup();
    await open();
    const snapshot = assert.rejects(plugin.captureScreen(true), code(close ? 'CANCELLED' : 'SNAPSHOT_FAILED'));
    await tick();
    if (close) await plugin.closeWebView();
    else images[0].onerror();
    await snapshot;
    assert.equal(revoked.length, 1);
    await plugin.closeWebView();
  }
});

test('default policy blocks insecure, local, active and credential URLs before native allocation', async () => {
  for (const url of ['http://example.com', 'file:///tmp/private', 'content://provider/file',
    'javascript:alert(1)', 'data:text/html,hello', 'https://user:secret@example.com']) {
    const { plugin, element, calls } = setup();
    await assert.rejects(plugin.openWebView({ url, element }), code('POLICY_BLOCKED'));
    assert.equal(calls.length, 0);
  }
});

test('exact origin defaults allow same-origin URLs and reject lookalikes or different ports', async () => {
  const { plugin, calls, open } = setup();
  await open();
  const nativeOpen = calls.find(call => call[0] === 'openWebView')[1];
  assert.equal(JSON.stringify(nativeOpen.navigationPolicy), JSON.stringify({ allowedOrigins: ['https://example.com'], allowHttp: false }));
  await plugin.loadUrl({ url: 'https://EXAMPLE.COM:443/next' });
  for (const url of ['https://example.com.evil.test', 'https://other.example.com', 'https://example.com:8443']) {
    await assert.rejects(plugin.loadUrl({ url }), code('POLICY_BLOCKED'));
  }
  assert.equal(calls.filter(call => call[0] === 'loadUrl').length, 1);
  await plugin.closeWebView();
});

test('explicit origin configuration is copied and HTTP needs opt-in', async () => {
  const { plugin, element, calls } = setup();
  const allowedOrigins = ['https://example.com', 'https://login.example.com', 'http://localhost:8080'];
  await plugin.openWebView({ url: 'https://example.com', element, navigationPolicy: { allowedOrigins, allowHttp: true } });
  allowedOrigins.push('https://untrusted.test');
  await plugin.loadUrl({ url: 'https://login.example.com/login' });
  await plugin.loadUrl({ url: 'http://localhost:8080/fixture' });
  await assert.rejects(plugin.loadUrl({ url: 'https://untrusted.test' }), code('POLICY_BLOCKED'));
  assert.equal(calls.filter(call => call[0] === 'loadUrl').length, 2);
  await plugin.closeWebView();
  const secure = setup();
  await secure.plugin.openWebView({ url: 'https://example.com', element: secure.element,
    navigationPolicy: { allowedOrigins: ['https://example.com', 'http://localhost:8080'] } });
  await assert.rejects(secure.plugin.loadUrl({ url: 'http://localhost:8080' }), code('POLICY_BLOCKED'));
  await secure.plugin.closeWebView();
});

test('invalid policy values and origin patterns reject without native side effects', async () => {
  for (const navigationPolicy of [null, [], { allowHttp: 'yes' }, { allowedOrigins: [] },
    ...['*', 'https://*.example.com', 'https://example.com/path', 'https://example.com?',
      'https://example.com#', 'https://user@example.com', 'https://example.com:0'].map(origin => ({ allowedOrigins: [origin] }))]) {
    const { plugin, element, calls } = setup();
    await assert.rejects(plugin.openWebView({ url: 'https://example.com', element, navigationPolicy }), code('INVALID_ARGUMENT'));
    assert.equal(calls.length, 0);
  }
});

test('navigation observation cannot hold a native decision or send a late JavaScript override', async () => {
  const { plugin, backend, calls, open } = setup();
  let emit, observed;
  backend.addListener = async (name, callback) => { if (name === 'navigationHandler') emit = callback; return { remove: async () => {} }; };
  await plugin.onNavigation(event => { observed = event; });
  await open();
  emit({ url: 'https://example.com/next', allowed: true, reason: 'ALLOWED', isMainFrame: true, newWindowRequest: false });
  assert.equal(observed.allowed, true);
  await assert.rejects(observed.complete(false), code('UNIMPLEMENTED'));
  assert.equal(calls.filter(call => call[0] === 'onNavigation').length, 0);
  await plugin.reload();
  await plugin.closeWebView();
});

test('blocked redirect closes the failed load session and allows a fresh open', async () => {
  const { plugin, backend, open } = setup();
  await open();
  backend.loadUrl = async () => { throw Object.assign(new Error('blocked redirect'), { code: 'NAVIGATION_BLOCKED' }); };
  await assert.rejects(plugin.loadUrl({ url: 'https://example.com/redirect' }), code('NAVIGATION_BLOCKED'));
  await assert.rejects(plugin.reload(), code('NOT_OPEN'));
  await open();
  await plugin.closeWebView();
});

test('a native policy closure updates wrapper state even without a public navigation listener', async () => {
  const { plugin, backend, open } = setup();
  let closed;
  backend.addListener = async (name, callback) => { if (name === 'browserClosed') closed = callback; return { remove: async () => {} }; };
  await open();
  closed({ reason: 'NAVIGATION_BLOCKED' });
  await assert.rejects(plugin.reload(), code('NOT_OPEN'));
  await open();
  await plugin.closeWebView();
});
