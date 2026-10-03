import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, cpSync, rmSync } from "node:fs";
import { join, resolve } from "node:path";

const version = process.argv[2] || process.env.TRADINGVIEW_VERSION || "32.0.0";
const repo = process.env.TRADINGVIEW_REPO || "git@github.com:tradingview/charting_library.git";
const work = resolve(".tv-private");
const checkout = join(work, "charting_library");
const destination = resolve("public/charting_library");

console.log(`TradingView Advanced Charts: ${version}`);
console.log("This requires authorized access to TradingView's private GitHub repository.");

mkdirSync(work, { recursive: true });

try {
  if (existsSync(checkout)) rmSync(checkout, { recursive: true, force: true });

  execFileSync("git", [
    "clone",
    "--depth", "1",
    "--branch", version,
    repo,
    checkout
  ], { stdio: "inherit" });

  mkdirSync(resolve("public"), { recursive: true });
  if (existsSync(destination)) rmSync(destination, { recursive: true, force: true });
  cpSync(checkout, destination, { recursive: true });

  console.log(`Installed TradingView files at ${destination}`);
} finally {
  if (existsSync(work)) rmSync(work, { recursive: true, force: true });
}
