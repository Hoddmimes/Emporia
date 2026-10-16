// Takes the user guide's screenshots of the trading web interface with headless Chrome (DevTools
// protocol), logged in as anna of NORDAGRI on a server filled by seed.mjs. Some events are made
// live through the REST API as other users (a trade on one of anna's orders, an offer, a chat
// message) so their popups are shown.
//
//   node screenshots.mjs http://127.0.0.1:18090/ ../../Server/WebContent/guide
import { startChrome, sleep } from './chrome.mjs';

const BASE = process.argv[2] || 'http://127.0.0.1:18090/';
const OUT  = process.argv[3] || '../../Server/WebContent/guide';

// ── REST as the other users ────────────────────────────────────────────
async function restLogin(firm, userId) {
    const r = await fetch(BASE + 'api/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
                                                body: JSON.stringify({ firm, userId, password: 'demo' }) });
    if (!r.ok) throw new Error('login ' + userId + ': HTTP ' + r.status);
    return r.headers.get('set-cookie').split(';')[0];
}
async function rest(cookie, method, path, body) {
    const r = await fetch(BASE + path, { method, headers: { 'Content-Type': 'application/json', Cookie: cookie },
                                         body: body === undefined ? undefined : JSON.stringify(body) });
    if (!r.ok) throw new Error(method + ' ' + path + ': HTTP ' + r.status + ' ' + await r.text());
    return r.json();
}
const erik = await restLogin('BALTCOM', 'erik');
const lena = await restLogin('WESTFARM', 'lena');

const { ev, navigate, shot, mark, unmark, close } = await startChrome(BASE, OUT);
const clearToasts = () => ev(`document.getElementById('mv-toasts').innerHTML = ''; document.getElementById('mv-chat-toasts').innerHTML = ''`);
const closeDialogs = () => ev(`document.querySelectorAll('dialog[open]').forEach(d => d.close())`);
const selectMarketNamed = (name) => ev(`selectMarket(markets.find(m => m.name === ${JSON.stringify(name)}))`);

// ── Login ──────────────────────────────────────────────────────────────
console.log('Taking screenshots into ' + OUT);
await navigate('login.html');
await ev(`document.getElementById('firm').value = 'NORDAGRI'; document.getElementById('username').value = 'anna';
          document.getElementById('password').value = 'demo'; 1`);
await sleep(300);
await shot('login', '.login-card', 24);
await ev(`document.getElementById('login-form').requestSubmit(); 1`);
await sleep(2500);

// ── Market dashboard ───────────────────────────────────────────────────
await ev(`localStorage.setItem('emporia.clientAccount', 'fund-1'); 1`);
await selectMarketNamed('Grains');
await sleep(1500);
await mark([
    [1, "(r => ({ x: r.right + 10, y: r.top - 6 }))(textEnd('.mv-sidebar h4'))"],
    [2, "(r => ({ x: r.right + 10, y: r.top + (r.height - 26) / 2 }))(at('.mv-actions button[data-view=\\'chat\\']'))"],
    [3, "(r => ({ x: r.right + 10, y: r.top + (r.height - 26) / 2 }))(textEnd('#mv-market-meta'))"],
    [4, "(r => ({ x: r.left - 36, y: r.top + (r.height - 26) / 2 }))(at('.mv-wss-status'))"],
    [5, "(r => ({ x: r.left + 170, y: r.top + 9 }))(at('#mv-prices'))"],
    [6, "(r => ({ x: r.right + 10, y: r.top + (r.height - 26) / 2 }))(textEnd('.mv-dash-bottom .mv-pane-hint'))"],
    [7, "(r => ({ x: r.right + 10, y: r.top + (r.height - 26) / 2 }))(textEnd('.mv-dash-bottom section:last-child h4'))"]]);
await shot('dashboard');
await unmark();

// The order book of WHEAT-DEC (a click on its row)
const wheat = await ev(`instruments.find(i => i.name === 'WHEAT-DEC').id`);
await ev(`openBookDialog(${wheat})`);
await sleep(1000);
await shot('order-book', '#book-dialog');
await closeDialogs();

// Enter Order, opened by a double click on WHEAT-DEC's ask
await ev(`document.querySelector('tr[data-instrument-id="${wheat}"] td[data-side="ask"]')
            .dispatchEvent(new MouseEvent('dblclick', { bubbles: true })); 1`);
