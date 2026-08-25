import "@testing-library/jest-dom/vitest";
import { configure } from "@testing-library/react";

// App integration tests cross the same mocked native-read chain as the renderer. A one-second
// Testing Library polling ceiling makes worker scheduling look like a product failure, while the
// individual Vitest timeout remains the actual hang guard.
configure({ asyncUtilTimeout: 3_000 });
