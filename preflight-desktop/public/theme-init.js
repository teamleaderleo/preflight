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
    // Kept in step with PALETTES in useTheme.ts. A palette missing from this list paints as
    // blueprint for one frame and is then corrected by React, which is the exact flash this
    // script exists to remove.
    var palettes = ["blueprint", "hangar", "ultraviolet", "airglow", "phosphor"];
    var palette = window.localStorage.getItem("preflight.palette");
    document.documentElement.dataset.palette =
      palettes.indexOf(palette) >= 0 ? palette : "blueprint";
  } catch (error) {
    // Storage can be unavailable; a readable window matters more than the right theme.
    document.documentElement.dataset.theme = "light";
    document.documentElement.dataset.palette = "blueprint";
  }
})();
