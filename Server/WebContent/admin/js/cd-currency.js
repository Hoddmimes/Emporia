/**
 * The Currency list of the instrument pages: the ISO 4217 currencies the server accepts
 * (GET /api/admin/currencies), the common ones first, then all of them by code, and "None" (empty).
 *
 * Usage:
 *   <script src="js/cd-api.js"></script>
 *   <script src="js/cd-currency.js"></script>
 *
 *   await cdCurrency.fill(document.getElementById('f-currency'), 'EUR');   // a <select>, the selected code
 */
const cdCurrency = (() => {

    const COMMON = ['EUR', 'USD', 'GBP', 'SEK', 'NOK', 'DKK', 'CHF'];

    async function fill(select, selected) {
        const r = await cdApi.request('../api/admin/currencies');
        const currencies = r.ok ? r.data : [];
        const option = (c) => '<option value="' + c.code + '">' + c.code + ' — ' + esc(c.name) + '</option>';
        const common = COMMON.map(code => currencies.find(c => c.code === code)).filter(c => c);
        let html = '<option value="">None</option>'
            + '<optgroup label="Common">' + common.map(option).join('') + '</optgroup>'
            + '<optgroup label="All currencies">' + currencies.map(option).join('') + '</optgroup>';
        // A stored code the list doesn't have (e.g. no longer known) stays selectable
        if (selected && !currencies.some(c => c.code === selected)) {
            html += '<option value="' + esc(selected) + '">' + esc(selected) + '</option>';
        }
        select.innerHTML = html;
        select.value = selected || '';
    }

    function esc(s) {
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    return { fill };
})();
