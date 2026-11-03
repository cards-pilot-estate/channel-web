import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The bundle is written into the Spring Boot jar (target/classes/static); the BFF serves it.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../target/classes/static',
    emptyOutDir: true,
    sourcemap: false,
  },
})
