/**
 * Shared session utilities for all Emporia admin pages.
 *
 * Usage — include at the bottom of every admin page body:
 *   <script src="/admin/js/cd-session.js"></script>
 *   <script>cdSession.guard('user-info');</script>
 *
 * cdSession.guard(userInfoElementId)
 *   Verifies the server session on page load.
 *   If not authenticated, redirects to /admin/login.html.
 *   Populates the element with the logged-in username.
 *
 * cdSession.logout()
 *   Calls POST /api/admin/logout then redirects to /admin/login.html.
 */
const cdSession = (() => {

    async function guard(userInfoId) {
        try {
            const response = await fetch('/api/admin/session');
            if (!response.ok) {
                window.location.href = '/admin/login.html';
                return;
            }
            const data = await response.json();
            if (userInfoId) {
                const el = document.getElementById(userInfoId);
                if (el) el.textContent = data.username;
            }
        } catch (e) {
            window.location.href = '/admin/login.html';
        }
    }

    async function logout() {
        try {
            await fetch('/api/admin/logout', { method: 'POST' });
        } finally {
            window.location.href = '/admin/login.html';
        }
    }

    return { guard, logout };
})();
