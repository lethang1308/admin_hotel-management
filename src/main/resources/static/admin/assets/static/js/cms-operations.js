(() => {
    const form = document.getElementById('booking-create');
    if (!form) return;
    const { request, money } = window.BookingDesk;
    const rooms = document.getElementById('booking-rooms');
    const template = document.getElementById('booking-room-template');
    const state = form.querySelector('[data-quote-state]');
    let serial = 0, timer, abort;
    function invalidate() {
        ++serial;
        abort?.abort();
        const quote = form.querySelector('#booking-quote');
        if (quote) { quote.hidden = true; quote.querySelector('[data-save-booking]').disabled = true; }
        clearTimeout(timer);
        timer = setTimeout(() => { if (form.querySelector('[name=typeId]')?.value) quoteNow(); }, 700);
    }
    async function quoteNow() {
        const inputs = rooms.querySelectorAll('input,select');
        if ([...inputs].some(field => !field.checkValidity())) return;
        const revision = ++serial;
        abort?.abort(); abort = new AbortController();
        state.textContent = '…';
        try {
            const quote = await request(form.dataset.quote, { method: 'POST', body: new URLSearchParams(new FormData(form)), signal: abort.signal });
            if (revision !== serial) return;
            form.querySelector('#booking-quote')?.remove();
            const node = document.getElementById('booking-quote-template').content.cloneNode(true);
            for (const [key, value] of Object.entries({ subtotal: quote.roomSubtotal, tax: quote.taxAmount, total: quote.totalAmount, deposit: quote.depositRequired })) {
                node.querySelector('[data-quote-' + key + ']').textContent = money(value);
            }
            const rows = document.createDocumentFragment();
            for (const night of quote.nights) {
                const row = document.createElement('tr');
                for (const value of [night.line, night.date, night.roomTypeName, money(Number(night.voucherDiscountAmount) + Number(night.lengthDiscountAmount)), money(night.totalAmount)]) {
                    const cell = document.createElement('td'); cell.textContent = value; row.append(cell);
                }
                rows.append(row);
            }
            node.querySelector('[data-quote-nights]').append(rows);
            node.querySelector('[data-save-booking]').value = quote.totalAmount;
            form.append(node);
            state.textContent = '';
        } catch (error) { if (revision === serial && error.name !== 'AbortError') state.textContent = error.message || form.dataset.error; }
    }
    form.addEventListener('input', invalidate);
    form.addEventListener('submit', event => {
        if (event.submitter?.hasAttribute('data-save-booking')) return;
        event.preventDefault(); clearTimeout(timer); quoteNow();
    });
    form.querySelector('[data-add-room]').addEventListener('click', () => {
        const rows = rooms.querySelectorAll('[data-room]');
        if (rows.length >= 12) return;
        const next = template.content.cloneNode(true);
        for (const field of next.querySelectorAll('input,select')) field.value = rows[rows.length - 1].querySelector('[name="' + field.name + '"]').value;
        rooms.append(next); invalidate();
    });
    rooms.addEventListener('click', event => {
        const button = event.target.closest('[data-remove-room]');
        if (!button || rooms.querySelectorAll('[data-room]').length <= 1) return;
        button.closest('[data-room]').remove(); invalidate();
    });
    const lookup = document.getElementById('booking-customer-search');
    if (lookup) {
        let lookupAbort;
        lookup.addEventListener('submit', async event => {
            event.preventDefault();
            lookupAbort?.abort(); lookupAbort = new AbortController();
            const target = lookup.querySelector('[data-customer-results]');
            const message = lookup.querySelector('[data-customer-state]');
            message.textContent = '…'; target.replaceChildren();
            try {
                const values = await request(lookup.action, { method: 'POST', body: new URLSearchParams(new FormData(lookup)), signal: lookupAbort.signal });
                message.textContent = values.length ? '' : lookup.dataset.empty;
                for (const customer of values) {
                    const button = document.createElement('button');
                    button.type = 'button'; button.className = 'btn btn-outline-primary text-start d-block w-100 mb-2';
                    button.textContent = customer.code + ' · ' + customer.name + ' · ' + (customer.phone || '') +
                        (customer.nationality ? ' · ' + customer.nationality : '') + (customer.document ? ' · ' + customer.document : '');
                    button.addEventListener('click', () => {
                        form.elements.customerId.value = customer.id; form.elements.name.value = customer.name;
                        form.elements.phone.value = customer.phone || ''; form.elements.email.value = customer.email || '';
                        lookup.elements.q.value = ''; target.replaceChildren(); message.textContent = customer.code + ' · ' + customer.name;
                        invalidate();
                    });
                    target.append(button);
                }
                if (lookup.elements.mode.value === 'DOCUMENT') lookup.elements.q.value = '';
            } catch (error) { if (error.name !== 'AbortError') message.textContent = error.message || lookup.dataset.error; }
        });
        lookup.querySelector('[data-new-customer]').addEventListener('click', () => {
            for (const name of ['customerId', 'name', 'phone', 'email']) form.elements[name].value = '';
            lookup.querySelector('[data-customer-results]').replaceChildren();
            lookup.querySelector('[data-customer-state]').textContent = '';
            form.elements.name.focus(); invalidate();
        });
    }
})();
