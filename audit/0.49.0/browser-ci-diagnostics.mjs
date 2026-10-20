// Read-only diagnostics for the unmodified production-browser acceptance test.
import {createRequire} from 'node:module';
const require = createRequire(import.meta.url);
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright-core');
const launch = chromium.launchPersistentContext.bind(chromium);
function safe(value) {
  return String(value).replace(/[a-f0-9]{64}/gi, '[redacted token]').slice(0, 2000);
}
chromium.launchPersistentContext = async (...args) => {
  const context = await launch(...args);
  function observe(page) {
    page.on('pageerror', error => console.error('BROWSER_PAGE_ERROR', safe(error.message)));
    page.on('console', message => {
      if (message.type() === 'error') console.error('BROWSER_CONSOLE_ERROR', safe(message.text()));
    });
  }
  context.pages().forEach(observe);
  context.on('page', observe);
  const close = context.close.bind(context);
  context.close = async (...closeArgs) => {
    for (const page of context.pages()) {
      if (!page.url().startsWith('chrome-extension://')) continue;
      try {
        const state = await page.evaluate(() => ({
          status: document.querySelector('#status')?.textContent,
          diagnostics: document.querySelector('#diagnostics')?.textContent,
          addressPresent: Boolean(document.querySelector('#base')?.value),
          tokenLength: document.querySelector('#token')?.value.length
        }));
        console.error('BROWSER_POPUP_STATE', safe(JSON.stringify(state)));
      } catch (error) {
        console.error('BROWSER_DIAGNOSTIC_ERROR', safe(error.message));
      }
    }
    return close(...closeArgs);
  };
  return context;
};
