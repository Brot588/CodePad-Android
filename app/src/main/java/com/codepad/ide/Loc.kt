package com.codepad.ide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 极简 i18n：中文原文做 key，英文查表。
 * 表里没有的键原样返回（保证不会显示成 key 或空白）。
 * lang 是 Compose 状态，切换后所有用到 Loc.t 的地方自动重组。
 */
object Loc {

    var lang by mutableStateOf("zh")

    fun isEn(): Boolean = lang == "en"

    fun t(zh: String): String {
        if (lang != "en") return zh
        return en[zh] ?: zh
    }

    /** 带参数的模板：用 %s 占位 */
    fun t(zh: String, vararg args: Any?): String {
        val s = t(zh)
        return try {
            String.format(s, *args)
        } catch (_: Throwable) {
            s
        }
    }

    val en: Map<String, String> = mapOf(
        // 顶栏 / 通用
        "资源管理器" to "Explorer",
        "搜索" to "Search",
        "工程" to "Project",
        "调试" to "Debug",
        "运行" to "Run",
        "停止" to "Stop",
        "保存" to "Save",
        "终端" to "Terminal",
        "更多" to "More",
        "新建文件" to "New File",
        "新建文件夹" to "New Folder",
        "重命名" to "Rename",
        "删除" to "Delete",
        "移动" to "Move",
        "关闭" to "Close",
        "取消" to "Cancel",
        "确定" to "OK",
        "完成" to "Done",
        "好" to "OK",
        "创建" to "Create",
        "刷新" to "Refresh",
        "收起" to "Hide",
        "重载" to "Reload",
        "查看" to "View",
        "返回" to "Back",
        "上一级" to "Up",
        // 工程菜单
        "新建工程…" to "New Project…",
        "新建工程" to "New Project",
        "选择运行目标…" to "Select Run Target…",
        "导入工程（zip）" to "Import Project (zip)",
        "导出当前工程（zip）" to "Export Project (zip)",
        "导出工程（zip）" to "Export Project (zip)",
        "重命名当前工程…" to "Rename Project…",
        "删除当前工程…" to "Delete Project…",
        "切换断点（当前行）" to "Toggle Breakpoint (Line)",
        "跳转到定义（F12）" to "Go to Definition (F12)",
        "网页预览（实时）" to "Web Preview (Live)",
        "收起网页预览" to "Hide Web Preview",
        "设置…" to "Settings…",
        "重载扩展" to "Reload Extensions",
        "关于 CodePad" to "About CodePad",
        // 搜索
        "在工程中搜索…" to "Search in project…",
        "个结果" to "results",
        // 文件树
        "新建 / 移动 / 重命名 / 删除" to "New / Move / Rename / Delete",
        "长按拖动文件到文件夹，或松手打开菜单" to "Long-press to drag into a folder, or release for menu",
        "从左侧选择文件，或长按文件夹新建" to "Pick a file on the left, or long-press a folder to create one",
        "还没有打开文件" to "No file open",
        // 底部面板
        "调试 ●" to "Debug ●",
        "清屏" to "Clear",
        "重启终端" to "Restart Terminal",
        "收起终端" to "Hide Terminal",
        "变量" to "Variables",
        "调用栈" to "Call Stack",
        "正在准备调试…" to "Preparing debugger…",
        "首次调试需要解压工具链（约 230MB），几十秒内完成" to "First debug unpacks the toolchain (~230MB), takes a few dozen seconds",
        "点顶栏 🐞 开始调试" to "Tap 🐞 in the top bar to start debugging",
        "Python / JavaScript / C / Rust · 点行号设断点" to "Python / JavaScript / C / Rust · tap a line number to set a breakpoint",
        "C / Rust 首次调试要解压工具链，需要稍等一下" to "C / Rust first debug unpacks the toolchain, please wait",
        // Git
        "这个工程还不是 git 仓库" to "This project is not a git repository yet",
        "点右上「初始化」把这个工程变成 git 仓库" to "Tap “Initialize” to turn this project into a git repo",
        "刷新中…" to "Refreshing…",
        "初始化" to "Initialize",
        "分支" to "branch",
        "全部暂存" to "Stage All",
        "提交" to "Commit",
        "提交信息（提交已暂存的改动）" to "Commit message (commits staged changes)",
        "还没有任何提交" to "No commits yet",
        "历史" to "History",
        "改动" to "Changes",
        "(可能未初始化)" to " (maybe uninitialized)",
        // 设置
        "设置" to "Settings",
        "编辑器字号" to "Editor font size",
        "缩进宽度" to "Indent width",
        "自动换行" to "Word wrap",
        "终端字号" to "Terminal font size",
        "语言" to "Language",
        "中文" to "中文",
        "English" to "English",
        // 对话框
        "新建工程" to "New Project",
        "工程名称" to "Project name",
        "模板" to "Template",
        "空工程" to "Empty",
        "重命名工程" to "Rename Project",
        "删除工程" to "Delete Project",
        "名称" to "Name",
        "输入" to "Input",
        "关于 CodePad" to "About CodePad",
        "口袋里的 Android IDE：" to "An Android IDE in your pocket:",
        "· 工程 = 文件夹，支持整包 zip 导入导出" to "· A project is just a folder; import/export as zip",
        "· Monaco 编辑器 + clangd 补全 + 原生 PTY 终端" to "· Monaco editor + clangd completion + real PTY terminal",
        "· C / C++ / Rust / Python / JavaScript / Shell 运行" to "· Run C / C++ / Rust / Python / JavaScript / Shell",
        "· C / C++ / Rust / Python / JS 断点调试" to "· Breakpoint debugging for C / C++ / Rust / Python / JS",
        "· 内置 clang、rustc+cargo、cmake" to "· Bundled clang, rustc+cargo, cmake",
        "日志" to "Logs",
        // 状态栏
        "编辑器就绪" to "Editor ready",
        "已保存" to "Saved",
        "未保存" to "Unsaved",
        "已终止" to "Terminated",
        "调试结束，退出码 %s" to "Debug finished, exit code %s",
        "程序退出，退出码 %s" to "Program exited, code %s",
        "进程结束，退出码 %s" to "Process exited, code %s",
        "工程：%s" to "Project: %s",
        "已创建工程 %s" to "Created project %s",
        "删除失败: %s" to "Delete failed: %s",
        "预览失败: %s" to "Preview failed: %s",
        "没有暂存的改动（先点 ＋ 或「全部暂存」）" to "Nothing staged (tap + or Stage All first)",
        "先写提交信息" to "Write a commit message first",
        "已提交" to "Committed",
        "git 不可用" to "git unavailable",
        "已丢弃 %s" to "Discarded %s",
        "丢弃" to "Discard",
        "丢弃改动" to "Discard changes",
        "刷新失败: %s" to "Refresh failed: %s",
        "git 操作失败: %s" to "git operation failed: %s",
        "读取历史失败: %s" to "Failed to read history: %s",
        "没找到定义（文件可能不在编译数据库里）" to "No definition found (file may be missing from the compilation database)",
        "当前文件不是网页（html/css/js）" to "Current file is not a web page (html/css/js)",
        "当前文件附近没有 index.html" to "No index.html near the current file",
        "定义所在文件打不开: %s" to "Cannot open definition file: %s",
        " 个结果" to " results",
        "(可能未初始化) " to " (maybe uninitialized) ",
        "/ · 工程根目录" to "/ · Project root",
        "300+ 处（已截断）" to "300+ matches (truncated)",
        "Aa 区分大小写" to "Aa Case sensitive",
        "Aa 忽略大小写" to "Aa Ignore case",
        "build 目录里的可执行文件：" to "Executables in build/:",
        "不能移动到自身或自己的子文件夹里" to "Cannot move into itself or its subfolder",
        "先打开一个文件" to "Open a file first",
        "先运行一次 CMake 工程，再选目标" to "Build a CMake project once, then pick a target",
        "全局搜索" to "Global Search",
        "关闭搜索" to "Close search",
        "准备中…" to "Preparing…",
        "单步跳过" to "Step over",
        "单步进入" to "Step into",
        "只能在当前工程内移动" to "Can only move within the current project",
        "启动中…" to "Starting…",
        "多语言" to "Multi-language",
        "定义所在文件打不开: " to "Cannot open definition file: ",
        "导入工程" to "Import project",
        "就绪" to "Ready",
        "展开 / 折叠" to "Expand / Collapse",
        "工程已删除" to "Project deleted",
        "工程已重命名" to "Project renamed",
        "工程根目录" to "Project root",
        "已停止" to "Stopped",
        "已切换工程" to "Project switched",
        "已初始化 git 仓库" to "git repository initialized",
        "已移动到 " to "Moved to ",
        "已经在调试中" to "Already debugging",
        "已结束" to "Finished",
        "扩展已重新加载" to "Extensions reloaded",
        "搜索中…" to "Searching…",
        "文件不存在了" to "File no longer exists",
        "文件夹名称" to "Folder name",
        "文件操作" to "File actions",
        "断点" to "Breakpoint",
        "未命名工程" to "Untitled Project",
        "没有匹配" to "No matches",
        "目前支持 Python / JavaScript / C / Rust 调试" to "Debugging supports Python / JavaScript / C / Rust",
        "目标不是文件夹" to "Target is not a folder",
        "目标文件夹里已有同名项" to "An item with the same name already exists",
        "示例工程" to "Sample Project",
        "移到上一层" to "Move up one level",
        "移动到文件夹" to "Move to folder",
        "移动到文件夹…" to "Move to Folder…",
        "移动失败" to "Move failed",
        "空工程 · 点右上角 + 新建文件" to "Empty project · tap + in the top bar to create a file",
        "继续" to "Continue",
        "要搜什么？" to "Search…",
        "调试器启动失败: " to "Debugger failed to start: ",
        "跳出" to "Step out",
        "运行中…" to "Running…",
        "选择运行目标" to "Select Run Target",
        "重命名失败：名称重复或非法" to "Rename failed: duplicate or invalid name",
    )
}
