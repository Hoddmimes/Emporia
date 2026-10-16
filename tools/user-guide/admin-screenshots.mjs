// Takes the admin guide's screenshots of the admin web interface with headless Chrome (DevTools
// protocol), logged in as Admin on a server filled by seed.mjs. It first adds a little more through
// the admin REST API: contact details of NORDAGRI and anna, an inactive firm EASTGRAIN with a user and
// authorizations for single markets, and an auction market.
//
//   node admin-screenshots.mjs http://127.0.0.1:18090/ ../../Server/WebContent/admin/guide
import { startChrome, sleep } from './chrome.mjs';

const BASE = process.argv[2] || 'http://127.0.0.1:18090/';
const OUT  = process.argv[3] || '../../Server/WebContent/admin/guide';

// ── More reference data (admin REST) ──────────────────────────────────
const lr = await fetch(BASE + 'api/admin/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
                                                   body: JSON.stringify({ username: 'Admin', password: 'changeit' }) });
if (!lr.ok) throw new Error('admin login: HTTP ' + lr.status);
const admin = lr.headers.get('set-cookie').split(';')[0];
async function rest(method, path, body) {
    const r = await fetch(BASE + path, { method, headers: { 'Content-Type': 'application/json', Cookie: admin },
                                         body: body === undefined ? undefined : JSON.stringify(body) });
    const text = await r.text();
    if (!r.ok) throw new Error(method + ' ' + path + ': HTTP ' + r.status + ' ' + text);
    return text ? JSON.parse(text) : null;
}
const nordagri = (await rest('GET', 'api/admin/firms')).find(f => f.name === 'NORDAGRI');
await rest('PATCH', 'api/admin/firms/' + nordagri.id, { status: 'ACTIVE',
    address: 'Hamngatan 4, 211 22 Malmö', phone: '+46 40 123 45 00', contact: 'Anna Lind', mail: 'trading@nordagri.example' });
const anna = (await rest('GET', 'api/admin/users')).find(u => u.id === 'anna');
await rest('PATCH', 'api/admin/users/' + anna.databaseId, { firm: 'NORDAGRI', name: 'Anna Lind', status: 'ACTIVE',
    mail: 'anna.lind@nordagri.example', phone: '+46 70 555 01 23' });
await rest('POST', 'api/admin/firms', { name: 'EASTGRAIN', status: 'INACTIVE', contact: 'Olof Ek' });
await rest('POST', 'api/admin/users', { userId: 'olof', name: 'Olof Ek', firm: 'EASTGRAIN', status: 'INACTIVE', password: 'demo' });
await rest('POST', 'api/admin/authorizations', { firm: 'EASTGRAIN', market: '1' });
await rest('POST', 'api/admin/authorizations', { firm: 'EASTGRAIN', market: '3' });
await rest('POST', 'api/admin/markets', { name: 'Dairy', description: 'Butter and milk powder, call auctions',
    type: 'AUCTION', open: '09:00', close: '16:00', tradingStatus: 'CLOSED', auctionInterval: '900', randomAuctionInterval: '60' });

// ── Chrome ─────────────────────────────────────────────────────────────
const { ev, navigate, shot, mark, unmark, resize, close } = await startChrome(BASE, OUT);
const CONTENT = '.cd-content > *';
const FORM = '.cd-content h2, .cd-content .card';   // a form page: its heading and the form, not the whole width
const set = (fields) => ev(`(() => { const f = ${JSON.stringify(fields)};
    for (const [id, v] of Object.entries(f)) { const e = document.getElementById(id);
        if (e.type === 'checkbox') e.checked = v; else e.value = v;
        e.dispatchEvent(new Event('input', { bubbles: true })); e.dispatchEvent(new Event('change', { bubbles: true })); }
    return 1; })()`);
// Opens a detail page by clicking the list row that contains the text
async function openRow(text) {
    await ev(`[...document.querySelectorAll('tr.row-link, td.row-link')].find(e => e.textContent.includes(${JSON.stringify(text)})).click(); 1`);
    await sleep(1500);
}

console.log('Taking screenshots into ' + OUT);
await navigate('admin/login.html');
await set({ username: 'Admin', password: 'changeit' });
await sleep(300);
await shot('admin-login', '.login-card', 24);
await ev(`document.getElementById('login-form').requestSubmit(); 1`);
await sleep(2000);

// ── The start page, with callouts ──────────────────────────────────────
await mark([
    [1, "(r => ({ x: at('.cd-sidebar').right - 44, y: r.top + (r.height - 26) / 2 }))(textEnd('.cd-sidebar .nav-section'))"],
    [2, "(r => ({ x: r.left - 36, y: r.top + (r.height - 26) / 2 }))(at('#user-info'))"],
    [3, "(r => ({ x: r.right + 8, y: r.top + (r.height - 26) / 2 }))(textEnd('.cd-sidebar a[href=\"admin-guide.html\"]'))"]]);
await shot('admin-dashboard');
await unmark();
await resize(1400);   // the forms are taller than the window; the pictures are clipped to their content

