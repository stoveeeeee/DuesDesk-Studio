// Starts DuesDesk's Spring Boot service (no desktop window) for the Playwright checks, against a throwaway copy of the
// demo chapter in the system temp folder, so a test run never touches the treasurer's real data file.
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawn } from 'node:child_process';

const app = resolve(import.meta.dirname, '..');
const demo = resolve(app, '..', 'demo-data', 'DuesDesk-demo-50-member-chapter.json');
const port = process.env.E2E_PORT || '8765';

const data = JSON.parse(readFileSync(demo, 'utf8'));
// "system" lets each Playwright project pick light or dark with its emulated color scheme.
data.settings.appearanceMode = 'system';
data.settings.cloudBackupEnabled = false;
data.settings.autoSendEnabled = false;
const store = join(mkdtempSync(join(tmpdir(), 'duesdesk-e2e-')), 'store.json');
writeFileSync(store, JSON.stringify(data));
console.log(`DuesDesk e2e data file: ${store}`);

const args = [
    '-q', '-o', 'spring-boot:run',
    '-Dspring-boot.run.main-class=com.fraternity.dues.App',
    `"-Dspring-boot.run.arguments=--server.port=${port} --store.file.path=${store} --app.rate-limit.requests-per-minute=100000"`
];
const child = spawn('mvn', args, { cwd: app, stdio: 'inherit', shell: true });
const stop = () => { if (!child.killed) child.kill(); };
process.on('SIGINT', stop);
process.on('SIGTERM', stop);
process.on('exit', stop);
child.on('exit', code => process.exit(code ?? 0));
