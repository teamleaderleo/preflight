import baseStyles from "./styles.css?raw";
import gameSettingsStyles from "./game-settings-layout.css?raw";

function installGameSettingsStyles() {
  const style = document.createElement("style");
  style.textContent = `${baseStyles}\n${gameSettingsStyles}`;
  document.head.append(style);
  return style;
}

test("Game memory stays vertical when the desktop sidebar narrows the settings card", () => {
  const style = installGameSettingsStyles();
  const grid = document.createElement("div");
  grid.className = "launch-settings-grid";
  const primary = document.createElement("section");
  primary.className = "launch-settings-card";
  const secondary = document.createElement("section");
  secondary.className = "launch-settings-card";
  const field = document.createElement("label");
  field.className = "setting-field";
  const label = document.createElement("span");
  label.textContent = "Game memory";
  const select = document.createElement("select");
  select.append(new Option("6 GB", "6144"));
  field.append(label, select);
  secondary.append(field);
  grid.append(primary, secondary);
  document.body.append(grid);

  expect(getComputedStyle(field).display).toBe("flex");
  expect(getComputedStyle(field).flexDirection).toBe("column");
  expect(getComputedStyle(field).alignItems).toBe("stretch");
  expect(getComputedStyle(field).gap).toBe("8px");
  expect(getComputedStyle(select).width).toBe("100%");

  grid.remove();
  style.remove();
});
