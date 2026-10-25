#!/usr/bin/env node
/**
 * Captures 8 demo scenes with Playwright at 1920x1080@2x with a smooth visual cursor.
 * Boots Spring Boot with STORE_FILE_PATH=./demo-store.json (isolated from real data).
 */
import { chromium } from 'playwright';
import { spawn } from 'child_process';
import { mkdirSync, existsSync } from 'fs';
import { join } from 'path';

const SCENES_DIR = './recordings';
const SPRING_BOOT_JAR = '../DuesDesK-main/target/dues-manager-1.0.0.jar';
const BASE_URL = 'http://localhost:8081'; // 8080 is used by the Docker container

// Create recordings directory
if (!existsSync(SCENES_DIR)) {
  mkdirSync(SCENES_DIR, { recursive: true });
}

let serverProcess;

async function startSpringBoot() {
  console.log('🚀 Starting Spring Boot with demo data...');
  
  return new Promise((resolve, reject) => {
    const demoStorePath = join(process.cwd(), 'demo-store.json');
    
    // Start the Spring Boot app directly: the jar's declared main class is the JavaFX desktop launcher.
    serverProcess = spawn('java', [
      '-Dloader.main=com.fraternity.dues.App',
      '-cp', SPRING_BOOT_JAR,
      'org.springframework.boot.loader.launch.PropertiesLauncher',
      `--server.port=8081`,
      `--store.file.path=${demoStorePath}`
    ], {
      cwd: process.cwd(),
      env: { ...process.env, STORE_FILE_PATH: demoStorePath },
      stdio: 'pipe'
    });

    serverProcess.stdout.on('data', (data) => {
      const output = data.toString();
      console.log(output);
      if (output.includes('Started') || output.includes('Tomcat started')) {
        console.log('✅ Spring Boot ready\n');
        setTimeout(resolve, 2000); // Give it 2s to fully initialize
      }
    });

    serverProcess.stderr.on('data', (data) => {
      console.error(data.toString());
    });

    serverProcess.on('error', reject);
    
    setTimeout(() => reject(new Error('Server failed to start within 60s')), 60000);
  });
}

function stopSpringBoot() {
  if (serverProcess) {
    console.log('\n🛑 Stopping Spring Boot...');
    serverProcess.kill();
  }
}

async function injectCursor(page) {
  await page.addStyleTag({
    content: `
      #demo-cursor {
        position: fixed;
        width: 24px;
        height: 24px;
        border: 2px solid #3b82f6;
        border-radius: 50%;
        background: rgba(59, 130, 246, 0.2);
        pointer-events: none;
        z-index: 999999;
        transition: all 0.12s cubic-bezier(0.4, 0.0, 0.2, 1);
      }
      #demo-cursor.clicking {
        transform: scale(0.8);
        background: rgba(59, 130, 246, 0.4);
      }
      .cursor-ripple {
        position: fixed;
        width: 24px;
        height: 24px;
        border: 2px solid #3b82f6;
        border-radius: 50%;
        pointer-events: none;
        z-index: 999998;
        animation: ripple-out 0.6s ease-out forwards;
      }
      @keyframes ripple-out {
        from { transform: scale(1); opacity: 0.8; }
        to { transform: scale(3); opacity: 0; }
      }
    `
  });

  await page.evaluate(() => {
    const cursor = document.createElement('div');
    cursor.id = 'demo-cursor';
    document.body.appendChild(cursor);

    let currentX = 0, currentY = 0;

    document.addEventListener('mousemove', (e) => {
      currentX = e.clientX;
      currentY = e.clientY;
      cursor.style.left = `${currentX - 12}px`;
      cursor.style.top = `${currentY - 12}px`;
    });

    document.addEventListener('mousedown', () => {
      cursor.classList.add('clicking');
      const ripple = document.createElement('div');
      ripple.className = 'cursor-ripple';
      ripple.style.left = `${currentX - 12}px`;
      ripple.style.top = `${currentY - 12}px`;
      document.body.appendChild(ripple);
      setTimeout(() => ripple.remove(), 600);
    });

    document.addEventListener('mouseup', () => {
      cursor.classList.remove('clicking');
    });
  });
}

async function smoothMove(page, toX, toY, durationMs = 800) {
  await page.mouse.move(toX, toY, { steps: Math.ceil(durationMs / 16) });
  await page.waitForTimeout(100);
}

async function smoothClick(page, x, y) {
  await page.mouse.click(x, y);
  await page.waitForTimeout(300);
}

