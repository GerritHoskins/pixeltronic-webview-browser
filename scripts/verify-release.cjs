const { run } = require('./run.cjs');
try {
  for (const gate of ['verify:web', 'verify:android', 'verify:ios']) run('npm', ['run', gate]);
  console.log('PASS: all release gates completed. This command does not publish.');
} catch (error) {
  console.error('RELEASE GATE INCOMPLETE:', error.message);
  process.exitCode = 1;
}
