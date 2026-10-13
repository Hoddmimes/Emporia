/**
 * Shared request helper for the Emporia admin pages. It fetches and reads the reply, so a failure
 * can be shown with its HTTP status and reply text, also when the reply is not JSON (such as a 401
 * "Not authenticated", or a 500 from a server error).
 *
 * Usage:
 *   <script src="js/cd-api.js"></script>
 *
 *   const r = await cdApi.request('../api/admin/markets');                 // GET
 *   const r = await cdApi.request(url, cdApi.json('POST', body));         // JSON body
 *   if (!r.ok) { showError('Failed to load markets: ' + r.error); return; }
 *   ... r.data ...
 *
 * cdApi.request never throws. It resolves to { ok, status, data, text, error }:
 *   ok     - HTTP 2xx and the reply is JSON
 *   status - the HTTP status (0 when there was no reply)
 *   data   - the parsed JSON reply, or null
 *   text   - the reply text
 *   error  - null when ok, otherwise "HTTP <status>: <the reply's error, or its text>"
 *            or "Connection error: <reason>"
 *
 * cdApi.showError(elementId, message) shows the message in an alert element (removes alert-hidden).
 */
const cdApi = (() => {

    const MAX_TEXT = 500;   // characters of a non-JSON reply shown in an error

    async function request(url, options) {
        let response;
        try {
            response = await fetch(url, options);
        } catch (e) {
            return { ok: false, status: 0, data: null, text: '',
                     error: 'Connection error: ' + (e.message || String(e)) };
        }
        const text = await response.text().catch(() => '');
        let data = null;
        try { data = JSON.parse(text); } catch (_) { /* not JSON */ }
        const ok = response.ok && data !== null;
        let error = null;
        if (!ok) {
            const reply = (data && (data.error || data.message)) || text.trim().slice(0, MAX_TEXT);
            error = `HTTP ${response.status}` + (reply ? ': ' + reply : '');
        }
        return { ok, status: response.status, data, text, error };
    }

    /** Request options for a JSON body. */
    function json(method, body) {
        return { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
    }

    function showError(elementId, message) {
        const el = document.getElementById(elementId);
        if (!el) return;
        el.textContent = message;
        el.classList.remove('alert-hidden');
    }

    return { request, json, showError };
})();
