(() => {
    const form = document.getElementById('booking-create');
    if (!form) return;
    const rooms = document.getElementById('booking-rooms');
    const template = document.getElementById('booking-room-template');
    const quote = document.getElementById('booking-quote');
    const invalidate = () => {
        if (quote) {
            quote.hidden = true;
            quote.querySelector('[data-save-booking]').disabled = true;
        }
    };
    form.addEventListener('input', invalidate);
    form.querySelector('[data-add-room]').addEventListener('click', () => {
        const rows = rooms.querySelectorAll('[data-room]');
        if (rows.length >= 12) return;
        const next = template.content.cloneNode(true);
        for (const field of next.querySelectorAll('input,select')) {
            field.value = rows[rows.length - 1].querySelector('[name="' + field.name + '"]').value;
        }
        rooms.append(next);
        invalidate();
    });
    rooms.addEventListener('click', event => {
        const button = event.target.closest('[data-remove-room]');
        if (!button || rooms.querySelectorAll('[data-room]').length <= 1) return;
        button.closest('[data-room]').remove();
        invalidate();
    });
    for (const button of document.querySelectorAll('[data-select-customer]')) {
        button.addEventListener('click', () => {
            form.elements.customerId.value = button.dataset.id;
            invalidate();
            form.elements.customerId.focus();
        });
    }
})();
