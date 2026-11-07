(() => {
    const nodes = document.querySelectorAll('[data-booking-countdown]');
    function tick() {
        for (const node of nodes) {
            const seconds = Math.max(0, Math.ceil((new Date(node.dataset.until).getTime() - Date.now()) / 1000));
            node.textContent = Math.floor(seconds / 60) + ':' + String(seconds % 60).padStart(2, '0');
            node.classList.toggle('text-danger', seconds === 0);
        }
    }
    tick();
    const timer = setInterval(tick, 1000);
    window.addEventListener('pagehide', () => clearInterval(timer));
})();
