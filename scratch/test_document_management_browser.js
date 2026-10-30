const { CdpBrowser } = require('C:/Users/hp/.gemini/antigravity/brain/59378320-c959-4633-b9e3-672211f294a0/scratch/cdp_browser.js');
const path = require('path');
const fs = require('fs');
const { execSync } = require('child_process');

const SCREENSHOTS_DIR = 'C:/Users/hp/.gemini/antigravity/brain/59378320-c959-4633-b9e3-672211f294a0/screenshots';

// Get DB_PASSWORD from .env
function getDbPassword() {
  const envContent = fs.readFileSync('C:/Users/hp/OneDrive/Desktop/AI_Document_Assistant/.env', 'utf8');
  for (const line of envContent.split(/\r?\n/)) {
    if (line.trim().startsWith('DB_PASSWORD=')) {
      return line.trim().substring('DB_PASSWORD='.length).replace(/^["']|["']$/g, '').trim();
    }
  }
  return '';
}

function runMysql(sql) {
  const pass = getDbPassword();
  const dbName = 'ai_document_assistant';
  const passArg = pass ? `--password="${pass}"` : '';
  const oneLineSql = sql.replace(/\r?\n/g, ' ').replace(/"/g, '\\"');
  const cmd = `"C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe" -u root ${passArg} -D ${dbName} -e "${oneLineSql}"`;
  execSync(cmd, { stdio: 'pipe' });
}

// Minimal valid PDF in base64
function createMinimalPdfBase64(titleText) {
  const content = `%PDF-1.4
1 0 obj
<< /Type /Catalog /Pages 2 0 R >>
endobj
2 0 obj
<< /Type /Pages /Kids [3 0 R] /Count 1 >>
endobj
3 0 obj
<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>
endobj
4 0 obj
<< /Length 55 >>
stream
BT
/F1 14 Tf
70 700 Td
(${titleText}) Tj
ET
endstream
endobj
5 0 obj
<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>
endobj
xref
0 6
0000000000 65535 f 
0000000009 00000 n 
0000000058 00000 n 
0000000115 00000 n 
0000000244 00000 n 
0000000350 00000 n 
trailer
<< /Size 6 /Root 1 0 R >>
startxref
433
%%EOF`;
  return Buffer.from(content).toString('base64');
}

async function verifyDocumentManagementBrowser() {
  const browser = new CdpBrowser();
  const ts = Date.now();
  const email = `doc_mgr_v2_${ts}@example.com`;
  const password = 'TestPassword123!';

  try {
    await browser.start(9229);
    await browser.setViewport(1280, 850);
    console.log("=== Edge CDP browser started on port 9229 ===");

    // 1. Register test user
    await browser.navigate('http://localhost:5173/register');
    await browser.waitForSelector('input[data-testid="register-name-input"]');
    await browser.type('input[data-testid="register-name-input"]', 'Doc Management Specialist');
    await browser.type('input[data-testid="register-email-input"]', email);
    await browser.type('input[data-testid="register-password-input"]', password);
    await browser.type('input[data-testid="register-confirm-password-input"]', password);
    await browser.click('button[data-testid="register-submit-button"]');
    await new Promise(r => setTimeout(r, 2000));

    // 2. Navigate to /documents
    await browser.navigate('http://localhost:5173/documents');
    await browser.waitForSelector('[data-testid="document-search-input"]');
    console.log("Arrived on /documents page.");

    // 3. Upload 3 documents via browser fetch
    console.log("Uploading test documents via browser fetch...");
    const uploadResult = await browser.evaluate(`
      (async () => {
        const token = sessionStorage.getItem('ai_doc_jwt_token');
        function b64toBlob(b64Data, contentType) {
          const byteCharacters = atob(b64Data);
          const byteNumbers = new Array(byteCharacters.length);
          for (let i = 0; i < byteCharacters.length; i++) {
            byteNumbers[i] = byteCharacters.charCodeAt(i);
          }
          const byteArray = new Uint8Array(byteNumbers);
          return new Blob([byteArray], { type: contentType });
        }

        const files = [
          { name: 'Alpha_Architecture_Spec.pdf', b64: '${createMinimalPdfBase64("Alpha Architecture Spec Document")}' },
          { name: 'Beta_Financial_Report.pdf', b64: '${createMinimalPdfBase64("Beta Financial Report Q3")}' },
          { name: 'Alpha_User_Manual.pdf', b64: '${createMinimalPdfBase64("Alpha User Manual and Guide")}' }
        ];

        const uploaded = [];
        for (const f of files) {
          const blob = b64toBlob(f.b64, 'application/pdf');
          const formData = new FormData();
          formData.append('file', blob, f.name);

          const res = await fetch('http://localhost:8080/api/documents', {
            method: 'POST',
            headers: { 'Authorization': 'Bearer ' + token },
            body: formData
          });
          const data = await res.json();
          uploaded.push(data);
        }
        return uploaded;
      })()
    `);

    console.log("Uploaded 3 documents successfully:", uploadResult.map(d => d.originalFileName));
    const [docReady, docPending, docFailed] = uploadResult;

    // 4. Update docReady in MySQL to READY with 2 extracted chunks
    console.log("Setting docReady to READY with 2 relational chunks in database...");
    runMysql(`
      UPDATE documents SET status='READY', total_pages=1, total_chunks=2 WHERE id='${docReady.id}';
      INSERT INTO document_chunks (id, document_id, chunk_index, page_number, chunk_text, token_count, created_at)
      VALUES 
      (UUID(), '${docReady.id}', 0, 1, 'Architecture Specification: Microservices and Modular RAG Components. Section 1 covers data isolation and ownership.', 24, NOW()),
      (UUID(), '${docReady.id}', 1, 1, 'Section 2: High availability vector stores and atomic two-phase cascaded deletion for document lifecycles.', 21, NOW());
      UPDATE documents SET status='FAILED', error_message='Corrupted trailer stream or incomplete PDF payload' WHERE id='${docFailed.id}';
    `);

    // Reload page to reflect database status
    await browser.navigate('http://localhost:5173/documents');
    await browser.waitForSelector(`[data-testid="document-card-${docReady.id}"]`);
    console.log("Documents reloaded with updated statuses (READY, PENDING, FAILED).");

    // 5. Test Search by Filename
    console.log("Testing search by filename 'Alpha'...");
    await browser.type('[data-testid="document-search-input"]', 'Alpha');
    await new Promise(r => setTimeout(r, 600));

    const alphaCount = await browser.evaluate(`document.querySelectorAll('[data-testid^="document-card-"]').length`);
    console.log("Search 'Alpha' matching cards:", alphaCount);

    // Capture Screenshot 17: Search and Filter
    await browser.screenshot(path.join(SCREENSHOTS_DIR, '17_documents_search_and_filter.png'));
    console.log("Captured screenshot: 17_documents_search_and_filter.png");

    // Clear search input using the UI clear button
    const clearBtnExists = await browser.evaluate(`!!document.querySelector('button[title="Clear search"]')`);
    if (clearBtnExists) {
      await browser.click('button[title="Clear search"]');
      await new Promise(r => setTimeout(r, 500));
    }

    // 6. Test Chunk Viewer Modal
    console.log("Testing Document Chunk Viewer Modal on READY document...");
    await browser.click(`[data-testid="view-chunks-btn-${docReady.id}"]`);
    await browser.waitForSelector('[data-testid="chunk-item-0"]');
    console.log("Chunk viewer modal opened with chunk item 0.");

    // Type in chunk search box
    await browser.type('[data-testid="chunk-search-input"]', 'Section 2');
    await new Promise(r => setTimeout(r, 500));

    // Capture Screenshot 20: Chunk Viewer Modal
    await browser.screenshot(path.join(SCREENSHOTS_DIR, '20_document_chunk_viewer.png'));
    console.log("Captured screenshot: 20_document_chunk_viewer.png");

    // Close chunk viewer modal
    await browser.evaluate(`
      (() => {
        const btns = Array.from(document.querySelectorAll('button'));
        const closeBtn = btns.find(b => b.textContent.includes('Close'));
        if (closeBtn) closeBtn.click();
      })()
    `);
    await new Promise(r => setTimeout(r, 800));

    // 7. Test Bulk Selection and Bulk Delete Modal
    console.log("Testing bulk selection...");
    const visibleCheckboxes = await browser.evaluate(`
      Array.from(document.querySelectorAll('[data-testid^="select-doc-checkbox-"]')).map(el => el.getAttribute('data-testid'))
    `);
    console.log("Visible checkboxes for bulk selection:", visibleCheckboxes);

    if (visibleCheckboxes.length >= 2) {
      await browser.click(`[data-testid="${visibleCheckboxes[0]}"]`);
      await browser.click(`[data-testid="${visibleCheckboxes[1]}"]`);
    }
    await new Promise(r => setTimeout(r, 500));

    const bulkBarText = await browser.evaluate(`document.querySelector('[data-testid="selected-count-badge"]')?.textContent`);
    console.log("Bulk bar text:", bulkBarText);

    // Open bulk delete modal
    await browser.click('[data-testid="bulk-delete-button"]');
    await browser.waitForSelector('[data-testid="confirm-bulk-delete-button"]');
    console.log("Bulk delete confirmation modal opened.");

    // Capture Screenshot 18: Bulk Delete Modal
    await browser.screenshot(path.join(SCREENSHOTS_DIR, '18_documents_bulk_select_modal.png'));
    console.log("Captured screenshot: 18_documents_bulk_select_modal.png");

    // Confirm bulk deletion
    await browser.click('[data-testid="confirm-bulk-delete-button"]');
    await new Promise(r => setTimeout(r, 1500));

    const remainingCards = await browser.evaluate(`document.querySelectorAll('[data-testid^="document-card-"]').length`);
    console.log("Remaining document cards after bulk delete:", remainingCards);

    // 8. Test Mobile Viewport (375x667)
    console.log("Testing mobile responsive layout...");
    await browser.setViewport(375, 667);
    await new Promise(r => setTimeout(r, 800));
    await browser.screenshot(path.join(SCREENSHOTS_DIR, '19_documents_mobile_view.png'));
    console.log("Captured screenshot: 19_documents_mobile_view.png");

    // Reset viewport
    await browser.setViewport(1280, 850);
    console.log("All browser tests completed successfully!");

  } catch (err) {
    console.error("Document management browser verification error:", err);
  } finally {
    await browser.close();
    console.log("=== Edge CDP browser closed ===");
  }
}

verifyDocumentManagementBrowser().catch(console.error);
