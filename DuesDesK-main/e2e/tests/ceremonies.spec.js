// End-to-end checks for the ceremony layer (anticipation -> reveal -> afterglow) on DuesDesk's five treasurer
// workflows, in the light and dark themes (one Playwright project each). Every flow also checks that the underlying
// post, download or data change still happens exactly as before.
const { test, expect } = require('@playwright/test');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');

const money = text => Number(String(text).replace(/[^0-9.-]/g, ''));
const today = () => new Date().toISOString().slice(0, 10);

test.beforeEach(async ({ page }) => {
    page.on('dialog', dialog => dialog.accept());
});

/** WCAG contrast ratio between an element's text color and the first opaque-ish background behind it. */
async function contrast(locator) {
    return locator.evaluate(el => {
        const parse = c => (c.match(/[\d.]+/g) || []).map(Number);
        const lum = ([r, g, b]) => {
            const f = v => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
            return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
        };
        // Composite translucent backgrounds from the element up to the page canvas.
        const layers = [];
        for (let node = el; node; node = node.parentElement) {
            const bg = parse(getComputedStyle(node).backgroundColor);
            if (bg.length >= 3 && (bg[3] === undefined || bg[3] > 0)) layers.push(bg);
            if (bg.length >= 3 && (bg[3] === undefined || bg[3] === 1)) break;
        }
        let base = parse(getComputedStyle(document.documentElement).backgroundColor);
        if (base.length < 3 || base[3] === 0) base = [255, 255, 255];
        for (const layer of layers.reverse()) {
            const a = layer[3] === undefined ? 1 : layer[3];
            base = [0, 1, 2].map(i => layer[i] * a + base[i] * (1 - a));
        }
        const fg = parse(getComputedStyle(el).color);
        const a = fg[3] === undefined ? 1 : fg[3];
        const text = [0, 1, 2].map(i => fg[i] * a + base[i] * (1 - a));
        const [hi, lo] = [lum(text), lum(base)].sort((x, y) => y - x);
        return (hi + 0.05) / (lo + 0.05);
    });
}

/** Names and contents of every entry in a ZIP file. */
function unzip(buffer) {
    let end = buffer.length - 22;
    while (end >= 0 && buffer.readUInt32LE(end) !== 0x06054b50) end--;
    const count = buffer.readUInt16LE(end + 10);
    let offset = buffer.readUInt32LE(end + 16);
    const entries = {};
    for (let i = 0; i < count; i++) {
        const method = buffer.readUInt16LE(offset + 10);
        const size = buffer.readUInt32LE(offset + 20);
        const nameLength = buffer.readUInt16LE(offset + 28);
        const extra = buffer.readUInt16LE(offset + 30);
        const comment = buffer.readUInt16LE(offset + 32);
        const local = buffer.readUInt32LE(offset + 42);
        const name = buffer.toString('utf8', offset + 46, offset + 46 + nameLength);
        const start = local + 30 + buffer.readUInt16LE(local + 26) + buffer.readUInt16LE(local + 28);
        const raw = buffer.subarray(start, start + size);
        entries[name] = method === 8 ? zlib.inflateRawSync(raw) : raw;
        offset += 46 + nameLength + extra + comment;
    }
    return entries;
}

