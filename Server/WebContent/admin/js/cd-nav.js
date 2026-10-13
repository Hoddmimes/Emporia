/**
 * Shared sidebar navigation for all Emporia admin pages.
 *
 * Usage:
 *   <nav class="cd-sidebar"></nav>
 *   <script src="js/cd-nav.js"></script>
 *   <script>cdNav.render('active-item-id');</script>
 *
 * Active item IDs: 'dashboard', 'list-firms', 'create-firm', 'list-users', 'create-user',
 *                  'list-markets', 'create-market',
 *                  'list-bulletin-markets', 'create-bulletin-market',
 *                  'list-instruments', 'create-instrument', 'load-instruments',
 *                  'list-authorizations', 'create-authorization', 'settings'
 */
const cdNav = (() => {

    const ITEMS = [
        { type: 'section', label: 'General' },
        { type: 'link', id: 'dashboard',      label: 'Dashboard',      href: 'admin.html' },
        { type: 'section', label: 'Firms' },
        { type: 'link', id: 'list-firms',     label: 'List Firms',     href: 'firms.html' },
        { type: 'link', id: 'create-firm',    label: 'Create Firm',    href: 'create-firm.html' },
        { type: 'section', label: 'Users' },
        { type: 'link', id: 'list-users',     label: 'List Users',     href: 'users.html' },
        { type: 'link', id: 'create-user',    label: 'Create User',    href: 'create-user.html' },
        { type: 'section', label: 'Markets' },
        { type: 'link', id: 'list-markets',      label: 'List Markets',      href: 'markets.html' },
        { type: 'link', id: 'create-market',     label: 'Create Market',     href: 'create-market.html' },
        { type: 'section', label: 'Bulletin Markets' },
        { type: 'link', id: 'list-bulletin-markets',  label: 'List Bulletin Markets',  href: 'bulletin-markets.html' },
        { type: 'link', id: 'create-bulletin-market', label: 'Create Bulletin Market', href: 'create-bulletin-market.html' },
        { type: 'section', label: 'Instruments' },
        { type: 'link', id: 'list-instruments',  label: 'List Instruments',  href: 'instruments.html' },
        { type: 'link', id: 'create-instrument', label: 'Create Instrument', href: 'create-instrument.html' },
        { type: 'link', id: 'load-instruments',  label: 'Load Instruments',  href: 'load-instruments.html' },
        { type: 'section', label: 'Authorizations' },
        { type: 'link', id: 'list-authorizations',  label: 'List Authorizations',  href: 'authorizations.html' },
        { type: 'link', id: 'create-authorization', label: 'Create Authorization', href: 'create-authorization.html' },
        { type: 'section', label: 'System' },
        { type: 'link', id: 'change-password', label: 'Change Password', href: 'change-password.html' },
        { type: 'link', id: 'settings',        label: 'Settings',        href: '#' },
    ];

    function render(activeId) {
        const sidebar = document.querySelector('.cd-sidebar');
        if (!sidebar) return;
        sidebar.innerHTML = ITEMS.map(item => {
            if (item.type === 'section') {
                return `<div class="nav-section">${item.label}</div>`;
            }
            const cls = 'nav-item' + (item.id === activeId ? ' active' : '');
            return `<a class="${cls}" href="${item.href}">${item.label}</a>`;
        }).join('');
    }

    return { render };
})();
