(() => {
    const token = document.querySelector('meta[name="csrf-token"]');
    const header = document.querySelector('meta[name="csrf-header"]');
    async function request(url, options = {}) {
        const headers = { Accept: 'application/json', ...options.headers };
        if (options.method === 'POST') headers[header.content] = token.content;
        const response = await fetch(url, { ...options, headers, cache: 'no-store' });
        if (response.redirected && response.url.includes('/admin/login')) {
            window.location.assign(response.url);
            throw new Error();
        }
        const json = response.headers.get('content-type')?.includes('application/json') ? await response.json() : null;
        if (!response.ok || json === null) throw new Error(json?.message || document.getElementById('booking-availability')?.dataset.error || 'Request failed');
        return json;
    }
    const money = value => Number(value || 0).toLocaleString(document.documentElement.lang) + ' ₫';
    const line = (label, value) => {
        const node = document.createElement('p');
        node.className = 'mb-1';
        const caption = document.createElement('span');
        caption.className = 'text-muted me-2';
        caption.textContent = label;
        const content = document.createElement('strong');
        content.textContent = value;
        node.append(caption, content);
        return node;
    };
    window.BookingDesk = { request, money, line };
    document.addEventListener('click', event => {
        const close = event.target.closest('[data-booking-close]');
        if (close) close.closest('dialog').close();
        if (event.target.closest('[data-open-availability]')) document.getElementById('booking-availability')?.showModal();
    });
    const dialog = document.getElementById('booking-availability');
    if (!dialog) return;
    const form = document.getElementById('availability-form');
    const results = dialog.querySelector('[data-availability-results]');
    const state = dialog.querySelector('[data-availability-state]');
    const today = form.elements.from.value;
    let revision = 0;
    form.addEventListener('input', () => {
        ++revision;
        results.replaceChildren();
        state.textContent = '';
        if (form.elements.from.value !== today) form.elements.readyNow.checked = false;
    });
    form.addEventListener('submit', async event => {
        event.preventDefault();
        const serial = ++revision;
        const button = form.querySelector('button');
        button.disabled = true;
        results.replaceChildren();
        state.textContent = '…';
        try {
            const params = new URLSearchParams(new FormData(form));
            const data = await request(dialog.dataset.endpoint + '?' + params);
            if (serial !== revision) return;
            state.textContent = data.options.length ? '' : dialog.dataset.empty;
            for (const option of data.options) {
                const card = document.createElement('article');
                card.className = 'booking-availability-option';
                const title = document.createElement('h6');
                title.textContent = option.name;
                const badge = document.createElement('span');
                badge.className = 'badge ' + (option.canBook ? 'bg-light-success' : 'bg-light-danger');
                badge.textContent = dialog.dataset.available + ' ' + option.available;
                const amenities = document.createElement('p');
                amenities.className = 'small text-muted my-2';
                amenities.textContent = option.amenities.join(' · ');
                card.append(title, badge, amenities,
                    line(dialog.dataset.capacity, option.maxAdults + ' / ' + option.maxChildren + ' · ' + option.maxOccupancy),
                    line(dialog.dataset.continuous, option.continuous),
                    line(dialog.dataset.average, money(option.averageNight)),
                    line(dialog.dataset.total, money(option.total)),
                    line(dialog.dataset.deposit, money(option.deposit)));
                const details = document.createElement('details');
                const summary = document.createElement('summary');
                summary.textContent = dialog.dataset.day + ' / ' + dialog.dataset.remaining;
                details.append(summary);
                const table = document.createElement('table');
                table.className = 'table table-sm mt-2';
                const body = document.createElement('tbody');
                for (const night of option.nights) {
                    const row = document.createElement('tr');
                    for (const value of [night.date, night.remaining]) {
                        const cell = document.createElement('td');
                        cell.textContent = value;
                        row.append(cell);
                    }
                    body.append(row);
                }
                table.append(body); details.append(table); card.append(details);
                if (dialog.dataset.canBook === 'true' && option.canBook) {
                    const link = document.createElement('a');
                    link.className = 'btn btn-primary btn-sm mt-3';
                    link.textContent = dialog.dataset.book;
                    const chosen = new URLSearchParams(params);
                    chosen.delete('readyNow'); chosen.set('typeId', option.typeId);
                    link.href = dialog.dataset.create + '?' + chosen;
                    card.append(link);
                }
                results.append(card);
            }
        } catch (error) { if (serial === revision) state.textContent = error.message || dialog.dataset.error; }
        finally { button.disabled = false; }
    });
})();
