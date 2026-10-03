import { existsSync } from "node:fs";
import { resolve } from "node:path";

const p = resolve("public/charting_library/charting_library.standalone.js");
if (!existsSync(p)) {
  console.error("TradingView Advanced Charts is not installed.");
  console.error("Run: npm run tv:install");
  process.exit(1);
}
console.log("TradingView Advanced Charts installation found.");