test.describe.serial('ceremonies', () => {
    test('theme binding: the page renders in the project color scheme', async ({ page }, info) => {
        await page.goto('/charges');
        await expect(page.locator('html')).toHaveAttribute('data-theme', info.project.name);
    });

    test('1a. Charges: batch posting plays the ledger-locking sequence, cascades new rows and rolls receivables', async ({ page }, info) => {
        await page.addInitScript(() => {
            window.__shifts = [];
            new PerformanceObserver(list => list.getEntries().forEach(e => { if (!e.hadRecentInput) window.__shifts.push({ t: e.startTime, v: e.value }); }))
                .observe({ type: 'layout-shift', buffered: true });
        });
        await page.goto('/charges');
        const owedBefore = money(await page.locator('#chargesOwedValue').textContent());

        await page.getByRole('tab', { name: 'Several' }).click();
        const description = `E2E Formal fee ${info.project.name} ${Date.now()}`;
        await page.fill('#batchDescription', description);
        await page.fill('#batchAmount', '25');
        await page.fill('#batchDueDate', '2026-12-01');
        await page.click('[data-pick="Member"]');
        const selected = await page.locator('.batch-member-check:checked').count();
        expect(selected).toBeGreaterThan(0);

        const posted = page.waitForRequest(req => req.method() === 'POST' && req.url().endsWith('/charges/batch'));
        const started = Date.now();
        await page.click('#batchChargeForm button[type="submit"]');

        // Stage 1: the anticipation overlay, with its steps checking off before the post leaves the page.
        const overlay = page.locator('.ceremony-overlay');
        await expect(overlay).toBeVisible();
        await expect(overlay.locator('.ceremony-title')).toHaveText('Validating member accounts & posting charges…');
        await expect(overlay.locator('.ceremony-step')).toHaveCount(3);
        await expect(overlay.locator('.ceremony-step.is-done .ceremony-check').first()).toBeAttached();
        await posted;
        expect(Date.now() - started).toBeGreaterThanOrEqual(1400);

        // Stage 2: the redirect lands, the new rows cascade (35ms apart) and the owed figure rolls on reels.
        await page.waitForLoadState('domcontentloaded');
        await expect(page.locator('main > .alert')).toContainText(`Added ${description}`);
        const fresh = page.locator('tr.charge-row.ceremony-fresh');
        await expect(fresh).toHaveCount(selected);
        await expect(fresh.nth(1)).toHaveCSS('animation-name', 'ceremonyRowIn');
        expect(await fresh.nth(1).evaluate(el => getComputedStyle(el).animationDelay)).toBe('0.035s');
        await expect(page.locator('#chargesOwedValue .odo-reel').first()).toBeAttached();
        await expect(page.locator('#chargesOwedValue .odo-reel')).toHaveCount(0, { timeout: 6000 });

        // The data change itself: the charges are on the accounts, and what is owed went up by at most $25 each.
        const owedAfter = money(await page.locator('#chargesOwedValue').textContent());
        expect(owedAfter).toBeGreaterThan(owedBefore);
        expect(owedAfter - owedBefore).toBeLessThanOrEqual(25 * selected + 0.01);

        // No layout shift from the reels: nothing measurable once the page had loaded.
        const shift = await page.evaluate(() => {
            const loaded = performance.getEntriesByType('navigation')[0].domContentLoadedEventEnd;
            return window.__shifts.filter(s => s.t > loaded).reduce((sum, s) => sum + s.v, 0);
        });
        expect(shift).toBeLessThan(0.01);
    });

    test('1b. Charges: "Template for Greekbill charges" compresses, snaps to a ready pill and leaves a count badge', async ({ page }) => {
        await page.goto('/charges#greekbill');
        const button = page.locator('[data-greekbill-export]');
        const members = await button.getAttribute('data-member-count');
        const charges = await button.getAttribute('data-charge-count');
        const download = page.waitForEvent('download');
        await button.click();
        await expect(button).toContainText('Formatting Greekbill Schema…');
        await expect(button).toHaveClass(/ceremony-press/);
        await expect(button).toContainText('Validating Columns…');
        const file = await download;
        expect(file.suggestedFilename()).toMatch(/\.pdf$/);
        const pdf = fs.readFileSync(await file.path());
        expect(pdf.subarray(0, 4).toString()).toBe('%PDF');
        await expect(button).toHaveText('Greekbill Template Ready');
        await expect(button).toHaveClass(/ceremony-ready/);
        const badge = page.locator('#greekbillAfterglow .ceremony-badge');
        await expect(badge).toBeVisible();
        await expect(badge).toContainText(`${members} member record`);
        await expect(badge).toContainText(`${charges} charge`);
        expect(await contrast(badge)).toBeGreaterThanOrEqual(4.5);
        expect(await contrast(button)).toBeGreaterThanOrEqual(4.5);
        // Then the button returns to normal; the badge stays as the afterglow.
        await expect(button).toContainText('Export template (PDF)', { timeout: 6000 });
        await expect(badge).toBeVisible();
    });

    test('2a. Reconciliation: clearing a payment earns a spring check, then the dashboard KPIs roll', async ({ page }) => {
        await page.goto('/');
        const balanceBefore = await page.locator('.kpi-value[data-stat-key="balance"]').textContent();

        await page.goto('/numbers/flow');
        const form = page.locator('form[action*="/reconciliation/"][action$="/confirm"]').first();
        await expect(form).toBeVisible();
        const select = form.locator('select[name="memberId"]');
        if (!(await select.inputValue())) await select.selectOption({ index: 1 });
        await form.locator('button[type="submit"]').click();
        await page.waitForURL(/\/numbers\/flow/);
        const toast = page.locator('main .alert-success[data-auto-dismiss]');
        await expect(toast).toContainText('matched and payment recorded');
        await expect(toast.locator('svg.ceremony-check')).toBeVisible();
        await expect(toast.locator('svg.ceremony-check')).toHaveCSS('animation-name', /ceremonyCheckIn/);

        await page.goto('/');
        const balance = page.locator('.kpi-value[data-stat-key="balance"]');
        await expect(balance.locator('.odo-reel').first()).toBeAttached();
        await expect(balance.locator('.odo-reel')).toHaveCount(0, { timeout: 6000 });
        expect(money(await balance.textContent())).toBeLessThan(money(balanceBefore));
    });

    test('2b. Dashboard: crossing a collection milestone elevates the header with a health badge and session glow', async ({ page }) => {
        await page.goto('/');
        // The chapter's state before the reconciliation: collections under 75% with members past due.
        await page.evaluate(() => sessionStorage.setItem('duesdesk:health', JSON.stringify({ tier: 0, clear: false })));
        await page.evaluate(() => sessionStorage.removeItem('duesdesk:health-glow'));
        // The reconciliation brought collections to 92.5% and cleared every past-due balance.
        await page.route('**/', async route => {
            if (route.request().resourceType() !== 'document') return route.continue();
            const response = await route.fetch();
            const body = (await response.text())
                .replace(/data-collection-rate="[^"]*"/, 'data-collection-rate="92.5"')
                .replace(/data-past-due="[^"]*"/, 'data-past-due="0.0"');
            await route.fulfill({ response, body });
        });
        await page.reload();
        const badge = page.locator('#chapterHealth');
        await expect(badge).toBeVisible();
        await expect(badge).toContainText('Chapter Financial Health:');
        await expect(badge.locator('.health-value')).toHaveText('Strong · $0 past due');
        await expect(badge).toHaveClass(/is-milestone/);
        await expect(badge).toContainText('Milestone reached: 90% collection rate');
        await expect(page.locator('.page-header').first()).toHaveClass(/has-health-glow/);
        expect(await contrast(badge.locator('.health-value'))).toBeGreaterThanOrEqual(4.5);

        // Afterglow: the glow persists for the session without replaying the milestone.
        await page.reload();
        await expect(badge).toBeVisible();
        await expect(badge).not.toHaveClass(/is-milestone/);
        await expect(page.locator('.page-header').first()).toHaveClass(/has-health-glow/);
        await page.unroute('**/');
    });

    test('3. Chair Budgets: arriving from another page staggers each chair card and fills its usage bar', async ({ page, baseURL }) => {
        await page.goto('/chair-budgeting', { referer: `${baseURL}/` });
        const cards = page.locator('.chair-card.ceremony-enter');
        expect(await cards.count()).toBeGreaterThan(1);
        await expect(cards.first()).toHaveCSS('animation-name', 'ceremonyRise');
        expect(await cards.nth(1).evaluate(el => getComputedStyle(el).animationDelay)).toBe('0.12s');
        await expect(cards.first().locator('.progress-fill').first()).toHaveCSS('animation-name', 'ceremonyFill');
        await expect(page.locator('.chair-runway').first()).toContainText(/runway left|No runway left/);
        // A save on this page reloads it: no replay.
        await page.goto('/chair-budgeting', { referer: `${baseURL}/chair-budgeting` });
        await expect(page.locator('.chair-card.ceremony-enter')).toHaveCount(0);
    });

    test('4. Semester rollover: 2.5s diagnostic, "New Term Initialized" reveal, audit receipt, data intact', async ({ page }, info) => {
        await page.goto('/finance-tools#rollover');
        const term = `E2E Spring 2027 ${info.project.name}`;
        await page.fill('#termName', term);
        await page.check('#rolloverConfirmed');
        const posted = page.waitForRequest(req => req.method() === 'POST' && req.url().endsWith('/finance/semester-rollover'));
        const started = Date.now();
        await page.getByRole('button', { name: 'Start new semester' }).click();

        const overlay = page.locator('.ceremony-overlay');
        await expect(overlay.locator('.ceremony-step')).toHaveText([
            'Archiving current semester ledger & charge history…',
            'Calculating carry-over member balances & clearance statuses…',
            'Initializing new term budget structures…'
        ]);
        await expect(overlay.locator('.ceremony-step.is-done')).toHaveCount(2, { timeout: 3000 });
        await posted;
        expect(Date.now() - started).toBeGreaterThanOrEqual(2400);

        // Stage 2: the reveal card on the redirected page.
        const panel = page.locator('.ceremony-panel');
        await expect(panel.locator('.ceremony-title')).toHaveText(`New Term Initialized: ${term}`);
        await expect(panel.locator('.ceremony-stat__label')).toHaveText(['Starting reserves', 'Carried-over receivables', 'Active members']);
        await expect(panel.locator('.odo-reel').first()).toBeAttached();
        await expect(panel.locator('.odo-reel')).toHaveCount(0, { timeout: 6000 });

        // Database integrity: the term is recorded and the receivables shown match the books.
        await expect(page.locator('.page-actions .status-pill')).toContainText(term);
        const performance = await (await page.request.get('/finance/term-performance')).json();
        expect(performance.termName).toBe(term);
        expect(money(await panel.locator('.ceremony-stat__value').nth(1).textContent())).toBeCloseTo(performance.receivables, 2);
        expect(await contrast(panel.locator('.ceremony-title'))).toBeGreaterThanOrEqual(4.5);
        for (const selector of ['.ceremony-stat__label', '.ceremony-stat__sub', '.ceremony-eyebrow', '.ceremony-sub', '.ceremony-foot', '.ceremony-stat__value']) {
            expect(await contrast(panel.locator(selector).first()), selector).toBeGreaterThanOrEqual(4.5);
        }

        // Stage 3: the audit receipt, then back to the page.
        const download = page.waitForEvent('download');
        await panel.getByRole('button', { name: 'Download Rollover Audit Receipt' }).click();
        const receipt = fs.readFileSync(await (await download).path(), 'utf8');
        expect(receipt).toContain(`Term: ${term}`);
        expect(receipt).toContain('Starting reserves');
        await page.keyboard.press('Escape');
        await expect(page.locator('.ceremony-overlay')).toHaveCount(0);
    });

    test('5. Officer handoff: sealing sequence, AES-GCM package + PDF, and the Term Performance Card', async ({ page, context, baseURL }) => {
        await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: baseURL });
        await page.goto('/finance-tools');
        await page.fill('#handoffPassword', 'correct horse battery');
        await page.fill('#handoffPasswordConfirm', 'correct horse battery');
        const download = page.waitForEvent('download');
        await page.getByRole('button', { name: 'Generate Officer Handoff Package' }).click();

        const overlay = page.locator('.ceremony-overlay');
        await expect(overlay.locator('.ceremony-step')).toHaveText([
            'Encrypting database (AES-256-GCM)…',
            'Enforcing privacy rules: saved credentials stay out…',
            'Compiling the 1-page executive PDF summary…'
        ]);

        // The package: an encrypted backup (no readable member data), the 1-page PDF and the instructions.
        const file = await download;
        expect(file.suggestedFilename()).toBe(`DuesDesk-officer-handoff-${today()}.zip`);
        const zip = fs.readFileSync(await file.path());
        const entries = unzip(zip);
        const names = Object.keys(entries);
        expect(names).toEqual(expect.arrayContaining([`DuesDesk-backup-${today()}.ddbk`, 'Officer-handoff-summary.pdf', 'New-treasurer-instructions.txt']));
        expect(entries['Officer-handoff-summary.pdf'].subarray(0, 4).toString()).toBe('%PDF');
        expect(entries[`DuesDesk-backup-${today()}.ddbk`].includes(Buffer.from('"members"'))).toBe(false);

        // Stages 2 and 3: the Term Performance Card, with the package fingerprint it was verified against.
        const panel = page.locator('.ceremony-panel');
        await expect(panel.locator('.ceremony-title')).toHaveText("Treasurer's Term Performance");
        await expect(panel.locator('.ceremony-stat')).toHaveCount(4);
        await expect(panel.locator('.ceremony-stat__label').nth(0)).toHaveText('Revenue managed & reconciled');
        await expect(panel.locator('.ceremony-stat__label').nth(1)).toHaveText('Collection efficiency');
        await expect(panel.locator('.ceremony-stat__label').nth(2)).toHaveText(/Budget surplus maintained|Budget variance/);
        await expect(panel.locator('.ceremony-stat__label').nth(3)).toHaveText('Backup & handoff integrity');
        const sha = crypto.createHash('sha256').update(zip).digest('hex');
        await expect(panel.locator('.ceremony-foot')).toHaveText(`Package fingerprint (SHA-256): ${sha}`);
        await expect(panel).toHaveClass(/is-gold/);
        expect(await contrast(panel.locator('.ceremony-stat__value').nth(3))).toBeGreaterThanOrEqual(4.5);

        await panel.getByRole('button', { name: 'Copy Executive Summary' }).click();
        await expect(panel.getByRole('button', { name: 'Copied' })).toBeVisible();
        const summary = await page.evaluate(() => navigator.clipboard.readText());
        expect(summary).toContain('Collection efficiency:');
        expect(summary).toContain(`package SHA-256 ${sha}`);
        await panel.getByRole('button', { name: 'Done' }).click();
        await expect(page.locator('.ceremony-overlay')).toHaveCount(0);
    });

    test('5b. Cloud backup: the snapshot form is held for the AES-GCM sealing sequence', async ({ page }) => {
        await page.goto('/settings');
        await expect(page.locator('#snapshotNowForm')).toHaveAttribute('data-ceremony', 'cloud-backup');
        // Backups are off in the test chapter (turning them on would write to this computer's keychain), so the
        // sequence is played directly to check it renders and resolves.
        await page.evaluate(() => window.duesDeskCeremony.sequence({
            title: 'Sealing an encrypted snapshot…', duration: 600,
            steps: ['Encrypting database (AES-256-GCM)…', 'Sealing…', 'Writing…']
        }).done);
        await expect(page.locator('.ceremony-panel')).toHaveClass(/is-sealed/);
        await expect(page.locator('.ceremony-step.is-done')).toHaveCount(3);
    });

    test('reduced motion: sequences resolve almost at once and reels are skipped', async ({ page }) => {
        await page.emulateMedia({ reducedMotion: 'reduce' });
        await page.goto('/charges');
        const elapsed = await page.evaluate(async () => {
            const start = performance.now();
            await window.duesDeskCeremony.sequence({ title: 'x', duration: 2500, steps: ['a', 'b', 'c'] }).done;
            return performance.now() - start;
        });
        expect(elapsed).toBeLessThan(800);
        const reels = await page.evaluate(() => {
            const el = document.getElementById('chargesOwedValue');
            window.duesDeskCeremony.odometer(el, '$0.00');
            return el.querySelectorAll('.odo-reel').length;
        });
        expect(reels).toBe(0);
    });
});

test('motion audit: ceremony keyframes animate only compositor-friendly properties', async ({}, info) => {
    test.skip(info.project.name !== 'light', 'static check, run once');
    const css = fs.readFileSync(path.resolve(__dirname, '../../src/main/resources/templates/fragments.html'), 'utf8');
    const block = css.slice(css.indexOf('==== Ceremonies'));
    const frames = [...block.matchAll(/@keyframes (ceremony\w+) \{([^@]*?)\}\s*\n/g)];
    expect(frames.length).toBeGreaterThanOrEqual(10);
    for (const [, name, body] of frames) {
        const properties = [...body.matchAll(/([a-z-]+)\s*:/g)].map(m => m[1]);
        // stroke-dashoffset only draws the 20px check's tick.
        for (const property of properties) expect([name, property]).toEqual([name, expect.stringMatching(/^(transform|opacity|stroke-dashoffset)$/)]);
    }
    expect(block).toContain('@media (prefers-reduced-motion: reduce)');
    expect(block).toMatch(/html\[data-theme="dark"\][^{]*\{[^}]*--ceremony-emerald/);
});
