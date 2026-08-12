(function () {
  try {
    var saved = window.localStorage.getItem("preflight.theme");
    var preference =
      saved === "light" || saved === "dark" || saved === "system" ? saved : "system";
    var dark =
      preference === "dark" ||
      (preference === "system" &&
        typeof window.matchMedia === "function" &&
        window.matchMedia("(prefers-color-scheme: dark)").matches);
    document.documentElement.dataset.theme = dark ? "dark" : "light";
  } catch (error) {
    // Storage can be unavailable; a readable window matters more than the right theme.
    document.documentElement.dataset.theme = "light";
  }
})();
