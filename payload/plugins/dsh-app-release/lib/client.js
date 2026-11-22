/**
 * dsh-app-release 的浏览器端：故意什么都不做。
 *
 * 引擎版本标签改由 App 原生注入（MainActivity + Probe），不再依赖这条客户端 bundle ——
 * 这样它既不会因为插槽/框架差异而不显示，也不可能连带弄坏侧栏其它客户端插件（浏览器等）。
 * 宿主插件（lib/index.js）仍然保留，用于 /api/dsh-app-release 的 status/check 接口。
 */
window.__ModuleLoader__.load({
	id: "dsh-app-release",
	factory: function () {
		return { exports: {} };
	}
});
