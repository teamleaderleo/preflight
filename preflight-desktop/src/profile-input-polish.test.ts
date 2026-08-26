import profileInputStyles from "./profile-input-polish.css?raw";

describe("profile input polish", () => {
  it("uses the dark input surface for saved-profile search", () => {
    expect(profileInputStyles).toMatch(
      /:root\[data-theme="dark"\] \.profile-list-card > input\s*\{[^}]*background:\s*rgba\(255, 255, 255, 0\.04\);/s,
    );
    expect(profileInputStyles).not.toContain("rgba(255, 255, 255, 0.46)");
  });

  it("gives all profile text fields explicit semantic placeholders", () => {
    expect(profileInputStyles).toMatch(
      /:root\[data-theme\] :is\([\s\S]*\.profile-list-card > input,[\s\S]*\.profile-save-card input,[\s\S]*\.profile-rename-editor input[\s\S]*\)::placeholder\s*\{[^}]*color:\s*var\(--ink-soft\);[^}]*opacity:\s*1;/s,
    );
  });

  it("uses the full active accent for keyboard focus", () => {
    expect(profileInputStyles).toMatch(
      /:focus-visible\s*\{[^}]*border-color:\s*var\(--accent\);[^}]*outline:\s*3px solid var\(--accent\);[^}]*outline-offset:\s*3px;/s,
    );
    expect(profileInputStyles).not.toMatch(/focus-visible[\s\S]*rgba\([^)]*,\s*0\.(?:1[0-9]?|2[0-9]?|3[0-9]?)\)/);
  });
});
