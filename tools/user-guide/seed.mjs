// Fills an empty Emporia server with demo data for the user guide's screenshots, through the REST API:
// three firms, two continuous markets and a bulletin market, the day's trades, resting orders,
// bulletins, offers and chat messages. The screenshots are taken as user anna of firm NORDAGRI.
//
//   node seed.mjs http://127.0.0.1:18090
const BASE = process.argv[2] || 'http://127.0.0.1:18090';
const SCALE = 100000;
const PASSWORD = 'demo';

async function call(cookie, method, path, body) {
    const r = await fetch(BASE + path, {
        method,
        headers: Object.assign({ 'Content-Type': 'application/json' }, cookie ? { Cookie: cookie } : {}),
        body: body === undefined ? undefined : JSON.stringify(body)
    });
    const text = await r.text();
    if (!r.ok) throw new Error(`${method} ${path} ${JSON.stringify(body)}: HTTP ${r.status} ${text}`);
    return text ? JSON.parse(text) : null;
}

async function login(path, body) {
    const r = await fetch(BASE + path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    if (!r.ok) throw new Error(`login ${JSON.stringify(body)}: HTTP ${r.status} ${await r.text()}`);
    return r.headers.get('set-cookie').split(';')[0];
}

// ── Reference data (admin) ─────────────────────────────────────────────
const admin = await login('/api/admin/login', { username: 'Admin', password: 'changeit' });
const FIRMS = { NORDAGRI: ['anna', 'Anna Lind'], BALTCOM: ['erik', 'Erik Berg'], WESTFARM: ['lena', 'Lena Holm'] };
for (const [firm, [userId, name]] of Object.entries(FIRMS)) {
    await call(admin, 'POST', '/api/admin/firms', { name: firm, status: 'ACTIVE' });
    await call(admin, 'POST', '/api/admin/users', { userId, name, firm, status: 'ACTIVE', password: PASSWORD });
    await call(admin, 'POST', '/api/admin/authorizations', { firm, market: '*' });
}
await call(admin, 'POST', '/api/admin/markets', { name: 'Grains', description: 'Grain and oilseed futures',
    type: 'CONTINUOUS', open: '08:00', close: '18:00', tradingStatus: 'OPEN' });
await call(admin, 'POST', '/api/admin/markets', { name: 'Metals', description: 'Base metals, cash',
    type: 'CONTINUOUS', open: '08:00', close: '18:00', tradingStatus: 'OPEN' });
await call(admin, 'POST', '/api/admin/bulletin-markets', { name: 'OTC-Energy', description: 'Oil products, bilateral',
    tradingStatus: 'OPEN', open: '08:00', close: '18:00', maxOrderTime: '72:00',
    instruments: ['/Brent .*/', '/Diesel .*/', 'Jet Fuel', 'Fuel Oil 0.5%'] });

// Grains: tick 0.25, lot 10; metals: tick 0.5, lot 1
const GRAINS = { 'WHEAT-DEC': 228.50, 'CORN-DEC': 196.25, 'BARLEY-DEC': 205.00, 'OATS-DEC': 172.75,
                 'RAPESEED-NOV': 468.50, 'SOYBEAN-JAN': 412.00 };
const METALS = { 'COPPER': 9850, 'ALUMINIUM': 2615, 'ZINC': 2940, 'NICKEL': 16850, 'TIN': 31200 };
for (const name of Object.keys(GRAINS)) await call(admin, 'POST', '/api/admin/instruments',
    { marketId: '1', name, instType: 'COMMODITY', tickSize: 0.25 * SCALE, lotSize: 10 * SCALE });
for (const name of Object.keys(METALS)) await call(admin, 'POST', '/api/admin/instruments',
    { marketId: '2', name, instType: 'COMMODITY', tickSize: 0.5 * SCALE, lotSize: 1 * SCALE });

// ── Trading ────────────────────────────────────────────────────────────
const s = {};
for (const [firm, [userId]] of Object.entries(FIRMS)) s[userId] = await login('/api/login', { firm, userId, password: PASSWORD });

const ids = {};
for (const m of [1, 2]) for (const i of (await call(s.anna, 'GET', '/api/instruments?marketId=' + m)).instruments) ids[i.name] = i.id;

// A deterministic random sequence, so every run gives the same pictures
let seed = 7;
const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
const pick = (a) => a[Math.floor(rnd() * a.length)];

const order = (user, instrument, side, price, quantity, extra = {}) =>
    call(s[user], 'POST', '/api/orders', Object.assign({ instrumentId: ids[instrument], side,
        price: Math.round(price * SCALE), quantity: Math.round(quantity * SCALE) }, extra));

// A trade: the seller's order rests, the buyer's matches it (the book is empty at that price)
async function trade(instrument, price, quantity, buyer, seller, buyExtra = {}, sellExtra = {}) {
    await order(seller, instrument, 'SELL', price, quantity, sellExtra);
    await order(buyer, instrument, 'BUY', price, quantity, buyExtra);
}

const round = (v, tick) => Math.round(v / tick) * tick;
const others = ['erik', 'lena'];
const ACCOUNTS = ['fund-1', 'hedge', 'omni'];
let ref = 100;

async function day(instruments, tick, lots) {
    for (const [name, mid] of Object.entries(instruments)) {
        // The day's trades, drifting around the mid price; anna is one side of some of them
        let price = mid;
        for (let n = 0; n < 5; n++) {
            price = round(price + (rnd() - 0.5) * tick * 8, tick);
            const qty = pick(lots);
            if (n % 2 === 0) {
                const annaBuys = rnd() < 0.6;
                const extra = { userRef: 'R' + (ref++), clientAccount: pick(ACCOUNTS) };
                if (annaBuys) await trade(name, price, qty, 'anna', pick(others), extra, {});
                else          await trade(name, price, qty, pick(others), 'anna', {}, extra);
            } else {
                const [a, b] = rnd() < 0.5 ? others : others.slice().reverse();
                await trade(name, price, qty, a, b);
            }
        }
        // The resting book: four levels a side from the others, one or two of anna's own orders
        for (let l = 1; l <= 4; l++) {
            await order(pick(others), name, 'BUY',  price - l * tick * 2, pick(lots));
            await order(pick(others), name, 'SELL', price + l * tick * 2, pick(lots));
            if (rnd() < 0.4) await order(pick(others), name, 'BUY', price - l * tick * 2, pick(lots));
        }
        await order('anna', name, 'BUY', price - tick * 4, pick(lots), { userRef: 'R' + (ref++), clientAccount: pick(ACCOUNTS) });
        if (rnd() < 0.5) await order('anna', name, 'SELL', price + tick * 6, pick(lots), { userRef: 'R' + (ref++), clientAccount: pick(ACCOUNTS) });
    }
}
await day(GRAINS, 0.25, [50, 100, 150, 250]);
await day(METALS, 0.5, [5, 10, 20, 25]);

// ── Bulletins ──────────────────────────────────────────────────────────
const bulletin = (user, body) => call(s[user], 'POST', '/api/bulletins', Object.assign({ marketId: 3 }, body));
const sc = (v) => Math.round(v * SCALE);
await bulletin('erik', { instrument: 'Brent Dec', side: 'SELL', price: sc(80.25), quantity: sc(5000), userRef: 'BD-12' });
const diesel = await bulletin('lena', { instrument: 'Diesel Jan', side: 'BUY', quantity: sc(2000) });
await bulletin('erik', { instrument: 'Jet Fuel', price: sc(742), quantity: sc(1000) });
// anna's Brent Jan: one offer accepted (a bulletin trade), a second one pending
const brent = await bulletin('anna', { instrument: 'Brent Jan', side: 'SELL', price: sc(81.10), quantity: sc(3000),
                                       userRef: 'BJ-01', clientAccount: 'hedge' });
await call(s.lena, 'POST', `/api/bulletins/${brent.bulletinId}/offer`, { price: sc(81.00), quantity: sc(1000), validTime: 600 });
await call(s.anna, 'POST', `/api/bulletins/${brent.bulletinId}/response`, { accept: true });
await call(s.erik, 'POST', `/api/bulletins/${brent.bulletinId}/offer`, { price: sc(80.90), quantity: sc(2000), offerRef: 'E-77', validTime: 3600 });
await bulletin('anna', { instrument: 'Fuel Oil 0.5%', side: 'BUY', price: sc(515), quantity: sc(500), userRef: 'FO-3', clientAccount: 'fund-1' });
await bulletin('anna', { instrument: 'Diesel Feb', side: 'SELL', price: sc(770), quantity: sc(1000), userRef: 'DF-1' });
// anna's own pending offer on lena's Diesel Jan
await call(s.anna, 'POST', `/api/bulletins/${diesel.bulletinId}/offer`, { price: sc(765.50), quantity: sc(2000), offerRef: 'DJ-1',
                                                                         validTime: 3600, clientAccount: 'fund-1' });

// ── Chat ───────────────────────────────────────────────────────────────
const chat = (user, text, tag) => call(s[user], 'POST', '/api/chat', { text, tag });
await chat('erik', 'Good morning all. Wheat looks bid after the crop report.', 'grains');
await chat('lena', 'Anyone interested in Diesel Jan? We are buyers of 2000 t, see the bulletin.', 'energy');
await chat('anna', 'Copper offered at 9860 in 20 t if anyone needs it.', 'metals');
await chat('erik', 'Thanks, we will take a look after lunch.', 'metals');

console.log('seeded: instruments', Object.keys(ids).length);
