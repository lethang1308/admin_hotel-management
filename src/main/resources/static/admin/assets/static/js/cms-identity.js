"use strict";
document.querySelectorAll("[data-generate-password]").forEach(button => {
    button.addEventListener("click", () => {
        const form = button.closest("form");
        const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";
        let password = "Aa7!";
        const limit = 256 - (256 % alphabet.length);
        while (password.length < 20) {
            const bytes = crypto.getRandomValues(new Uint8Array(32));
            for (const byte of bytes) {
                if (byte < limit && password.length < 20) password += alphabet[byte % alphabet.length];
            }
        }
        form.querySelector('[name="newPassword"]').value = password;
        form.querySelector('[name="confirmPassword"]').value = password;
        form.querySelector("[data-generated-password]").textContent = password;
    });
});
