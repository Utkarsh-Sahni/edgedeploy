import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  // Standalone output gives a minimal server bundle for the container image built in a later phase.
  output: "standalone",
};

export default nextConfig;
