document.addEventListener("DOMContentLoaded", () => {
    const confirmForms = document.querySelectorAll("form[data-confirm]");
    confirmForms.forEach((form) => {
        form.addEventListener("submit", (event) => {
            const message = form.getAttribute("data-confirm");
            if (message && !window.confirm(message)) {
                event.preventDefault();
            }
        });
    });
});
