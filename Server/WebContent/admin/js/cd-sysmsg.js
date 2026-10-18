/**
 * The system chat messages in the admin interface (dashboard and system-messages.html): messages an admin
 * sent to every trading user (POST /api/admin/chat), shown with the system message colors.
 *
 * Usage:
 *   <script src="js/cd-api.js"></script>
 *   <script src="js/cd-sysmsg.js"></script>
 *
 *   const r = await cdSysMsg.load();                       // the latest 10, cdApi.request's result
 *   cdSysMsg.render(element, r.data.messages);             // newest first
 */
const cdSysMsg = (() => {

    const LIMIT = 10;

    function load(limit = LIMIT) {
        return cdApi.request('../api/admin/chat/system?limit=' + limit);
    }

    function render(el, messages) {
        if (!messages || messages.length === 0) {
            el.innerHTML = '<div class="sysmsg-empty">No system messages.</div>';
            return;
        }
        el.innerHTML = messages.slice().reverse().map(m => {
            const d = new Date(m.time);
            const when = d.toLocaleDateString('sv-SE') + ' ' + d.toLocaleTimeString('en-GB');
            return '<div class="sysmsg">'
                + '<div class="sysmsg-head"><span>' + esc(when) + '</span>'
                + '<span class="sysmsg-who">' + esc(m.user || '') + '</span>'
                + (m.tag ? '<span class="sysmsg-tag">' + esc(m.tag) + '</span>' : '') + '</div>'
                + '<div class="sysmsg-text">' + esc(m.text) + '</div>'
                + '</div>';
        }).join('');
    }

    function esc(s) {
        return (s == null ? '' : String(s)).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
    }

    return { load, render, LIMIT };
})();
