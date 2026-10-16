// Headless Chrome over the DevTools protocol for the guides' screenshots (screenshots.mjs for the
// user guide, admin-screenshots.mjs for the admin guide): a 1600x900 window at 2x, en-GB locale.
import { spawn } from 'node:child_process';
import { writeFileSync, mkdtempSync, mkdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

export const WIDTH = 1600, HEIGHT = 900, SCALE_FACTOR = 2;
const DEBUG_PORT = 9341;

export const sleep = (ms) => new Promise(r => setTimeout(r, ms));

// Starts Chrome and returns the helpers that drive its page; base is the server URL ending in /,
// out the directory the PNGs are written to
export async function startChrome(base, out) {
    mkdirSync(out, { recursive: true });
    const profile = mkdtempSync(join(tmpdir(), 'emporia-guide-'));
    const chrome = spawn('google-chrome', ['--headless=new', '--remote-debugging-port=' + DEBUG_PORT,
        '--window-size=' + WIDTH + ',' + HEIGHT, '--user-data-dir=' + profile, '--no-first-run',
        '--hide-scrollbars', '--lang=en-GB', 'about:blank'], { stdio: 'ignore' });
    let targets;
    for (let i = 0; i < 50 && !targets; i++) {
        try { targets = await (await fetch('http://127.0.0.1:' + DEBUG_PORT + '/json')).json(); } catch { await sleep(200); }
    }
    const ws = new WebSocket(targets.find(t => t.type === 'page').webSocketDebuggerUrl);
    await new Promise(r => ws.onopen = r);
    let id = 0;
    const pending = new Map();
    const problems = [];
    ws.onmessage = (e) => {
        const m = JSON.parse(e.data);
        if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
        if (m.method === 'Runtime.exceptionThrown') problems.push(m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text);
        if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') problems.push(m.params.args.map(a => a.value ?? a.description).join(' '));
        // alert() and confirm() would block the page: accept them
        if (m.method === 'Page.javascriptDialogOpening') send('Page.handleJavaScriptDialog', { accept: true });
    };
    const send = (method, params = {}) => new Promise(r => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });

    async function ev(expr) {
        const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
        if (r.result?.exceptionDetails) throw new Error(expr + ': ' + (r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text));
        return r.result?.result?.value;
    }
    // The window's height, for pages taller than the default (shots are clipped to the window)
    let height = HEIGHT;
    async function resize(h) {
        height = h;
        await send('Emulation.setDeviceMetricsOverride', { width: WIDTH, height, deviceScaleFactor: SCALE_FACTOR, mobile: false });
    }
    async function navigate(path) {
        await send('Page.navigate', { url: base + path });
        await sleep(1500);
    }

    // The whole window, or the element(s) given by a CSS selector with a margin around them
    async function shot(name, selector, pad = 16) {
        const params = { format: 'png' };
        if (selector) {
            const r = await ev(`(() => { const rs = [...document.querySelectorAll(${JSON.stringify(selector)})]
                .map(e => e.getBoundingClientRect()).filter(r => r.width > 0);
                if (!rs.length) return null;
                const x = Math.min(...rs.map(r => r.left)), y = Math.min(...rs.map(r => r.top));
                return { x, y, w: Math.max(...rs.map(r => r.right)) - x, h: Math.max(...rs.map(r => r.bottom)) - y }; })()`);
            if (!r) throw new Error('nothing to capture: ' + selector);
            const x = Math.max(0, r.x - pad), y = Math.max(0, r.y - pad);
            params.clip = { x, y, width: Math.min(WIDTH - x, r.w + 2 * pad), height: Math.min(height - y, r.h + 2 * pad), scale: 1 };
        }
        const res = await send('Page.captureScreenshot', params);
        writeFileSync(join(out, name + '.png'), Buffer.from(res.result.data, 'base64'));
        console.log('  ' + name + '.png');
    }

    // Numbered callouts on the page, explained in the guide's text: [number, JavaScript expression giving
    // the callout's position { x, y } in the window]; at(selector) is the element's bounding rectangle,
    // textEnd(selector) the right end of its own text
    async function mark(list) {
        await ev(`(() => {
            const at = (sel) => document.querySelector(sel).getBoundingClientRect();
            const textEnd = (sel) => { const r = document.createRange(); r.selectNodeContents(document.querySelector(sel)); return r.getBoundingClientRect(); };
            const st = document.createElement('style');
            st.id = 'guide-style';
            st.textContent = '.guide-mark{position:fixed;z-index:9999;width:26px;height:26px;border-radius:50%;'
                + 'background:#e8590c;color:#fff;font:bold 14px/26px sans-serif;text-align:center;'
                + 'box-shadow:0 0 0 3px rgba(255,255,255,.9),0 2px 6px rgba(0,0,0,.35)}';
            document.head.appendChild(st);
            for (const [n, where] of [${list.map(([n, w]) => `[${n}, ${w}]`).join(',')}]) {
                const m = document.createElement('div');
                m.className = 'guide-mark';
                m.textContent = n;
                m.style.left = where.x + 'px';
                m.style.top  = where.y + 'px';
                document.body.appendChild(m);
            }
        })()`);
    }
    const unmark = () => ev(`document.querySelectorAll('.guide-mark,#guide-style').forEach(e => e.remove())`);

    async function close() {
        if (problems.length) console.log('Page errors:\n' + problems.join('\n'));
        ws.close();
        chrome.kill();
        await sleep(500);
        rmSync(profile, { recursive: true, force: true });
    }

    await send('Runtime.enable');
    await send('Page.enable');
    await send('Emulation.setLocaleOverride', { locale: 'en-GB' });   // 24 hour times, day/month/year dates
    await resize(HEIGHT);
    return { send, ev, navigate, shot, mark, unmark, resize, close, problems };
}
