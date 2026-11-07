(() => {
    const groups = [...document.querySelectorAll('[data-nav-module]')];
    groups.forEach(group => {
        group.addEventListener('toggle', () => {
            if (!group.open) return;
            groups.forEach(other => { if (other !== group) other.open = false; });
        });
    });
})();
