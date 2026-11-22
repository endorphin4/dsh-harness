/**
 * 引擎更新的纯逻辑（不依赖任何运行时），便于单独测试。
 *
 * 背景：0.10.27 的白屏事故是「老外壳 + 新引擎」组合造成的。外壳（APK）承载
 * 前端资源与重启回调，引擎升级必须要求外壳至少是某个版本，否则宁可不让升。
 */

export function parseVersion(value) {
  const m = String(value ?? "").match(/(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?/);
  if (!m) return null;
  return { nums: m.slice(1, 4).map(Number), pre: m[4] ?? null };
}

/** semver 粗比：数字段 + 预发布（有预发布 < 无预发布）。返回 >0 表示 a 更新。 */
export function compareVersions(a, b) {
  const x = parseVersion(a);
  const y = parseVersion(b);
  if (!x || !y) return 0;
  for (let i = 0; i < 3; i += 1) {
    if (x.nums[i] !== y.nums[i]) return x.nums[i] - y.nums[i];
  }
  if (x.pre === y.pre) return 0;
  if (x.pre === null) return 1;
  if (y.pre === null) return -1;
  return x.pre > y.pre ? 1 : -1;
}

/**
 * 决定是否允许升级引擎。
 * @param {object} input
 * @param {string|null} input.appCurrent   当前外壳版本（如 0.10.28）
 * @param {string|null} input.engineCurrent 当前引擎版本
 * @param {string|null} input.engineLatest  远端引擎最新版
 * @param {string|null} input.minShell      该引擎要求的最低外壳版本（可为空=不限制）
 * @returns {{updateAvailable:boolean, blockedReason:string|null}}
 */
export function decideEngineUpdate({ appCurrent, engineCurrent, engineLatest, minShell }) {
  if (!engineLatest) return { updateAvailable: false, blockedReason: null };
  const newer = engineCurrent ? compareVersions(engineLatest, engineCurrent) > 0 : true;
  if (!newer) return { updateAvailable: false, blockedReason: null };
  if (minShell && appCurrent && compareVersions(appCurrent, minShell) < 0) {
    return {
      updateAvailable: false,
      blockedReason: `该引擎要求外壳 ≥ ${minShell}，当前 ${appCurrent}；请先更新 App`
    };
  }
  return { updateAvailable: true, blockedReason: null };
}
