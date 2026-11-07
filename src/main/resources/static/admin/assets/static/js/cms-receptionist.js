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
    const stayDialog = document.getElementById('stay-dialog');
    const stayForm = document.getElementById('stay-form');
    const serviceForm = document.getElementById('desk-service-form');
    let activeTab = 'arrivals';
    let refreshing = false;
    let activeAction;
    let activeBooking;
    let activeLine;
    let previewSerial = 0;
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
            stayForm.reset();
            stayDialog.querySelector('[data-stay-title]').textContent = labels[activeAction];
            stayDialog.querySelector('[data-stay-code]').textContent = button.dataset.code;
            stayDialog.showModal();
            await loadPreview();
        } else {
            reasonForm.reset();
            reasonDialog.querySelector('[data-reason-title]').textContent = activeAction === 'no-show' ? labels.noShow : labels[activeAction];
            reasonDialog.querySelector('[data-reason-code]').textContent = button.dataset.code;
            reasonForm.action = activeAction === 'no-show' ? root.dataset.noShow + activeBooking : root.dataset.actionBase + activeAction + '/' + activeBooking;
            reasonForm.elements.line.value = activeLine || '';
            reasonDialog.showModal();
        }
    });
    function summaryRow(label, value) {
        const node = document.createElement('div');
        const title = document.createElement('small');
        title.className = 'd-block text-muted';
        title.textContent = label;
        const total = document.createElement('strong');
        total.textContent = money(value);
        node.append(title, total);
        return node;
    }
    async function loadPreview() {
        const serial = ++previewSerial;
        const error = stayDialog.querySelector('[data-stay-error]');
        const loading = stayDialog.querySelector('[data-stay-loading]');
        const ready = stayDialog.querySelector('[data-stay-ready]');
        error.hidden = true; loading.hidden = false; ready.hidden = true;
        try {
            const params = new URLSearchParams({ booking: activeBooking, action: activeAction });
            const response = await request(root.dataset.preview + activeLine + '/preview?' + params, { headers: { Accept: 'application/json' } });
            const data = await response.json();
            if (serial !== previewSerial || !stayDialog.open) return;
            const p = data.preview;
            stayForm.action = root.dataset[activeAction];
            stayForm.elements.booking.value = activeBooking;
            stayForm.elements.line.value = activeLine;
            stayForm.elements.key.value = data.key;
            stayForm.elements.expectedDebt.value = p.projectedDebt;
            stayForm.elements.amount.value = root.dataset.canCollect === 'true'
                ? Math.max(0, activeAction === 'checkin' ? p.depositShortfall : p.projectedDebt) : 0;
            const summary = stayDialog.querySelector('[data-stay-summary]');
            summary.replaceChildren(
                summaryRow(root.dataset.currentDebt, p.currentDebt),
                summaryRow(root.dataset.debt, p.projectedDebt),
                summaryRow(root.dataset.deposit, p.depositShortfall),
                summaryRow(root.dataset.surcharge, p.surcharge ? p.surcharge.total : 0)
            );
            if (Number(p.credit)) summary.append(summaryRow(root.dataset.credit, p.credit));
            if (Number(p.changeFee)) summary.append(summaryRow(root.dataset.fee, p.changeFee));
            const rows = stayDialog.querySelector('[data-stay-charges]');
            rows.replaceChildren();
            data.charges.forEach(charge => {
                const row = document.createElement('tr');
                const description = document.createElement('td');
                description.textContent = charge.description;
                const value = document.createElement('td');
                value.className = 'text-end text-nowrap';
                value.textContent = money(charge.amount);
                row.append(description, value); rows.append(row);
            });
            const guests = stayForm.querySelector('[data-guests]');
            // A service refresh during checkout never replaces entered identity documents.
            guests.replaceChildren();
            if (activeAction === 'checkin') {
                for (let index = 0; index < p.adults + p.children; index++) {
                    const adult = index < p.adults;
                    const fields = document.getElementById('guest-template').content.cloneNode(true);
                    fields.querySelector('[data-guest-title]').textContent = (adult ? labels.adult : labels.child) + ' ' + (index + 1);
                    fields.querySelector('[name=guestType]').value = adult ? 'ADULT' : 'CHILD';
                    fields.querySelector('[name=documentNumber]').required = adult;
                    guests.append(fields);
                }
            }
            stayDialog.querySelector('[data-stay-refund]').hidden = Number(p.projectedDebt) >= 0;
            const notReady = activeAction === 'checkin' && p.roomStatus !== 'AVAILABLE';
            stayDialog.querySelector('[data-submit-stay]').disabled = notReady;
            if (notReady) { error.textContent = root.dataset.readyError; error.hidden = false; }
            if (serviceForm) {
                serviceForm.hidden = activeAction !== 'checkout' || !data.services?.length;
                serviceForm.elements.booking.value = activeBooking;
                serviceForm.elements.line.value = activeLine;
                serviceForm.elements.key.value = crypto.randomUUID().replaceAll('-', '').slice(0, 24).toUpperCase();
                const options = data.services?.map(item => new Option(item.name + ' · ' + money(item.unit_price) + '/' + item.unit, item.id)) || [];
                serviceForm.elements.service.replaceChildren(...options);
            }
            ready.hidden = false;
        } catch (failure) {
            error.textContent = failure.message;
            error.hidden = false;
        } finally {
            if (serial === previewSerial) loading.hidden = true;
        }
    }
    serviceForm?.addEventListener('submit', async event => {
        event.preventDefault();
        const button = serviceForm.querySelector('button');
        button.disabled = true;
        try {
            await request(root.dataset.service, { method: 'POST', headers: { ...csrf(), Accept: 'application/json' }, body: new URLSearchParams(new FormData(serviceForm)) });
            await loadPreview();
            refresh();
        } catch (failure) {
            const error = stayDialog.querySelector('[data-stay-error]');
            error.textContent = failure.message;
            error.hidden = false;
        } finally { button.disabled = false; }
    });
    for (const dialog of root.querySelectorAll('dialog')) {
        dialog.addEventListener('close', () => { if (dialog === stayDialog) { ++previewSerial; stayForm.reset(); stayForm.querySelector('[data-guests]').replaceChildren(); } });
    }
    document.addEventListener('reception:refresh', refresh);
    document.addEventListener('reception:offline', () => { root.querySelector('[data-refresh-error]').hidden = false; });
    document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
    const refreshTimer = setInterval(refresh, 30000);
    const clockTimer = setInterval(displayTimes, 1000);
    window.addEventListener('pagehide', () => { clearInterval(refreshTimer); clearInterval(clockTimer); });
    displayTimes();
    selectTab();
    const shortcut = new URLSearchParams(window.location.search);
    const requestedAction=shortcut.get('action'), requestedBooking=shortcut.get('booking'), requestedLine=shortcut.get('line');
    if (['checkin','checkout'].includes(requestedAction) && /^[0-9]+$/.test(requestedBooking || '') && /^[0-9]+$/.test(requestedLine || '')) {
        activeAction=requestedAction; activeBooking=requestedBooking; activeLine=requestedLine;
        stayForm.reset();
        stayDialog.querySelector('[data-stay-title]').textContent=labels[activeAction];
        stayDialog.querySelector('[data-stay-code]').textContent='#'+activeBooking;
        stayDialog.showModal();
        loadPreview();
    }
})();
