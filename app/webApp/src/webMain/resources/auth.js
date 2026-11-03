// Google credentials stay in memory only. Bohoman refresh tokens are HttpOnly cookies.
(() => {
    let token = "";
    let dialog = null;
    let initialized = false;
    let previouslyFocused = null;
    const auth = window.bohomanAuth = {
        error: "",
        begin() {
            auth.close();
            token = "";
            auth.error = "";
            if (!window.bohomanConfig.googleClientId) {
                auth.error = "Google sign-in is not configured for this website.";
                return;
            }
            if (!window.google?.accounts?.id) {
                auth.error = "Google sign-in could not load. Check your connection and try again.";
                return;
            }
            if (!initialized) {
                google.accounts.id.initialize({
                    client_id: window.bohomanConfig.googleClientId,
                    auto_select: false,
                    callback(response) {
                        token = response.credential || "";
                        if (!token) auth.error = "Google did not return a sign-in credential.";
                    }
                });
                initialized = true;
            }
            previouslyFocused = document.activeElement;
            dialog = document.createElement("dialog");
            dialog.setAttribute("aria-label", "Sign in to Bohoman");
            dialog.style.cssText = "border:0;border-radius:24px;padding:32px;max-width:90vw;font:16px system-ui;box-shadow:0 12px 60px #0004";
            const title = document.createElement("h2");
            title.textContent = "Welcome to Bohoman";
            const button = document.createElement("div");
            const cancel = document.createElement("button");
            cancel.textContent = "Cancel";
            cancel.style.cssText = "display:block;margin:24px auto 0;padding:8px 20px";
            cancel.onclick = () => { auth.error = "Sign-in cancelled."; auth.close(); };
            dialog.addEventListener("cancel", () => { auth.error = "Sign-in cancelled."; });
            dialog.append(title, button, cancel);
            document.body.append(dialog);
            dialog.showModal();
            google.accounts.id.renderButton(button, {type: "standard", theme: "outline", size: "large", text: "continue_with"});
        },
        takeToken() { const value = token; token = ""; return value; },
        close() {
            dialog?.close();
            dialog?.remove();
            dialog = null;
            previouslyFocused?.focus();
            previouslyFocused = null;
            token = "";
        }
    };
})();
