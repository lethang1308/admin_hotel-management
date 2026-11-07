(() => {
    const bell = document.getElementById('reception-notifications');
    if (!bell) return;
    const csrf = () => ({
        [document.querySelector('meta[name="csrf-header"]').content]:
            document.querySelector('meta[name="csrf-token"]').content
    });
    let stream;
    let lastVersion = null;
    let currentItems = [];
    let busy = false;
    let audio;
    const sound = bell.querySelector('[data-notification-sound]');
    sound.addEventListener('change', () => {
        if (sound.checked) {
            const Audio = window.AudioContext || window.webkitAudioContext;
            if (Audio) {
                audio = audio || new Audio();
                audio.resume().catch(() => {});
            }
        }
    });
    function toast() {
        const old = document.querySelector('.reception-toast');
        if (old) old.remove();
        const node = document.createElement('div');
        node.className = 'reception-toast';
        node.role = 'status';
        node.textContent = bell.dataset.new;
        document.body.append(node);
        setTimeout(() => node.remove(), 5000);
        if (sound.checked && audio && audio.state === 'running') {
            const oscillator = audio.createOscillator();
            const volume = audio.createGain();
            volume.gain.value = 0.025;
            oscillator.frequency.value = 660;
            oscillator.connect(volume).connect(audio.destination);
            oscillator.start();
            oscillator.stop(audio.currentTime + 0.15);
        }
    }
    async function feed() {
        if (busy || document.hidden) return;
        busy = true;
        try {
            const response = await fetch(bell.dataset.feed, { headers: { Accept: 'application/json' }, cache: 'no-store' });
            if (response.redirected || response.status === 401 || response.status === 403) {
                if (stream) stream.close();
                document.dispatchEvent(new CustomEvent('reception:offline'));
                return;
            }
            if (!response.ok) throw new Error();
            const data = await response.json();
            if (lastVersion !== null && data.version > lastVersion) toast();
            lastVersion = data.version;
            currentItems = data.items;
            const badge = bell.querySelector('[data-unread]');
            badge.hidden = data.unread === 0;
            badge.textContent = data.unread > 99 ? '99+' : data.unread;
            const list = bell.querySelector('[data-notices]');
            list.replaceChildren();
            if (!data.items.length) {
                const empty = document.createElement('p');
                empty.className = 'text-muted p-3 mb-0';
                empty.textContent = bell.dataset.empty;
                list.append(empty);
            }
            for (const item of data.items) {
                const link = document.createElement(bell.dataset.canBooking === 'true' ? 'a' : 'div');
                if (link.tagName === 'A') link.href = bell.dataset.bookingBase + item.bookingId;
                link.className = 'dropdown-item reception-notice' + (item.read ? '' : ' unread');
                const title = document.createElement('strong');
                title.textContent = (bell.dataset['event' + item.type.toLowerCase().replace(/(^|_)([a-z])/g, (_, a, b) => b.toUpperCase())]
                    || bell.dataset.title) + ' · ' + (item.code || '');
                const date = document.createElement('small');
                date.className = 'd-block text-muted';
                date.textContent = new Date(item.createdAt).toLocaleString(document.documentElement.lang);
                link.append(title, date);
                list.append(link);
            }
        } catch (_) {
            document.dispatchEvent(new CustomEvent('reception:offline'));
        } finally {
            busy = false;
        }
    }
    function connect() {
        if (stream || !window.EventSource) return;
        stream = new EventSource(bell.dataset.stream);
        stream.addEventListener('refresh', () => {
            feed();
            document.dispatchEvent(new CustomEvent('reception:refresh'));
        });
        stream.onerror = () => document.dispatchEvent(new CustomEvent('reception:offline'));
    }
    bell.querySelector('[data-mark-read]')?.addEventListener('click', async () => {
        const ids = currentItems.filter(item => !item.read).map(item => item.id);
        if (!ids.length || bell.dataset.canRead !== 'true') return;
        const body = new URLSearchParams();
        ids.forEach(id => body.append('ids', id));
        const response = await fetch(bell.dataset.read, { method: 'POST', headers: csrf(), body });
        if (response.ok) feed();
    });
    const timer = setInterval(feed, 45000);
    document.addEventListener('visibilitychange', () => { if (!document.hidden) feed(); });
    window.addEventListener('pagehide', () => { clearInterval(timer); if (stream) stream.close(); stream = null; });
    window.addEventListener('pageshow', connect);
    feed();
    connect();
})();
