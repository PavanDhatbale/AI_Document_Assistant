const { CdpBrowser } = require('C:/Users/hp/.gemini/antigravity/brain/59378320-c959-4633-b9e3-672211f294a0/scratch/cdp_browser.js');
const path = require('path');

const SCREENSHOTS_DIR = 'C:/Users/hp/.gemini/antigravity/brain/59378320-c959-4633-b9e3-672211f294a0/screenshots';

async function verifyDashboardBrowser() {
  const browser = new CdpBrowser();
  const ts = Date.now();
  const email = `dash_audit_${ts}@example.com`;
  const password = 'TestPassword123!';

  try {
    await browser.start(9227);
    await browser.setViewport(1280, 800);
    console.log("=== Edge CDP browser started on port 9227 ===");

    // 1. Register new test user and auto-navigate to /dashboard
    await browser.navigate('http://localhost:5173/register');
    await browser.waitForSelector('input[data-testid="register-name-input"]');
    await browser.type('input[data-testid="register-name-input"]', 'Dashboard Auditor');
    await browser.type('input[data-testid="register-email-input"]', email);
    await browser.type('input[data-testid="register-password-input"]', password);
    await browser.type('input[data-testid="register-confirm-password-input"]', password);
    await browser.click('button[data-testid="register-submit-button"]');
    await new Promise(r => setTimeout(r, 2000));

    // 2. Ensure on /dashboard
    await browser.navigate('http://localhost:5173/dashboard');
    await browser.waitForSelector('[data-testid="stat-total-documents"]');
    console.log("Arrived on /dashboard page.");

    // Initial empty state values
    const totalDocsInitial = await browser.evaluate(`document.querySelector('[data-testid="stat-total-documents-value"]')?.textContent`);
    const readyDocsInitial = await browser.evaluate(`document.querySelector('[data-testid="stat-ready-documents-value"]')?.textContent`);
    const vectorChunksInitial = await browser.evaluate(`document.querySelector('[data-testid="stat-vector-chunks-value"]')?.textContent`);
    const convsInitial = await browser.evaluate(`document.querySelector('[data-testid="stat-conversations-value"]')?.textContent`);
    console.log("Initial Dashboard Metrics:", { totalDocsInitial, readyDocsInitial, vectorChunksInitial, convsInitial });

    // 3. Test "New Chat" shortcut button
    await browser.click('button[data-testid="new-chat-shortcut"]');
    await new Promise(r => setTimeout(r, 2000));
    const convUrl = await browser.evaluate('window.location.href');
    console.log("After clicking New Chat shortcut, URL is:", convUrl);

    // 4. Return to Dashboard and verify conversation count incremented
    await browser.navigate('http://localhost:5173/dashboard');
    await browser.waitForSelector('[data-testid="stat-conversations"]');
    await new Promise(r => setTimeout(r, 1200));

    const convsAfter = await browser.evaluate(`document.querySelector('[data-testid="stat-conversations-value"]')?.textContent`);
    console.log("Updated Conversation Metric:", convsAfter);

    // 5. Test Recent Conversations item click
    const recentConvExists = await browser.evaluate(`!!document.querySelector('[data-testid^="recent-conversation-item-"]')`);
    console.log("Recent conversation item rendered:", recentConvExists);

    // 6. Test "Upload PDF" shortcut opens upload modal
    await browser.click('button[data-testid="upload-pdf-shortcut"]');
    await new Promise(r => setTimeout(r, 800));
    const modalVisible = await browser.evaluate(`!!document.querySelector('input[data-testid="file-upload-input"]')`);
    console.log("Upload PDF modal opened via shortcut:", modalVisible);

    // Close modal by clicking cancel
    await browser.evaluate(`
      (() => {
        const btns = Array.from(document.querySelectorAll('button'));
        const cancelBtn = btns.find(b => b.textContent.includes('Cancel'));
        if (cancelBtn) cancelBtn.click();
      })()
    `);
    await new Promise(r => setTimeout(r, 500));

    // 7. Capture Desktop Screenshot
    await browser.screenshot(path.join(SCREENSHOTS_DIR, '15_dashboard_desktop_view.png'));
    console.log("Captured screenshot: 15_dashboard_desktop_view.png");

    // 8. Test Mobile Viewport (375x667)
    await browser.setViewport(375, 667);
    await new Promise(r => setTimeout(r, 800));
    await browser.screenshot(path.join(SCREENSHOTS_DIR, '16_dashboard_mobile_view.png'));
    console.log("Captured screenshot: 16_dashboard_mobile_view.png");

    // Reset viewport
    await browser.setViewport(1280, 800);

  } catch (err) {
    console.error("Dashboard browser verification error:", err);
  } finally {
    await browser.close();
    console.log("=== Edge CDP browser closed ===");
  }
}

verifyDashboardBrowser().catch(console.error);
