const dashboardSearch = document.getElementById("dashboardSearch");
const reportHistory = document.getElementById("reportHistory");

if (dashboardSearch && reportHistory) {
    dashboardSearch.addEventListener("input", () => {
        const query = dashboardSearch.value.trim().toLowerCase();
        reportHistory.querySelectorAll(".history-card").forEach((card) => {
            const title = card.querySelector("h3")?.textContent?.toLowerCase() || "";
            card.style.display = title.includes(query) ? "" : "none";
        });
    });
}