async function captureScene1Dashboard(page, context) {
  console.log('📹 Scene 1: Dashboard & Member Ledger Review');
  
  await page.goto(BASE_URL);
  await page.waitForLoadState('networkidle');
  await injectCursor(page);
  
  
  // Show stats
  await smoothMove(page, 300, 250);
  await page.waitForTimeout(1500);
  
  // Show "Needs attention" section
  await smoothMove(page, 400, 450);
  await page.waitForTimeout(1000);
  
  // Click first "Review" link
  const reviewBtn = page.locator('.btn-fintech-soft').first();
  const box = await reviewBtn.boundingBox();
  if (box) {
    await smoothMove(page, box.x + box.width / 2, box.y + box.height / 2);
    await smoothClick(page, box.x + box.width / 2, box.y + box.height / 2);
    await page.waitForTimeout(2000);
    
    // Show sticky headers and scroll retention
    await page.locator('[data-fullscreen-target="duesLedgerView"]').click();
    await page.waitForTimeout(1500);
    
    // Scroll down
    await page.mouse.wheel(0, 400);
    await page.waitForTimeout(1000);
    
    // Hover over bulk tools
    await smoothMove(page, 300, 300);
    await page.waitForTimeout(1500);
  }
}

async function captureScene2Reconcile(page) {
  console.log('📹 Scene 2: Bank Reconciliation');
  
  await page.goto(`${BASE_URL}/numbers/flow`);
  await page.waitForLoadState('networkidle');
  await injectCursor(page);
  
  
  // Find pending Susan Walding deposit
  await smoothMove(page, 400, 350);
  await page.waitForTimeout(1000);
  
  // The Susan Walding deposit's "Match to member" form
  const row = page.locator('tr', { hasText: 'Susan Walding' }).first();
  const select = row.locator('select[name="memberId"]');
  const box = await select.boundingBox();
  if (box) {
    await smoothMove(page, box.x + box.width / 2, box.y + box.height / 2);
    await smoothClick(page, box.x + box.width / 2, box.y + box.height / 2);
    await page.waitForTimeout(500);

    // Select Conner Stover from dropdown
    await select.selectOption({ label: 'Conner Stover' });
    await page.waitForTimeout(800);

    // Check "Remember this payer name" (shown once a member is chosen)
    await row.locator('input[name="rememberAlias"]').check();
    await page.waitForTimeout(800);

    // Confirm
    await row.locator('button:has-text("Confirm")').click();
    await page.waitForTimeout(2000);
  }
}

async function captureScene3Reminders(page) {
  console.log('📹 Scene 3: SMS & Pay Link Reminders');
  
  await page.goto(`${BASE_URL}/settings`);
  await page.waitForLoadState('networkidle');
  await injectCursor(page);
  
  
  // Show SMS template with {{venmo_link}} and {{pay_links}}
  await smoothMove(page, 500, 400);
  await page.waitForTimeout(1500);
  
  // Navigate back to dues page
  await page.goto(BASE_URL);
  await page.waitForTimeout(1000);
  
  // Find a past-due member and show zero-Twilio reminder options
  const reviewLink = page.locator('a:has-text("Review")').first();
  await reviewLink.click();
  await page.waitForTimeout(1500);
  
  // Show Messages/Phone Link, Email, Copy options
  await smoothMove(page, 800, 400);
  await page.waitForTimeout(2000);
}

async function captureScene4InstallmentsClearance(page) {
  console.log('📹 Scene 4: Payment Plans & Clearance');
  
  await page.goto(BASE_URL);
  await page.waitForLoadState('networkidle');
  await injectCursor(page);
  
  
  // Find member on payment plan
  await page.click('button[data-filter="Payment Plan"]');
  await page.waitForTimeout(1000);
  
  // Click member to show installments
  await page.locator('.history-link').first().click();
  await page.waitForTimeout(2000);
  
  // Show "Generate Agreement" button
  await smoothMove(page, 600, 350);
  await page.waitForTimeout(1500);
  
  // Navigate to settings to show clearance rules
  await page.goto(`${BASE_URL}/settings`);
  await page.waitForTimeout(1000);
  
  // Scroll to social clearance section
  await page.evaluate(() => window.scrollTo(0, 800));
  await page.waitForTimeout(1500);
}

async function captureScene5HousingCashFlow(page) {
  console.log('📹 Scene 5: Housing & Cash Flow');
  
  await page.goto(`${BASE_URL}/rent`);
  await page.waitForLoadState('networkidle');
  await injectCursor(page);
  
  
  // Show resident roster
  await smoothMove(page, 400, 350);
  await page.waitForTimeout(1500);
  
  // Navigate to cash flow
  await page.goto(`${BASE_URL}/numbers/flow`);
  await page.waitForTimeout(1000);
  
  // Show cash flow calendar
  await smoothMove(page, 500, 400);
  await page.waitForTimeout(1500);
  
  // Show spending calculator
  await page.click('button:has-text("Spending Calculator")');
  await page.waitForTimeout(1000);
  
  // Test $3,200 event
  await page.fill('input[name="testAmount"]', '3200');
  await page.waitForTimeout(1500);
}

