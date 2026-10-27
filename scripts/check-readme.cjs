const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '..');
const ts = require(path.join(root, 'node_modules/typescript'));
const readme = fs.readFileSync(path.join(root, 'README.md'), 'utf8');
const blocks = [...readme.matchAll(/```typescript\r?\n([\s\S]*?)```/g)];
let errors = [];
for (const [i, match] of blocks.entries()) {
  const file = path.join(root, `__readme_example_${i}.ts`).replace(/\\/g, '/');
  const options = {
    noEmit: true, strict: true, skipLibCheck: true, types: [],
    target: ts.ScriptTarget.ES2017, module: ts.ModuleKind.ESNext,
    moduleResolution: ts.ModuleResolutionKind.Node10,
    esModuleInterop: true, baseUrl: root,
    paths: { 'pixeltronic-webview-browser': ['dist/esm/index.d.ts'] },
  };
  const host = ts.createCompilerHost(options);
  const originalGetSourceFile = host.getSourceFile.bind(host);
  host.getSourceFile = (name, languageVersion, onError, createNew) =>
    name.replace(/\\/g, '/') === file
      ? ts.createSourceFile(name, match[1], languageVersion, true)
      : originalGetSourceFile(name, languageVersion, onError, createNew);
  const program = ts.createProgram([file], options, host);
  errors.push(...ts.getPreEmitDiagnostics(program).map(d => `Example ${i + 1}: ${ts.flattenDiagnosticMessageText(d.messageText, '\n')}`));
}
const anchors = new Set([...readme.matchAll(/^#{1,6} (.+)$/gm)].map(m => m[1].trim().toLowerCase().replace(/[^\w\- ]/g, '').replace(/ /g, '-')));
let linkCount = 0;
for (const match of readme.matchAll(/\[[^\]]+\]\(([^)]+)\)/g)) {
  const target = match[1];
  if (/^https?:/.test(target)) continue;
  linkCount++;
  if (target.startsWith('#') ? !anchors.has(target.slice(1)) : !fs.existsSync(path.join(root, target))) {
    errors.push(`Unresolved local link: ${target}`);
  }
}
if (errors.length) { console.error(errors.join('\n')); process.exit(1); }
console.log(`PASS: ${blocks.length} TypeScript examples type-check against the current API; ${linkCount} local links resolve.`);
