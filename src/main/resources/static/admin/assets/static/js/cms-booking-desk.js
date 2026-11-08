(() => {
    const config = document.getElementById('booking-action-config');
    if (!config) return;
    const { request, money, line: metric } = window.BookingDesk;
    const data = config.dataset;
    const base = data.base + data.booking;
    const reasonText = key => document.querySelector('[data-reason="' + key + '"]')?.textContent || key || '';
    const statusText = key => document.querySelector('[data-status="' + key + '"]')?.textContent || key;
    const roomDialog = document.getElementById('booking-room-dialog');
    const assign = document.getElementById('booking-assign-form');
    const changeDialog = document.getElementById('booking-change-dialog');
    const change = document.getElementById('booking-change-form');
    const changeType = change.querySelector('[data-change-type]');
    const walkinDialog = document.getElementById('booking-walkin-dialog');
    const walkin = document.getElementById('booking-walkin-form');
    let current;
    let revision = 0;
    let roomRevision = 0;
    let assignRevision = 0, walkinRevision = 0;
    const candidatesUrl = (id, params = '') => base + '/rooms/' + id + '/candidates' + (params ? '?' + params : '');
    function candidateLabel(room) {
        return room.number + ' · ' + statusText(room.status)
            + (room.reason ? ' · ' + reasonText(room.reason) : '')
            + (room.conflictDate ? ' · ' + room.conflictDate : '')
            + (room.conflictCode ? ' · ' + room.conflictCode : room.blockId ? ' · #' + room.blockId : '');
    }
    async function showRooms(id) {
        const serial=++assignRevision;
        assign.reset(); assign.elements.line.value = id; assign.elements.room.value = '';
        assign.querySelector('[data-room-submit]').disabled = true;
        const options = roomDialog.querySelector('[data-room-options]');
        const state = roomDialog.querySelector('[data-room-state]');
        options.replaceChildren(); state.textContent = '…'; roomDialog.showModal();
        try {
            const rooms = await request(candidatesUrl(id));
            if(serial!==assignRevision || !roomDialog.open) return;
            state.textContent = data.ready;
            for (const room of rooms) {
                const button = document.createElement('button');
                button.type = 'button'; button.className = 'btn btn-outline-primary booking-room-choice';
                button.textContent = candidateLabel(room); button.disabled = !room.available;
                button.addEventListener('click', () => {
                    assign.elements.room.value = room.id;
                    options.querySelectorAll('button').forEach(b => b.classList.remove('active'));
                    button.classList.add('active');
                    assign.querySelector('[data-room-submit]').disabled = false;
                });
                options.append(button);
            }
        } catch (error) { state.textContent = error.message; }
    }
    function invalidate() {
        ++revision;
        change.elements.reviewedVersion.value = ''; change.elements.reviewedDifference.value = '';
        change.querySelector('[data-change-save]').disabled = true;
        changeDialog.querySelector('[data-change-result]').hidden = true;
        changeDialog.querySelector('[data-change-state]').textContent = '';
    }
    function todayCheckout() {
        const today = current?.status === 'CHECKED_IN' && change.elements.kind.value === 'SHORTEN' && change.elements.until.value === data.today;
        changeDialog.querySelector('[data-change-today]').hidden = !today;
        change.querySelector('[data-change-preview]').disabled = today;
        changeDialog.querySelector('[data-checkout-link]').href = data.desk + '?' + new URLSearchParams({
            booking: data.booking, line: current?.line || '', action: 'checkout'
        });
        return today;
    }
    async function loadChangeRooms() {
        const serial = ++roomRevision;
        const kind = change.elements.kind.value;
        changeType.disabled = kind === 'SHORTEN';
        if (kind === 'SHORTEN') changeType.value = current.type;
        const params = new URLSearchParams({ typeId: changeType.value, until: change.elements.until.value });
        const select = change.elements.physicalRoom;
        const initial = select.options[0].textContent;
        select.replaceChildren(new Option(initial, ''));
        select.required = kind === 'CHANGE_ROOM' || changeType.value !== current.type;
        select.disabled = kind === 'SHORTEN';
        if (kind === 'SHORTEN' || todayCheckout()) return;
        try {
            const rooms = await request(candidatesUrl(current.line, params));
            if (serial !== roomRevision) return;
            for (const room of rooms) {
                const option = new Option(candidateLabel(room), room.id);
                option.disabled = !room.available;
                select.append(option);
            }
        } catch (error) { if (serial === roomRevision) changeDialog.querySelector('[data-change-state]').textContent = error.message; }
    }
    async function openChange(button) {
        current = { ...button.dataset };
        current.line = button.dataset.line;
        change.reset(); change.elements.line.value = current.line; change.elements.from.value = current.from;
        change.elements.until.value = current.until; change.elements.kind.value = button.dataset.changeRoom;
        changeType.value = current.type;
        change.elements.until.min = current.status === 'CHECKED_IN' ? data.today : current.from;
        invalidate(); todayCheckout(); changeDialog.showModal();
        await loadChangeRooms();
    }
    change.addEventListener('input', event => { if (event.target !== change.elements.reason) { invalidate(); todayCheckout(); } });
    change.addEventListener('change', event => {
        if (event.target !== change.elements.physicalRoom && event.target !== change.elements.reason) loadChangeRooms();
    });
    change.querySelector('[data-change-preview]').addEventListener('click', async () => {
        if (todayCheckout() || !change.elements.until.reportValidity() || !change.elements.physicalRoom.reportValidity()) return;
        invalidate(); const serial = revision;
        const state = changeDialog.querySelector('[data-change-state]');
        state.textContent = '…';
        try {
            const params = new URLSearchParams({ kind: change.elements.kind.value, from: change.elements.from.value, until: change.elements.until.value });
            if (change.elements.physicalRoom.value) params.set('physicalRoom', change.elements.physicalRoom.value);
            const review = await request(base + '/rooms/' + current.line + '/change-preview?' + params);
            if (serial !== revision) return;
            const result = changeDialog.querySelector('[data-change-result]');
            result.replaceChildren(metric(data.pick, review.roomNumber), metric(data.difference, money(review.difference)),
                metric(data.fee, money(review.fee)), metric(data.refund, money(review.refundDue)));
            if (review.movingOccupied) {
                const notice = document.createElement('p'); notice.className = 'small mt-2 mb-0'; notice.textContent = data.move; result.append(notice);
            }
            result.hidden = false;
            change.elements.reviewedVersion.value = review.bookingVersion;
            change.elements.reviewedDifference.value = review.difference;
            change.querySelector('[data-change-save]').disabled = false;
            state.textContent = '';
        } catch (error) { if (serial === revision) state.textContent = error.message; }
    });
    async function showWalkIn(id) {
        const serial=++walkinRevision;
        walkin.reset(); walkin.hidden = true;
        const state = walkinDialog.querySelector('[data-walkin-state]');
        state.textContent = '…'; walkinDialog.showModal();
        try {
            const result = await request(base + '/rooms/' + id + '/walk-in-preview');
            if(serial!==walkinRevision || !walkinDialog.open) return;
            const p = result.preview;
            walkin.elements.line.value = id; walkin.elements.key.value = result.key;
            walkin.elements.amount.value = walkin.elements.amount.type === 'hidden' ? 0 : Math.max(0, Number(p.currentDebt));
            const summary = walkin.querySelector('[data-walkin-summary]');
            summary.replaceChildren(metric(data.total, money(p.currentDebt)), metric(data.deposit, money(p.depositShortfall)),
                metric(data.surcharge, money(p.surcharge?.total || 0)));
            const guests = walkin.querySelector('[data-walkin-guests]');
            guests.replaceChildren();
            for (let index = 0; index < p.adults + p.children; index++) {
                const adult = index < p.adults;
                const fragment = document.getElementById('booking-guest-template').content.cloneNode(true);
                fragment.querySelector('[data-guest-title]').textContent = (adult ? data.adult : data.child) + ' ' + (index + 1);
                fragment.querySelector('[name=guestType]').value = adult ? 'ADULT' : 'CHILD';
                fragment.querySelector('[name=documentNumber]').required = adult;
                fragment.querySelector('[name=dateOfBirth]').required = !adult;
                guests.append(fragment);
            }
            const notReady = p.roomStatus !== 'AVAILABLE';
            state.textContent = notReady ? data.notReady : '';
            walkin.querySelector('[data-walkin-submit]').disabled = notReady;
            walkin.hidden = false;
        } catch (error) { state.textContent = error.message; }
    }
    document.getElementById('operations-page').addEventListener('click', async event => {
        const assignButton = event.target.closest('[data-assign-room]');
        if (assignButton) { showRooms(assignButton.dataset.line); return; }
        const changeButton = event.target.closest('[data-change-room]');
        if (changeButton) { openChange(changeButton); return; }
        const walkinButton = event.target.closest('[data-fast-walkin]');
        if (walkinButton) { showWalkIn(walkinButton.dataset.line); return; }
        const noShow = event.target.closest('[data-preview-no-show]');
        if (noShow) {
            const target = noShow.parentElement.querySelector('[data-no-show-result]');
            target.textContent = '…';
            try {
                const review = await request(base + '/rooms/' + noShow.dataset.line + '/no-show-preview');
                target.replaceChildren(metric(data.fee, money(review.fee)), metric(data.refund, money(review.refundDue)));
            } catch (error) { target.textContent = error.message; }
        }
    });
    roomDialog.addEventListener('close', () => { ++assignRevision; });
    walkinDialog.addEventListener('close', () => {
        ++walkinRevision; walkin.reset(); walkin.querySelector('[data-walkin-guests]').replaceChildren();
    });
})();