await sleep(500);
await ev(`document.getElementById('of-userRef').value = 'W-17'; 1`);
await shot('enter-order', '#order-dialog');
await closeDialogs();

// Delete order, a double click on one of the orders
await ev(`openDeleteOrderDialog(dashOrders.find(o => o.instrumentName === 'WHEAT-DEC') || dashOrders[0])`);
await sleep(300);
await shot('delete-order', '#delete-order-dialog');
await closeDialogs();

// A trade popup: anna buys CORN-DEC at the best ask
const corn = await ev(`instruments.find(i => i.name === 'CORN-DEC').id`);
await clearToasts();
await ev(`postJson('api/orders', { instrumentId: ${corn}, side: 'BUY', price: Math.round(priceState.get(${corn}).askPx * SCALE),
                                   quantity: Math.round(priceState.get(${corn}).askVol * SCALE), userRef: 'C-21', clientAccount: 'fund-1' })
          .then(r => r.status)`);
await sleep(1500);
await shot('trade-popup', '#mv-toasts', 20);
await clearToasts();

// ── My Orders, My Trades, My Portfolio ─────────────────────────────────
await ev(`onMyOrders()`);
await sleep(1200);
await shot('my-orders');
await ev(`onMyTrades()`);
await sleep(1200);
await shot('my-trades');
await ev(`onMyPortfolio()`);
await sleep(1200);
await shot('portfolio');
await ev(`setPortfolioAccount('*total')`);
await sleep(500);
await shot('portfolio-total');

// ── Bulletins ──────────────────────────────────────────────────────────
await selectMarketNamed('OTC-Energy');
await sleep(1200);
await shot('bulletin-market');
await ev(`openOfferDialog(bulletins.find(b => b.instrument === 'Brent Dec')); document.getElementById('od-offerRef').value = 'BD-2'; 1`);
await sleep(400);
await shot('make-offer', '#offer-dialog');
await closeDialogs();
await ev(`onEnterOrder(); document.getElementById('of-instrument-text').value = 'Brent Feb';
          document.querySelector('input[name="of-side"][value="SELL"]').checked = true; syncSideLabels();
          document.getElementById('of-price').value = '81.50'; document.getElementById('of-quantity').value = '2000';
          document.getElementById('of-userRef').value = 'BF-1'; document.getElementById('of-clientAccount').value = 'hedge'; 1`);
await sleep(400);
await shot('enter-bulletin', '#order-dialog');
await closeDialogs();

// A new offer on anna's Fuel Oil bulletin: the popup, then Answer opens the response dialog
const fuelOil = await ev(`fetch('api/bulletins').then(r => r.json()).then(d => d.bulletins.find(b => b.own && b.instrument === 'Fuel Oil 0.5%').bulletinId)`);
await clearToasts();
await rest(erik, 'POST', `api/bulletins/${fuelOil}/offer`, { price: 51400000, quantity: 50000000, offerRef: 'E-80', validTime: 300 });
await sleep(1500);
await shot('offer-popup', '#mv-toasts', 20);
await ev(`document.querySelector('#mv-toasts [data-act="answer"]').click(); 1`);
await sleep(1500);
await shot('offer-response', '#response-dialog');
await closeDialogs();
await sleep(500);
await shot('bulletin-orders');

// Withdrawing an own bulletin: its Withdraw button in Bulletin Orders
await ev(`document.querySelector('button[data-withdraw-bulletin-id="' + myBulletins.find(b => b.instrument === 'Diesel Feb').bulletinId + '"]').click(); 1`);
await sleep(400);
await shot('withdraw-bulletin', '#withdraw-bulletin-dialog');
await ev(`withdrawBulletin()`);
await sleep(1200);
const left = await ev(`myBulletins.map(b => b.instrument + ' ' + b.state).join(', ')`);
if (left.includes('Diesel Feb')) throw new Error('the bulletin was not withdrawn: ' + left);
await clearToasts();

// ── Chat, with a popup of a message from lena ─────────────────────────
await ev(`onChat()`);
await sleep(1200);
await rest(lena, 'POST', 'api/chat', { text: 'Oats harvest in the north is late this year, expect less supply in December.', tag: 'grains' });
await sleep(1200);
await shot('chat');

await close();
