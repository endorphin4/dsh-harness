package dev.dsh.harness;

import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 给“当前引擎”打补丁：dsh-agent-loop 在没有 tool-runtime 调度器时抛的是
 * Cannot read properties of undefined (reading 'prepare')，看起来像模型坏了；
 * 补丁把它变成一句人能看懂的错误 + 落盘诊断。幂等：已打过就直接返回。
 */
final class EngineGuard {

    private static final String TAG = "DSH-B";
    private static final String NEEDLE = "ctx.tools[TOOL_RUNTIME_SCHEDULER].prepare(";

    private EngineGuard() {}

    static String apply(File root) {
        File loop = new File(root, "usr/lib/node_modules/@deepseek-ai/dsh/node_modules/"
                + "@deepseek-ai/dsh-agent-loop/lib/index.js");
        if (!loop.isFile()) {
            return "打补丁：找不到 dsh-agent-loop（" + loop + "）";
        }
        try {
            String s = new String(Files.readAllBytes(loop.toPath()), StandardCharsets.UTF_8);
            if (s.contains("reportSchedulerMissing")) return "打补丁：已经打过，无需重复";
            if (!s.contains(NEEDLE)) return "打补丁：当前引擎结构不认识（未找到调用点），跳过";
            // 1) 在调度器调用点前插守卫：拿不到就报错并落盘
            String guard = "const __sched = ctx.tools?.[TOOL_RUNTIME_SCHEDULER];"
                    + "if (!__sched || typeof __sched.prepare !== 'function') {"
                    + " try { const fs = await import('node:fs');"
                    + " fs.appendFileSync(process.env.HOME + '/试玩/dsh-run-errors.log',"
                    + " new Date().toISOString() + ' 调度器缺失 tools=' + Object.keys(ctx.tools || {}).join(',') + '\\n'); } catch (e) {}"
                    + " throw new Error('工具调度器未注册（@deepseek-ai/dsh-tools.scheduler 缺失）：运行时状态异常，重启 DSH 后重试'); }"
                    + "const __prepared = await __sched.prepare(";
            s = s.replace("const prepared = await " + NEEDLE, guard);
            Files.write(loop.toPath(), s.getBytes(StandardCharsets.UTF_8));
            return "打补丁：dsh-agent-loop 已加调度器守卫";
        } catch (Throwable e) {
            Log.w(TAG, "打补丁失败：" + e);
            return "打补丁失败：" + e;
        }
    }
}
