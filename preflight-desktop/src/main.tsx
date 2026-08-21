import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "./styles.css";
import "./semantic-color.css";
import "./release-readiness.css";
import "./profile-input-polish.css";
import "./game-settings-layout.css";
import "./homePresentation.css";
import "./layout-hierarchy.css";
import "./hangar-instrumentation.css";
import "./hangar-focus-contrast.css";
import "./speedPage.css";
import { initializeHomePresentation } from "./useHomePresentation";

initializeHomePresentation();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
