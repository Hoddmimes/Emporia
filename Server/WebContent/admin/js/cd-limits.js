/**
 * The order limits fields of the market and instrument pages: Max Order Quantity, Max Order Value and
 * Price Band (%), decimals in the page, scaled longs (x 100000) in the admin API; 0 or empty = none
 * (on an instrument: the market's limit applies). The fields' ids are <prefix>-maxOrderQuantity,
 * <prefix>-maxOrderValue and <prefix>-priceBand.
 *
 * Usage:
 *   <script src="js/cd-limits.js"></script>
 *
 *   Object.assign(body, cdLimits.read('f'));      // adds maxOrderQuantity, maxOrderValue, priceBand
 *   cdLimits.fill('d', market);                   // from a market's or instrument's JSON
 *   cdLimits.key('d')                             // the fields' values as one string, for change checks
 */
const cdLimits = (() => {

    const SCALE  = 100000;
    const FIELDS = ['maxOrderQuantity', 'maxOrderValue', 'priceBand'];

    const el = (prefix, field) => document.getElementById(prefix + '-' + field);

    function read(prefix) {
        const out = {};
        FIELDS.forEach(f => {
            const v = parseFloat(el(prefix, f).value);
            out[f] = isNaN(v) ? 0 : Math.round(v * SCALE);
        });
        return out;
    }

    function fill(prefix, json) {
        FIELDS.forEach(f => {
            const v = json[f] || 0;
            el(prefix, f).value = v > 0 ? String(v / SCALE) : '';
        });
    }

    function key(prefix) {
        return FIELDS.map(f => el(prefix, f).value.trim()).join('|');
    }

    return { read, fill, key };
})();
