(() => {
    const root = document.getElementById('receptionist-page');
    if (!root) return;
    const labels = document.getElementById('desk-labels').dataset;
    const locale = document.documentElement.lang || 'vi';
    const money = value => Number(value || 0).toLocaleString(locale) + ' ₫';
    const shiftDialog = document.getElementById('shift-dialog');
    const shiftForm = document.getElementById('shift-form');
    const reasonDialog = document.getElementById('reason-dialog');
    const reasonForm = document.getElementById('reason-form');
    let activeTab = 'arrivals';
    let refreshing = false;
    let activeAction;
    let activeBooking;
    let activeLine;
    const csrf = () => ({
        [document.querySelector('meta[name="csrf-header"]').content]:
            document.querySelector('meta[name="csrf-token"]').content
    });
    async function request(url, options = {}) {
        const response = await fetch(url, { ...options, cache: 'no-store' });
        if (response.redirected && response.url.includes('/admin/login')) {
            window.location.assign(response.url);
            throw new Error(root.dataset.loadError);
        }
        if (!response.ok) {
            let message = root.dataset.loadError;
            if (response.headers.get('content-type')?.includes('application/json')) {
                const error = await response.json();
                message = error.message || message;
            }
            throw new Error(message);
        }
        return response;
    }
    function displayTimes() {
        const zone = document.getElementById('desk-live').dataset.timezone;
        root.querySelectorAll('[data-local-time]').forEach(node => {
            node.textContent = new Date(node.dateTime).toLocaleString(locale, {
                timeZone: zone, day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit'
            });
        });
        root.querySelectorAll('[data-countdown]').forEach(node => {
            const seconds = Math.max(0, Math.ceil((new Date(node.dataset.until).getTime() - Date.now()) / 1000));
            node.textContent = seconds ? Math.floor(seconds / 60) + ':' + String(seconds % 60).padStart(2, '0') : root.dataset.expired;
        });
    }
    function selectTab() {
        root.querySelectorAll('[data-queue]').forEach(node => { node.hidden = node.dataset.queue !== activeTab; });
        root.querySelectorAll('[data-queue-tab]').forEach(button => {
            const active = button.dataset.queueTab === activeTab;
            button.classList.toggle('btn-primary', active);
            button.classList.toggle('btn-light', !active);
            button.setAttribute('aria-pressed', active);
        });
    }
    async function refresh() {
        if (refreshing || document.hidden) return;
        refreshing = true;
        try {
            const response = await request(root.dataset.fragment);
            const parsed = new DOMParser().parseFromString(await response.text(), 'text/html');
            const next = parsed.getElementById('desk-live');
            if (!next) throw new Error();
            document.getElementById('desk-live').replaceWith(next);
            selectTab();
            displayTimes();
            root.querySelector('[data-refresh-error]').hidden = true;
        } catch (_) {
            root.querySelector('[data-refresh-error]').hidden = false;
        } finally {
            refreshing = false;
        }
    }
    root.addEventListener('click', async event => {
        const close = event.target.closest('[data-close-dialog]');
        if (close) { close.closest('dialog').close(); return; }
        const tab = event.target.closest('[data-queue-tab]');
        if (tab) { activeTab = tab.dataset.queueTab; selectTab(); return; }
        if (event.target.closest('[data-refresh-desk]')) { refresh(); return; }
        const shift = event.target.closest('[data-shift-action]');
        if (shift) {
            shiftForm.reset();
            shiftForm.action = shift.dataset.shiftAction === 'open' ? shiftDialog.dataset.open : shiftDialog.dataset.close;
            shiftForm.elements.cash.value = shift.dataset.expected || 0;
            shiftDialog.querySelector('[data-shift-expected]').textContent = shift.dataset.expected
                ? root.dataset.debt + ' · ' + money(shift.dataset.expected) : '';
            shiftDialog.showModal();
            return;
        }
        const button = event.target.closest('[data-desk-action]');
        if (!button) return;
        activeAction = button.dataset.deskAction;
        activeBooking = button.dataset.booking;
        activeLine = button.dataset.line;
        if (activeAction === 'checkin' || activeAction === 'checkout') {
            return; // The shared stay workflow handles these actions.
        } else {
            reasonForm.reset();
            reasonDialog.querySelector('[data-reason-title]').textContent = activeAction === 'no-show' ? labels.noShow : labels[activeAction];
            reasonDialog.querySelector('[data-reason-code]').textContent = button.dataset.code;
            reasonForm.action = activeAction === 'no-show' ? root.dataset.noShow + activeBooking : root.dataset.actionBase + activeAction + '/' + activeBooking;
            reasonForm.elements.line.value = activeLine || '';
            reasonDialog.showModal();
        }
    });
    document.addEventListener('reception:refresh', refresh);
    document.addEventListener('reception:offline', () => { root.querySelector('[data-refresh-error]').hidden = false; });
    document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
    const refreshTimer = setInterval(refresh, 30000);
    const clockTimer = setInterval(displayTimes, 1000);
    window.addEventListener('pagehide', () => { clearInterval(refreshTimer); clearInterval(clockTimer); });
    displayTimes();
    selectTab();
})();
