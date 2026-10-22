import type { NextConfig } from "next";

// STATIC_EXPORT=1 -> Cloudflare Pages static export (APIs served by out/_worker.js)
const isStatic = process.env.STATIC_EXPORT === "1";

const nextConfig: NextConfig = {
  ...(isStatic ? { output: "export" as const } : { output: "standalone" as const }),
  typescript: {
    ignoreBuildErrors: true,
  },
  reactStrictMode: false,
  images: { unoptimized: true },
  async rewrites() {
    if (isStatic) return [];
    return [
      // /player or /player/ -> standalone player demo (public/player/index.html)
      { source: "/player", destination: "/player/index.html" },
    ];
  },
};

export default nextConfig;
