import tailwindcss from "@tailwindcss/vite"
import { defineConfig } from "vitest/config"
import tsconfigPaths from "vite-tsconfig-paths"

export default defineConfig({
  plugins: [tailwindcss(), tsconfigPaths()],
  test: {
    environment: "jsdom",
    setupFiles: "./app/test/setup.ts",
    css: true,
  },
})
