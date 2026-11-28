import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const kotlinPath = resolve(root, "mobile/app/src/main/java/com/meshnet/app/MainActivity.kt");
const workspacePath = resolve(root, "src/components/PhoneWorkspace.tsx");
const appPath = resolve(root, "src/App.tsx");

const expectedBridgeMethods = [
  "cancelFileTransfer",
  "connectWallet",
  "connectWifiDirect",
  "copyOfflineWalletAddress",
  "createWifiDirectGroup",
  "disconnectWallet",
  "discoverWifiDirect",
  "endLiveVoice",
  "forgetGroupCredentials",
  "getOfflineWalletAddress",
  "getRuntimeStatus",
  "openOfflinePortal",
  "playVoiceNote",
  "removeOfflineContent",
  "requestOfflineContent",
  "resumeWifiAware",
  "retryPendingOfflinePayments",
  "saveReceivedFile",
  "selectFile",
  "selectOfflineContent",
  "sendDirectMessage",
  "sendMessage",
  "sendOfflinePayment",
  "sendSolanaDevnetTransfer",
  "setOnlineIntegrationsEnabled",
  "startLiveVoice",
  "startMesh",
  "startOfflineNetwork",
  "stopMesh",
  "stopOfflineNetwork",
  "toggleVoiceNote"
];
const expectedUiCalls = [
  "cancelFileTransfer",
  "connectWallet",
  "connectWifiDirect",
  "copyOfflineWalletAddress",
  "createWifiDirectGroup",
  "disconnectWallet",
  "discoverWifiDirect",
  "endLiveVoice",
  "forgetGroupCredentials",
  "getRuntimeStatus",
  "openOfflinePortal",
  "playVoiceNote",
  "removeOfflineContent",
  "requestOfflineContent",
  "resumeWifiAware",
  "retryPendingOfflinePayments",
  "saveReceivedFile",
  "selectFile",
  "selectOfflineContent",
  "sendDirectMessage",
  "sendMessage",
  "sendOfflinePayment",
  "sendSolanaDevnetTransfer",
  "setOnlineIntegrationsEnabled",
  "startLiveVoice",
  "startMesh",
  "startOfflineNetwork",
  "stopMesh",
  "stopOfflineNetwork",
  "toggleVoiceNote"
];
const intentionallyUnreferenced = ["getOfflineWalletAddress"];

const [kotlin, workspace, app] = await Promise.all([
  readFile(kotlinPath, "utf8"),
  readFile(workspacePath, "utf8"),
  readFile(appPath, "utf8"),
]);

function sorted(values) {
  return [...values].sort();
}

function duplicates(values) {
  return values.filter((value, index) => values.indexOf(value) !== index);
}

function showDifference(label, actual, expected) {
  const actualSet = new Set(actual);
  const expectedSet = new Set(expected);
  const missing = expected.filter((name) => !actualSet.has(name));
  const unexpected = actual.filter((name) => !expectedSet.has(name));
  if (missing.length || unexpected.length) {
    console.error(`${label} mismatch`);
    if (missing.length) console.error(`  missing: ${missing.join(", ")}`);
    if (unexpected.length) console.error(`  unexpected: ${unexpected.join(", ")}`);
    return false;
  }
  return true;
}

const kotlinMethods = [...kotlin.matchAll(
  /^\s*@JavascriptInterface\s*\r?\n\s*fun\s+([A-Za-z0-9_]+)\s*\(/gm,
)].map((match) => match[1]);

const bridgeBlock = workspace.match(/type\s+NativeBridge\s*=\s*\{([\s\S]*?)\n\};/);
if (!bridgeBlock) {
  console.error("Could not locate the TypeScript NativeBridge contract.");
  process.exit(1);
}
const typescriptMethods = [...bridgeBlock[1].matchAll(
  /^\s{2}([A-Za-z0-9_]+)\s*:/gm,
)].map((match) => match[1]);

const sourceForUiCalls = `${workspace}\n${app}`;
const uiCalls = expectedBridgeMethods.filter((name) => {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(`(?:native|bridge|MeshNetNative)\\??\\.${escaped}\\s*\\(`).test(sourceForUiCalls);
});

let ok = true;
for (const [label, values] of [
  ["Kotlin bridge", kotlinMethods],
  ["TypeScript bridge", typescriptMethods],
]) {
  const duplicateNames = duplicates(values);
  if (duplicateNames.length) {
    console.error(`${label} has duplicate methods: ${sorted(duplicateNames).join(", ")}`);
    ok = false;
  }
  ok = showDifference(label, sorted(values), expectedBridgeMethods) && ok;
}
ok = showDifference("Active UI command calls", sorted(uiCalls), expectedUiCalls) && ok;

if (!ok) {
  console.error("Do not remove or rename a native bridge capability as part of a refactor. Update the reviewed capability contract first.");
  process.exit(1);
}

console.log(
  `Native bridge contract preserved: ${kotlinMethods.length} Kotlin methods, ${typescriptMethods.length} TypeScript declarations, ${uiCalls.length} active UI calls, and ${intentionallyUnreferenced.length} documented compatibility getter.`,
);
