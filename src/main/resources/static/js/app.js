document.querySelectorAll(".flash").forEach((flash) => {
    setTimeout(() => {
        flash.style.opacity = "0";
        flash.style.transform = "translateY(-4px)";
    }, 5000);
});