// ── Firms ──────────────────────────────────────────────────────────────
await navigate('admin/firms.html');
await shot('firms', CONTENT);
await navigate('admin/create-firm.html');
await set({ 'f-name': 'SOUTHMILL', 'f-status': 'ACTIVE', 'f-address': 'Kvarngatan 1, 582 22 Linköping',
            'f-phone': '+46 13 400 10 00', 'f-contact': 'Karin Strand', 'f-mail': 'desk@southmill.example' });
await shot('create-firm', FORM);
await navigate('admin/firms.html');
await openRow('NORDAGRI');
await shot('firm', CONTENT);
await ev(`showDeleteModal(); 1`);
await sleep(300);
await shot('delete-firm', '#delete-modal .modal');

// ── Users ──────────────────────────────────────────────────────────────
await navigate('admin/users.html');
await shot('users', CONTENT);
await navigate('admin/create-user.html');
await sleep(500);
await set({ 'f-userId': 'karin', 'f-firm': 'NORDAGRI', 'f-name': 'Karin Strand', 'f-mail': 'karin.strand@nordagri.example',
            'f-phone': '+46 70 555 04 56', 'f-password': 'secret', 'f-status': 'ACTIVE' });
await shot('create-user', FORM);
await navigate('admin/users.html');
await openRow('Anna Lind');
await shot('user', CONTENT);

// ── Markets ────────────────────────────────────────────────────────────
await navigate('admin/markets.html');
await shot('markets', CONTENT);
await navigate('admin/create-market.html');
await set({ 'f-name': 'Softs', 'f-description': 'Sugar, coffee and cocoa', 'f-type': 'CONTINUOUS', 'f-tradingStatus': 'CLOSED',
            'f-open': '08:30', 'f-close': '17:30', 'f-preOpenTime': '08:15' });
await shot('create-market', FORM);
await navigate('admin/markets.html');
await openRow('Grains');
await shot('market', CONTENT);

// ── Bulletin markets ───────────────────────────────────────────────────
await navigate('admin/bulletin-markets.html');
await shot('bulletin-markets', CONTENT);
await navigate('admin/create-bulletin-market.html');
await set({ 'f-name': 'OTC-Machinery', 'f-description': 'Used farm machinery', 'f-tradingStatus': 'CLOSED',
            'f-open': '07:00', 'f-close': '19:00', 'f-instruments': 'Combine harvester\n/Tractor .*/\n/Baler .*/',
            'f-maxOrderTime': '168:00' });
await shot('create-bulletin-market', FORM);
await navigate('admin/bulletin-markets.html');
await openRow('OTC-Energy');
await shot('bulletin-market', CONTENT);

// ── Instruments ────────────────────────────────────────────────────────
await navigate('admin/instruments.html');
await ev(`const s = document.getElementById('market-select');
          s.value = [...s.options].find(o => o.textContent.startsWith('Grains')).value; loadInstruments(); 1`);
await sleep(1000);
await shot('instruments', CONTENT);
await openRow('WHEAT-DEC');
await shot('instrument', CONTENT);
await navigate('admin/create-instrument.html');
await sleep(500);
await ev(`const s = document.getElementById('f-marketId');
          s.value = [...s.options].find(o => o.textContent.startsWith('Grains')).value; 1`);
await set({ 'f-name': 'WHEAT-MAR', 'f-instType': 'FUTURE', 'f-tickSize': '0.25', 'f-lotSize': '10',
            'f-expirationDate': '2027-03-15', 'f-strikePrice': '230', 'f-underlying': 'Milling wheat' });
await shot('create-instrument', FORM);

// Load Instruments: a file with two new metals and the delete of an instrument that does not exist
const load = { ADD: [
        { ID: 'LEAD',   market: 'Metals', instType: 'COMMODITY', tickSize: 0.5, lotSize: 1 },
        { ID: 'COBALT', market: 'Metals', instType: 'COMMODITY', tickSize: 1,   lotSize: 1 }],
    DELETE: [ { ID: '8589934699' } ] };
await navigate('admin/load-instruments.html');
await ev(`parsedJson = ${JSON.stringify(load)}; showPreview('metals-2026-10.json', parsedJson); 1`);
await sleep(300);
await shot('load-instruments', CONTENT);
await ev(`processFile()`);
await sleep(1000);
await ev(`document.getElementById('results-card').scrollIntoView(); 1`);
await shot('load-results', '#results-card', 8);

// ── Authorizations ─────────────────────────────────────────────────────
await navigate('admin/authorizations.html');
await set({ 'firm-select': 'EASTGRAIN' });
await sleep(800);
await shot('authorizations', CONTENT);
await navigate('admin/create-authorization.html');
await sleep(500);
await ev(`const m = document.getElementById('f-market');
          m.value = [...m.options].find(o => o.textContent.startsWith('Metals')).value; 1`);
await set({ 'f-firm': 'EASTGRAIN' });
await shot('create-authorization', FORM);

// ── Change password ────────────────────────────────────────────────────
await navigate('admin/change-password.html');
await set({ 'f-current': 'changeit', 'f-new': 'a-better-one', 'f-confirm': 'a-better-one' });
await shot('change-password', FORM);

await close();
