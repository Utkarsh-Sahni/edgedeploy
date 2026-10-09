import type { Config } from "tailwindcss";

const config: Config = {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}"],
  theme: {
    extend: {
      fontFamily: {
        sans: ["var(--font-geist)", "ui-sans-serif", "system-ui"],
        mono: ["ui-monospace", "SFMono-Regular", "Menlo", "monospace"],
      },
      colors: {
        ink: {
          950: "#07080c",
          900: "#0c1018",
          800: "#141a24",
          700: "#1c2433",
        },
        accent: {
          DEFAULT: "#5eead4",
          dim: "#2dd4bf",
        },
      },
    },
  },
  plugins: [],
};

export default config;
