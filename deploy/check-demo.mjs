// End-to-end check of the deployed demo: opens the landing page, signs in with
// a demo surgeon account and confirms the schedule renders with seeded bookings.
// Runs against a local SSH tunnel, so it works before DNS is switched.
import puppeteer from 'puppeteer-core';

const base = process.env.SITE_URL || 'https://kliniq.izotov.dev';
const port = process.env.TUNNEL_PORT || '8443';

const browser = await puppeteer.launch({
  executablePath: process.env.CHROME_PATH,
  headless: 'new',
  args: [
    '--no-sandbox',
    '--disable-dev-shm-usage',
    // The origin certificate is issued by Cloudflare's Origin CA, which no
    // browser trusts directly — only Cloudflare does. Locally we bypass.
    '--ignore-certificate-errors',
    `--host-resolver-rules=MAP kliniq.izotov.dev 127.0.0.1:${port}`,
  ],
});

const page = await browser.newPage();
const problems = [];
page.on('pageerror', e => problems.push('pageerror: ' + String(e).slice(0, 160)));
page.on('response', r => {
  if (r.status() >= 500) problems.push(`${r.status()} ${r.url().slice(0, 80)}`);
});

console.log('1. Лендинг');
const landing = await page.goto(base + '/welcome', { waitUntil: 'networkidle0', timeout: 45000 });
console.log(`   ${landing.status()} · "${(await page.title()).slice(0, 60)}"`);

console.log('2. Страница входа');
await page.goto(base + '/login', { waitUntil: 'networkidle0', timeout: 45000 });
const fields = await page.evaluate(() =>
  [...document.querySelectorAll('input')].map(i => i.type + (i.name ? `[${i.name}]` : '')).join(', '));
console.log(`   поля: ${fields}`);

console.log('3. Вход демо-аккаунтом');
await page.type('input[type="email"], input[name="email"]', 'drsmith@kliniq-demo.local');
await page.type('input[type="password"], input[name="password"]', 'DemoSurgeon2026!');
await Promise.all([
  page.waitForNavigation({ waitUntil: 'networkidle0', timeout: 45000 }).catch(() => {}),
  page.click('button[type="submit"]'),
]);
await new Promise(r => setTimeout(r, 2500));
console.log(`   после входа: ${page.url().replace(base, '')}`);

console.log('4. Расписание');
if (!page.url().includes('/schedule')) {
  await page.goto(base + '/schedule', { waitUntil: 'networkidle0', timeout: 45000 });
  await new Promise(r => setTimeout(r, 2500));
}
const state = await page.evaluate(() => ({
  url: location.pathname,
  text: document.body.innerText.replace(/\s+/g, ' ').slice(0, 220),
  bookings: document.querySelectorAll('[data-booking-id], [class*="booking"]').length,
}));
console.log(`   ${state.url} · элементов брони в DOM: ${state.bookings}`);
console.log(`   текст: ${state.text.slice(0, 180)}`);

console.log(problems.length ? `\nПроблемы: ${problems.slice(0, 4).join(' | ')}` : '\nОшибок нет');
await browser.close();
