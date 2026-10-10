/**
 * tr-session.js — session management for Carthage TR trading interface.
 *
 * Usage:
 *   trSession.guard('user-info')  — redirect to /login.html if not logged in;
 *                                   otherwise populate element with "User @ Firm"
 *   trSession.logout()            — POST /api/logout then redirect to /login.html
 */
const trSession = (() => {

    async function guard(elementId) {
        try {
            const response = await fetch('/api/session');
            if (!response.ok) {
                window.location.href = '/login.html';
                return;
            }
            const data = await response.json();
            if (!data.authenticated) {
                window.location.href = '/login.html';
                return;
            }
            const el = document.getElementById(elementId);
            if (el) {
                el.textContent = data.userId + ' \u2014 ' + data.firm;
            }
            // Expose session data for the page
            trSession._session = data;
        } catch (e) {
            window.location.href = '/login.html';
        }
    }

    async function logout() {
        try {
            await fetch('/api/logout', { method: 'POST' });
        } catch (_) {}
        window.location.href = '/login.html';
    }

    return { guard, logout, _session: null };
})();
