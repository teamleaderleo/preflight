import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "./styles.css";
import "./release-readiness.css";
import "./game-settings-layout.css";
import "./homePresentation.css";
import "./layout-hierarchy.css";
import { initializeHomePresentation } from "./useHomePresentation";

initializeHomePresentation();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