async function captureScene6ChairBudgets(page) {
  console.log('📹 Scene 6: Chair Budgets');
  
  await page.goto(`${BASE_URL}/chair-budgeting`);
  await page.waitForLoadState('networkidle');
  await injectCursor(page);
  
  
  // Show color-coded budget cards
  await smoothMove(page, 400, 350);
  await page.waitForTimeout(2000);
  
  // Click "Add Charge" on first budget
  const addChargeBtn = page.locator('button:has-text("Add")').first();
  if (await addChargeBtn.isVisible()) {
    await addChargeBtn.click();
    await page.waitForTimeout(1000);
    
    // Show receipt upload field
    await smoothMove(page, 500, 450);
    await page.waitForTimeout(1500);
  }
}

async function captureScene7ReportsRollover(page) {
  console.log('📹 Scene 7: Reports & Rollover');
  
  await page.goto(`${BASE_URL}/finance-tools`);
  await page.waitForLoadState('networkidle');
  await injectCursor(page);
  
  
  // Show anonymous meeting packet
  await smoothMove(page, 400, 350);
  await page.waitForTimeout(1500);
  
  // Show encrypted handoff ZIP
  await smoothMove(page, 400, 450);
  await page.waitForTimeout(1500);
  
  // Show "Start New Semester" button
  const rolloverBtn = page.locator('button:has-text("Start New Semester")');
  if (await rolloverBtn.isVisible()) {
    await smoothMove(page, 400, 550);
    await page.waitForTimeout(2000);
  }
}

async function captureScene8SecuritySnapshots(page) {
  console.log('📹 Scene 8: Security & Cloud Backups');
  
  await page.goto(`${BASE_URL}/settings`);
  await page.waitForLoadState('networkidle');
  await injectCursor(page);
  
  
  // Scroll to cloud backup section
  await page.evaluate(() => window.scrollTo(0, 1200));
  await page.waitForTimeout(1000);
  
  // Show cloud provider buttons
  await smoothMove(page, 400, 450);
  await page.waitForTimeout(1500);
  
  // Show encryption notice
  await smoothMove(page, 400, 550);
  await page.waitForTimeout(2000);
}

/**
 * Records one scene in its own browser context, so each scene gets its own video. Playwright only finishes writing
 * a video once its page closes, so the file is saved under the scene's name after closing. A step that can't find
 * its element logs a warning and the scene keeps what it recorded, rather than stopping the whole run.
 */
async function recordScene(browser, name, capture) {
  const context = await browser.newContext({
    viewport: { width: 1920, height: 1080 },
    deviceScaleFactor: 2,
    recordVideo: { dir: SCENES_DIR, size: { width: 1920, height: 1080 } }
  });
  const page = await context.newPage();
  page.setDefaultTimeout(5000);
  try {
    await capture(page, context);
  } catch (error) {
    console.warn(`⚠️  ${name}: ${error.message.split('\n')[0]}`);
  }
  const video = page.video();
  await context.close();
  await video.saveAs(join(SCENES_DIR, `${name}.webm`));
  await video.delete();
  console.log(`   saved ${name}.webm`);
}

async function main() {
  try {
    await startSpringBoot();
    
    // Headless by default so no window takes over the screen; HEADED=1 to watch.
    const browser = await chromium.launch({
      headless: !process.env.HEADED,
      args: ['--window-size=1920,1080']
    });

    const scenes = [
      ['scene1-dashboard', captureScene1Dashboard],
      ['scene2-reconcile', captureScene2Reconcile],
      ['scene3-reminders', captureScene3Reminders],
      ['scene4-installments-clearance', captureScene4InstallmentsClearance],
      ['scene5-housing-cashflow', captureScene5HousingCashFlow],
      ['scene6-chair-budgets', captureScene6ChairBudgets],
      ['scene7-reports-rollover', captureScene7ReportsRollover],
      ['scene8-security-snapshots', captureScene8SecuritySnapshots]
    ];
    for (const [name, capture] of scenes) {
      await recordScene(browser, name, capture);
    }

    await browser.close();
    
    console.log('\n✅ All scenes captured!');
    console.log(`📁 Recordings saved to ${SCENES_DIR}/`);
    
  } catch (error) {
    console.error('❌ Error:', error);
    process.exit(1);
  } finally {
    stopSpringBoot();
  }
}

process.on('SIGINT', () => {
  stopSpringBoot();
  process.exit(0);
});

main();
