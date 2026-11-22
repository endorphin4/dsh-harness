"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.requireBuiltin = requireBuiltin;
exports.isAllowedInternalId = isAllowedInternalId;
exports.getBindingInfo = getBindingInfo;
const node_path_1 = __importDefault(require("node:path"));
// dsh-mobile native-binding fallback (N1): no prebuilt addon for this platform (Android has none).
// The shell already launches node with --expose-internals, which exposes exactly the
// internal/modules/* entries internalModules() needs; fall back to require() for them
// instead of crashing host preparation. See scripts/patches/registry.json narb-android-N1.
let api;
try {
    const { createEntryApi } = require('node-addon-native-custom-loader');
    api = createEntryApi(node_path_1.default.resolve(__dirname, '..'));
} catch (error) {
    api = undefined;
    if (!globalThis.__dshMobileNativeBindingFallbackWarned) {
        globalThis.__dshMobileNativeBindingFallbackWarned = true;
        console.warn('node-addon-require-builtin: no prebuilt native binding for this platform; ' +
            'falling back to require() (dsh-mobile N1, requires --expose-internals): ' +
            (error && error.message ? error.message : String(error)));
    }
}
function requireBuiltin(moduleId) {
    if (api !== undefined) return api.requireBuiltin(moduleId);
    return require(moduleId);
}
function isAllowedInternalId(moduleId) {
    if (api !== undefined) return api.isAllowedInternalId(moduleId);
    try { require(moduleId); return true; } catch { return false; }
}
function getBindingInfo() {
    if (api !== undefined) return api.getBindingInfo();
    return { backend: 'expose-internals', package: undefined, abi: process.versions.modules };
}
exports.default = {
    requireBuiltin,
    isAllowedInternalId,
    getBindingInfo,
};
