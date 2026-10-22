/* Manually invoked, local-only session error check. No requests, storage, cookies or page export. */
(function () {
    "use strict";
    const profiles = {
        "youtube.com": "YouTube", "www.youtube.com": "YouTube",
        "gemini.google.com": "Gemini", "notebook.google.com": "Notebook",
        "notebooklm.google.com": "Notebook", "antigravity.google": "Antigravity",
        "www.antigravity.google": "Antigravity",
        "antigravity.google.com": "Antigravity", "chatgpt.com": "ChatGPT / dots"
    };
    const serviceName = "(?:this service|the service|gemini|youtube|notebook|notebooklm|gemini notebook|antigravity|chatgpt)";
    const englishError = new RegExp("^(?:" + serviceName + "\\s+)?(?:is\\s+)?(?:currently\\s+)?" +
        "(?:not available|not supported|unavailable|isn't available|isn't supported) in your (?:country|region)" +
        "(?: yet| at this time| right now)?[.!]?$");
    const russianError = new RegExp("^(?:(?:gemini|youtube|notebook|gemini notebook|antigravity|chatgpt|" +
        "сервис|этот сервис)\\s+)?(?:пока\\s+)?(?:недоступен|недоступно|не поддерживается) в " +
        "(?:вашей стране|вашем регионе)[.!]?$");
    const challenges = ["verify you are human", "unusual traffic", "checking your browser",
        "подтвердите, что вы человек", "подозрительный трафик"];
    const normalize = text => text.toLowerCase().replace(/’/g, "'").replace(/\s+/g, " ").trim();
    const directError = text => englishError.test(text) || russianError.test(text);
    function classifyVisible(text, headings, complete = true) {
        const body = normalize(text);
        const titles = headings.map(normalize);
        if (!complete) return "UNKNOWN";
        if (challenges.some(phrase => body.includes(phrase))) return "CHALLENGE";
        if (body.length <= 4096 && titles.some(directError))
            return "REGION_RESTRICTED";
        if (titles.some(title => ["sign in", "sign in to continue", "войти", "войдите, чтобы продолжить"]
            .includes(title))) return "AUTH_REQUIRED";
        if (titles.some(title => title.includes("too many requests") || title.includes("слишком много запросов")))
            return "RATE_LIMITED";
        return "UNKNOWN";
    }
    function run(doc, win) {
        const service = profiles[win.location.hostname];
        if (win.location.protocol !== "https:" || !service) return {source: "browser_session", status: "UNKNOWN"};
        const raw = doc.body ? doc.body.innerText : "";
        const visible = node => {
            if (!node.getClientRects().length) return false;
            for (let current = node; current; current = current.parentElement) {
                const style = win.getComputedStyle(current);
                if (style.display === "none" || style.visibility === "hidden" || style.opacity === "0") return false;
            }
            return !node.closest("blockquote,q,pre,code,article,[role=article],[contenteditable=true]," +
                "[data-message-author-role],[data-message-id]");
        };
        const headings = Array.from(doc.querySelectorAll("h1,[role=alert]"))
            .slice(0, 200).filter(visible).map(node => node.innerText.slice(0, 4096));
        return {source: "browser_session", service,
            status: classifyVisible(raw.slice(0, 32768), headings, raw.length <= 32768)};
    }
    if (typeof module !== "undefined" && module.exports) {
        module.exports = {classifyVisible, run};
    } else {
        const result = run(document, window);
        const ru = navigator.language.toLowerCase().startsWith("ru");
        const labels = ru ? {
            REGION_RESTRICTED: "На странице явно указано региональное ограничение.",
            CHALLENGE: "Требуется CAPTCHA или проверка браузера.", AUTH_REQUIRED: "Требуется вход.",
            RATE_LIMITED: "Сервис ограничил частоту запросов.", UNKNOWN: "Доступ к функциям не подтверждён."
        } : {
            REGION_RESTRICTED: "The page explicitly reports a regional restriction.",
            CHALLENGE: "CAPTCHA or browser verification required.", AUTH_REQUIRED: "Sign-in required.",
            RATE_LIMITED: "The service is rate limited.", UNKNOWN: "Feature access is unverified."
        };
        window.alert("TgWatch · " + (result.service || "browser") + "\n" + labels[result.status] + "\n\n" +
            (ru ? "Одна проверка видимого текста этой вкладки. Причина может зависеть от аккаунта. " +
                "Воспроизведение видео, генерация и работа dots/IDE требуют ручного действия в сервисе." :
                "One visible-text check of this tab. The cause may depend on the account. " +
                "Playback, generation and dots/IDE readiness require a manual action in the service."));
    }
}());
