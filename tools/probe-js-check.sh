#!/data/data/com.termux/files/usr/bin/bash
# 把 Probe.js() 的返回值 dump 成 JS 文件并做语法检查（构建期拦截"注入脚本自己写坏"的回归）
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/.probe-check.js"
mkdir -p "$ROOT/.pj"
python3 - "$ROOT" <<'PY'
import sys
root=sys.argv[1]
s=open(root+'/app/src/main/java/dev/dsh/harness/Probe.java',encoding='utf-8').read()
s=s.replace('package dev.dsh.harness;','',1).replace('final class Probe','final class Dump',1)
s=s.replace('private Probe() {}','private Dump() {}').replace('Utils.jsStr(version)','q(version)').replace('Utils.jsStr(appVersion)','q(appVersion)')
add='''
    static String q(String x) { return "'" + x.replace("\\\\", "\\\\\\\\").replace("'", "\\\\'") + "'"; }
    public static void main(String[] a) {
        StringBuilder sb = new StringBuilder();
        sb.append(js("check"));
        sb.append("\\n");
        sb.append(engineLabelJs("0.9.9-test"));
        System.out.print(sb);
    }
'''
k=s.rstrip().rfind('}')
open(root+'/.pj/Dump.java','w',encoding='utf-8').write(s[:k]+add+'\n}\n')
PY
javac -d "$ROOT/.pj/out" "$ROOT/.pj/Dump.java" || exit 3
java -cp "$ROOT/.pj/out" Dump > "$OUT" || exit 4
command -v node >/dev/null || { echo "probe-js-check: 无 node，跳过"; exit 0; }
node --check "$OUT" || { echo "probe-js-check: 探针 JS 语法错误"; exit 5; }
echo "probe-js-check: OK ($(wc -c < "$OUT") 字节)"
