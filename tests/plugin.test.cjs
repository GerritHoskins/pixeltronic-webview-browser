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

function setup({ observeFailure = false } = {}) {
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
  const context = {
    exports: {}, console, URL: TestURL, Blob, setTimeout, clearTimeout,
    window: { devicePixelRatio: 2 },
    fetch: async () => ({ arrayBuffer: async () => new ArrayBuffer(4) }),
    Image: class { constructor() { images.push(this); } },
    require: name => name === '@capacitor/core'
      ? { Capacitor: { isNativePlatform: () => platform.native }, registerPlugin: () => backend } : Observer,
  };
  vm.runInNewContext(source, context);
  const plugin = new context.exports.InAppBrowserClass();
  const element = { style: {}, getBoundingClientRect: () => ({ width: 300, height: 400, x: -5, y: 10 }) };
  const open = () => plugin.openWebView({ url: 'https://example.com', element });
  return { plugin, backend, calls, observers, images, revoked, platform, element, open };
}

const code = expected => error => error.code === expected;
const tick = () => new Promise(resolve => setImmediate(resolve));

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
  backend.addListener = async () => ({ remove: async () => { throw new Error('removal failed'); } });
  await plugin.onPageLoaded(() => {});
  await open();
  await assert.rejects(plugin.closeWebView(), /removal failed/);
  assert.equal(calls.filter(call => call[0] === 'closeWebView').length, 1);
  await plugin.closeWebView();
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
  backend.hideWebView = () => visibility.promise;
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
