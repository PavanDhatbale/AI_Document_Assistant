const { CdpBrowser } = require('C:/Users/hp/.gemini/antigravity/brain/59378320-c959-4633-b9e3-672211f294a0/scratch/cdp_browser.js');
const path = require('path');

const SCREENSHOTS_DIR = 'C:/Users/hp/.gemini/antigravity/brain/59378320-c959-4633-b9e3-672211f294a0/screenshots';

async function verifyProfileBrowser() {
  const browser = new CdpBrowser();
  const ts = Date.now();
  const email = `browser_profile_${ts}@example.com`;
  const initialPassword = 'InitialPass123!';
  const newPassword = 'ChangedPass456!';

  try {
    await browser.start(9226);
    await browser.setViewport(1280, 800);
    console.log("=== Edge CDP browser started on port 9226 ===");

    // 1. Register new user
    await browser.navigate('http://localhost:5173/register');
    await browser.waitForSelector('input[data-testid="register-name-input"]');
    await browser.type('input[data-testid="register-name-input"]', 'Browser User Initial');
    await browser.type('input[data-testid="register-email-input"]', email);
    await browser.type('input[data-testid="register-password-input"]', initialPassword);
    await browser.type('input[data-testid="register-confirm-password-input"]', initialPassword);
    await browser.click('button[data-testid="register-submit-button"]');
    await new Promise(r => setTimeout(r, 2000));

    // 2. Navigate to /profile
    await browser.navigate('http://localhost:5173/profile');
    await browser.waitForSelector('[data-testid="profile-card"]');
    console.log("Navigated to /profile page successfully.");

    // 3. Edit Profile
    await browser.click('button[data-testid="edit-profile-button"]');
    await browser.waitForSelector('input[data-testid="profile-name-input"]');
    await browser.type('input[data-testid="profile-name-input"]', 'Browser User Renamed');
    await browser.type('input[data-testid="profile-phone-input"]', '+1-800-555-0100');
    await browser.click('button[data-testid="save-profile-button"]');
    await new Promise(r => setTimeout(r, 1500));

    const updatedDisplayName = await browser.evaluate(`
      document.querySelector('[data-testid="profile-display-name"]')?.textContent
    `);
    const updatedPhone = await browser.evaluate(`
      document.querySelector('[data-testid="profile-phone-display"]')?.textContent
    `);
    console.log("Profile updated in UI:", { updatedDisplayName, updatedPhone });

    // 4. Change Password via Form
    await browser.type('input[data-testid="current-password-input"]', initialPassword);
    await browser.type('input[data-testid="new-password-input"]', newPassword);
    await browser.type('input[data-testid="confirm-password-input"]', newPassword);
    await browser.click('button[data-testid="submit-password-button"]');
    await new Promise(r => setTimeout(r, 1500));

    const successBannerText = await browser.evaluate(`
      document.querySelector('[data-testid="password-success-banner"]')?.textContent
    `);
    console.log("Password change response in UI:", successBannerText);

    // 5. Take screenshot
    await browser.screenshot(path.join(SCREENSHOTS_DIR, '14_profile_management_verified.png'));
    console.log("Captured screenshot: 14_profile_management_verified.png");

  } catch (err) {
    console.error("Browser verification error:", err);
  } finally {
    await browser.close();
    console.log("=== Edge CDP browser closed ===");
  }
}

verifyProfileBrowser().catch(console.error);
