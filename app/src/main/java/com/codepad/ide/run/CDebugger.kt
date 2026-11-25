package com.codepad.ide.run

import com.codepad.ide.Loc

import android.util.Log
import com.codepad.ide.App
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * C 调试器：clang 编译（-g）→ gdbserver 启动 → RSP 客户端控制。
 * 断点地址来自 DWARF 行表，暂停位置用 PC 反查行号。
 *
 * 源码级单步：arm64 的 gdbserver 没有范围单步（vCont;r 用不了），
 * 所以这里在「按指令单步」之上自己算行：
 *   · 单步进入：一条条指令走，行号变了 / 走进没有源码行的地方就停；
 *   · 单步跳过：换函数时用 LR（或帧指针链）算出返回地址，临时下断点跑过去，
 *     这样 printf 之类不用一条条指令陪它走；
 *   · 跳出：从帧指针链取调用者的返回地址，临时下断点跑到那里。
 * 停在哪一行一律由「PC + DWARF 行表」决定，不靠「PC 是否离开源码范围」猜程序是否结束；
 * 程序结束只认 gdbserver 的 W/X 停止包。
 */
object CDebugger {

    /** 单条用户单步最多走多少条指令，兜底防止在循环里转不出来 */
    private const val MAX_INSTR_STEPS = 4000

    /** 致命信号：停在上面时按「继续」要把信号交回进程（否则会反复撞同一个信号） */
    private val FATAL_SIGNALS = setOf(4, 6, 7, 8, 11, 31)

    /** aarch64 断点长度（指令 4 字节） */
    private const val BP_KIND = 4

    // ------------------------------------------------------------ 进程 / 连接

    private var serverPfd: android.os.ParcelFileDescriptor? = null
    private var serverPidRef: Int = 0
    private var inferiorPid: Int = 0

    /** 被调试程序的标准输出（gdbserver 会转发到它的 stdout） */
    var onOutput: ((String) -> Unit)? = null

    private var client: GdbClient? = null

    /** 当前会话的命令队列（每次 start 换一个新的，旧会话的残留命令不会串到新会话） */
    @Volatile
    private var sessionQueue: LinkedBlockingQueue<String>? = null

    /** 会话号：旧会话的收尾逻辑 / 回调靠它判断自己是不是已经被新会话取代了 */
    @Volatile
    private var sessionId = 0

    @Volatile
    private var running = false

    // ------------------------------------------------------------ 会话状态

    private var table: List<Pair<Long, Int>> = emptyList()
    private var dwarf: DwarfInfo? = null
    private var srcName = ""

    /** PIE 加载基址：/proc/pid/maps 里 offset 为 0 的那段 */
    private var base = 0L

    /** 可执行文件自己的可执行段（[start, end)），用来区分「自己的代码」和 libc */
    private var textRanges: List<LongRange> = emptyList()

    /** 用户断点（绝对地址） */
    private val bpAddrs = ArrayList<Long>()

    /** 临时摘掉的用户断点：单步越过断点指令时必须先 z0 再 s，否则会原地 SIGTRAP */
    private val bpDisabled = HashSet<Long>()

    /** 当前停止点的 PC（绝对地址） */
    private var curPc = 0L

    /** 停在致命信号上时记下信号号：继续时要把信号交回进程，否则会一直撞同一个信号 */
    private var pausedSignal = 0

    fun isRunning(): Boolean = running

    fun start(
        source: File,
        breakpoints: List<Int>,
        rust: Boolean,
        publish: (String) -> Unit,
        log: (String) -> Unit,
    ) {
        stop()
        val myId = ++sessionId
        val queue = LinkedBlockingQueue<String>()
        sessionQueue = queue
        running = true
        Thread({
            try {
                runSession(myId, queue, source, breakpoints, rust, publish, log)
            } catch (t: Throwable) {
                // 用户点了「停止」/ 又开了一次调试时 socket 会被关掉，这不算失败
                if (myId == sessionId && running) {
                    log(Loc.t("C 调试失败: ") + (t.message ?: t.javaClass.simpleName))
                    publish("{\"type\":\"exit\",\"code\":1}")
                }
            } finally {
                // 已经有新会话接手时，不要动新会话的状态和进程
                if (myId == sessionId) {
                    running = false
                    cleanup()
                }
            }
        }, "c-debug").also { it.start() }
    }

    private fun runSession(
        myId: Int,
        queue: LinkedBlockingQueue<String>,
        source: File,
        breakpoints: List<Int>,
        rust: Boolean,
        rawPublish: (String) -> Unit,
        rawLog: (String) -> Unit,
    ) {
        // 旧会话可能还卡在 RSP 上，它之后所有回调都作废，不能打扰新会话的 UI
        val publish: (String) -> Unit = { json -> if (myId == sessionId) rawPublish(json) }
        val log: (String) -> Unit = { msg -> if (myId == sessionId) rawLog(msg) }
        if (!CToolchain.isReady()) {
            log(Loc.t("正在准备 C 工具链…"))
            if (!CToolchain.ensure { msg -> log(msg) }) {
                publish("{\"type\":\"exit\",\"code\":1}")
                return
            }
        }
        val buildDir = File(App.ctx.filesDir, "build").apply { mkdirs() }
        var exe = File(buildDir, source.nameWithoutExtension + if (rust) ".rs.dbg" else ".debug")
        var ok: Boolean
        val cmakeRoot = if (rust) null else CToolchain.cmakeRootOf(source)
        if (cmakeRoot != null) {
            log(Loc.t("CMake 构建 %s …", cmakeRoot.name))
            val built = CToolchain.buildCmake(cmakeRoot) { line -> log(line) }
            ok = built != null
            if (built != null) exe = built
        } else {
            log(Loc.t("编译 %s …", source.name))
            val result = when {
                rust -> compileRust(source, exe)
                source.name.endsWith(".cpp") || source.name.endsWith(".cc") || source.name.endsWith(".cxx") ->
                    CToolchain.compileCpp(source, exe)
                else -> CToolchain.compile(source, exe)
            }
            ok = result.first
            if (result.second.isNotBlank()) log(result.second.trimEnd())
        }
        if (!ok) {
            log(Loc.t("编译失败"))
            publish("{\"type\":\"exit\",\"code\":1}")
            return
        }

        table = CToolchain.lineTable(exe, source.name)
        srcName = source.name
        dwarf = CToolchain.debugInfo(exe)
        log(Loc.t("调试信息：%s 行 / %s 个函数", table.size, dwarf?.all()?.size ?: 0))

        cleanup()
        bpAddrs.clear()
        bpDisabled.clear()
        curPc = 0L
        pausedSignal = 0
        log(Loc.t("启动 gdbserver"))
        // 用 PTY 跑 gdbserver：被调试程序看到的是 tty，stdout 行缓冲，
        // printf 的输出能立刻透出来（管道里 libc 会攒满 4KB 才吐）。
        val envArr = arrayOf(
            "LD_LIBRARY_PATH=" + CToolchain.libPath(),
            "HOME=" + App.ctx.filesDir.absolutePath,
            "SHELL=/system/bin/sh",
            "PATH=" + CToolchain.root.absolutePath + "/bin:/system/bin:/system/xbin",
        )
        val ptyRes = com.codepad.ide.term.Pty.start(
            CToolchain.binary("gdbserver").absolutePath,
            arrayOf("gdbserver", ":0", exe.absolutePath),
            source.parentFile?.absolutePath ?: App.ctx.filesDir.absolutePath,
            envArr,
            24,
            200,
        )
        val parts = ptyRes.split(":")
        if (parts.size < 2) {
            log(Loc.t("连接 gdbserver 失败"))
            publish("{\"type\":\"exit\",\"code\":1}")
            return
        }
        val ptyFd = parts[0].toIntOrNull() ?: -1
        val serverPid = parts[1].toIntOrNull() ?: 0
        if (ptyFd < 0) {
            log(Loc.t("连接 gdbserver 失败"))
            publish("{\"type\":\"exit\",\"code\":1}")
            return
        }
        serverPidRef = serverPid
        val pfd = android.os.ParcelFileDescriptor.adoptFd(ptyFd)
        serverPfd = pfd

        val portRef = java.util.concurrent.atomic.AtomicInteger(0)
        val portLatch = java.util.concurrent.CountDownLatch(1)
        Thread {
            try {
                val input = java.io.FileInputStream(pfd.fileDescriptor)
                val buf = ByteArray(4096)
                val lineBuf = StringBuilder()
                while (running) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    val chunk = String(buf, 0, n, Charsets.UTF_8)
                    lineBuf.append(chunk)
                    while (true) {
                        val idx = lineBuf.indexOf("\n")
                        if (idx < 0) break
                        var line = lineBuf.substring(0, idx).trimEnd('\r')
                        lineBuf.delete(0, idx + 1)
                        Log.i("CodePadC", "gdbserver: " + line)
                        val m = Regex("Listening on port\\s+(\\d+)").find(line)
                        if (m != null && portRef.get() == 0) {
                            portRef.set(m.groupValues[1].toIntOrNull() ?: 0)
                            portLatch.countDown()
                        }
                        val protocol = line.startsWith("Listening on port") ||
                            line.startsWith("Remote debugging") ||
                            line.contains("created; pid =") ||
                            line.startsWith("Exiting") ||
                            line.startsWith("Can't bind")
                        if (!protocol && line.isNotEmpty()) onOutput?.invoke(line)
                    }
                }
            } catch (_: Throwable) {
            }
        }.also { it.isDaemon = true; it.start() }

        portLatch.await(8, TimeUnit.SECONDS)
        val port = portRef.get()
        if (port <= 0) {
            log(Loc.t("连接 gdbserver 失败"))
            publish("{\"type\":\"exit\",\"code\":1}")
            return
        }
        log(Loc.t("gdbserver 端口 %s", port))
        val socket = Socket()
        var connected = false
        for (attempt in 0 until 40) {
            try {
                socket.connect(InetSocketAddress("127.0.0.1", port), 200)
                connected = true
                break
            } catch (_: Throwable) {
                Thread.sleep(100)
            }
        }
        if (!connected) {
            log(Loc.t("连接 gdbserver 失败"))
            publish("{\"type\":\"exit\",\"code\":1}")
            return
        }
        val gdb = GdbClient(socket)
        gdb.onConsole = { text -> onOutput?.invoke(text.trimEnd('\n')) }
        client = gdb

        Log.i("CodePadRSP", "qSupported -> " + gdb.send("qSupported:multiprocess+;swbreak+;hwbreak+"))
        val stopReply = gdb.send("?")
        Log.i("CodePadRSP", "? -> " + stopReply)
        if (stopReply.startsWith("W") || stopReply.startsWith("X")) {
            val code = stopReply.drop(1).take(2).toIntOrNull(16) ?: 0
            log(Loc.t("程序退出，退出码 %s", code))
            publish("{\"type\":\"exit\",\"code\":$code}")
            return
        }
        val pid = Regex("thread:p([0-9a-fA-F]+)\\.").find(stopReply)?.groupValues?.get(1)?.toIntOrNull(16)
        inferiorPid = pid ?: 0
        val maps = if (inferiorPid > 0) loadMaps(inferiorPid, exe.absolutePath) else null
        base = maps?.base ?: 0L
        textRanges = maps?.exec ?: emptyList()
        Log.i(
            "CodePadRSP",
            "pid=" + inferiorPid + " base=0x" + java.lang.Long.toHexString(base) +
                " text=" + textRanges.size + " ranges"
        )

        for (bp in breakpoints) {
            val a = CToolchain.addressForLine(table, bp)
            if (a != null) bpAddrs.add(base + a) else log(Loc.t("第 %s 行没有可执行代码，已跳过", bp))
        }
        for (addr in bpAddrs) {
            val reply = gdb.send("Z0,%x,%d".format(addr, BP_KIND))
            Log.i("CodePadRSP", "Z0 0x%x -> %s".format(addr, reply))
            if (reply != "OK") log(Loc.t("断点设置失败 @0x%x", addr))
        }

        log(Loc.t("已进入调试"))
        var mode = "c"
        while (running) {
            val st = execute(gdb, mode, log)
            if (st.error) {
                log(Loc.t("与 gdbserver 的连接中断"))
                publish("{\"type\":\"exit\",\"code\":1}")
                return
            }
            if (st.exited) {
                if (st.bySignal) log(Loc.t("程序收到信号 %s 终止", signalName(st.exitCode)))
                else log(Loc.t("程序退出，退出码 %s", st.exitCode))
                publish("{\"type\":\"exit\",\"code\":${st.exitCode}}")
                return
            }
            val pc = st.pc()
            if (pc <= 0) {
                log(Loc.t("读取 PC 失败"))
                publish("{\"type\":\"exit\",\"code\":1}")
                return
            }
            curPc = pc
            val rel = pc - base
            val func = dwarf?.at(rel)
            val line = lineAt(rel)
            val regs = readRegs(gdb, st)
            val vars = if (regs != null) evalVars(gdb, regs, func, line) else emptyList()
            val frames = if (regs != null) walkFrames(gdb, regs, base) else emptyList()
            val note = noteFor(st, func, line)
            pausedSignal = if (st.signal in FATAL_SIGNALS) st.signal else 0
            Log.i("CodePadRSP", "stop pc=0x" + java.lang.Long.toHexString(pc) + " line=" + line + " reason=" + st.reason)
            publish(buildPause(pc, line, func?.name ?: "", vars, frames, st.reason, note))

            val next = queue.poll(600, TimeUnit.SECONDS) ?: "k"
            if (next == "k") {
                stopInferior(gdb)
                publish("{\"type\":\"exit\",\"code\":0}")
                return
            }
            mode = next
        }
    }

    // ------------------------------------------------------------ 单步引擎

    /** 一次停止：停止包 + 之后按需读到的寄存器 */
    private class Stop(val reply: String) {
        val exited: Boolean = reply.startsWith("W") || reply.startsWith("X")
        /** X 包表示「被信号杀死」，里面有信号号 */
        val bySignal: Boolean = reply.startsWith("X")
        val exitCode: Int = if (exited) reply.drop(1).take(2).toIntOrNull(16) ?: 0 else 0
        val signal: Int =
            if ((reply.startsWith("T") || reply.startsWith("S")) && reply.length >= 3)
                reply.substring(1, 3).toIntOrNull(16) ?: 0 else 0
        val error: Boolean = reply.isEmpty() || reply.startsWith("E")
        var regs: LongArray? = null
        var reason: String = "breakpoint"

        fun pc(): Long = regs?.getOrNull(32) ?: 0L
    }

    private fun readRegs(gdb: GdbClient, st: Stop): LongArray? {
        if (st.regs == null && !st.exited && !st.error) st.regs = gdb.readRegisters()
        return st.regs
    }

    private fun execute(gdb: GdbClient, mode: String, log: (String) -> Unit): Stop = when (mode) {
        "n" -> stepOver(gdb, log)
        "s" -> stepInto(gdb, log)
        "u" -> stepOut(gdb)
        else -> continueExec(gdb)
    }

    /** 继续：当前 PC 正好压在断点指令上时先单步越过，否则会原地再撞一次 */
    private fun continueExec(gdb: GdbClient): Stop {
        val early = stepOffBreakpoint(gdb)
        if (early != null) {
            early.reason = reasonOf(early, "breakpoint")
            return early
        }
        // 停在致命信号（段错误等）上：先用 C<sig> 把信号交回进程；gdbserver 若又停在同一个
        // 信号上（出错指令再执行还是会崩），就当作程序被信号杀死，结束会话
        val sig = pausedSignal
        pausedSignal = 0
        if (sig > 0 && sig in FATAL_SIGNALS) {
            val st = Stop(gdb.send("C%02x".format(sig)))
            Log.i("CodePadRSP", "C%02x -> %s".format(sig, st.reply))
            if (!st.exited && !st.error && st.signal == sig) {
                stopInferior(gdb)
                return Stop("X%02x".format(sig))
            }
            st.reason = reasonOf(st, "breakpoint")
            readRegs(gdb, st)
            if (!st.exited && !st.error) curPc = st.pc()
            return st
        }
        val st = Stop(gdb.send("c"))
        Log.i("CodePadRSP", "c -> " + st.reply)
        st.reason = reasonOf(st, "breakpoint")
        readRegs(gdb, st)
        if (!st.exited && !st.error) curPc = st.pc()
        return st
    }

    /** 单步进入：走到下一行（进了没有源码的地方也停） */
    private fun stepInto(gdb: GdbClient, log: (String) -> Unit): Stop {
        val startLine = lineAt(curPc - base)
        var guard = 0
        while (true) {
            val st = stepInstr(gdb)
            if (st.exited || st.error) {
                st.reason = "step"
                return st
            }
            if (st.signal != 5) {
                st.reason = "signal"
                return st
            }
            val pc = st.pc()
            if (userBpHit(pc)) {
                st.reason = "breakpoint"
                return st
            }
            val line = lineAt(pc - base)
            if (line == 0 || line != startLine) {
                st.reason = "step"
                return st
            }
            if (++guard > MAX_INSTR_STEPS) {
                log(Loc.t("单步超过 %s 条指令，先停在这里", MAX_INSTR_STEPS))
                st.reason = "step"
                return st
            }
        }
    }

    /** 单步跳过：同一行内继续走；换行就停；进了别的函数就跑到返回地址 */
    private fun stepOver(gdb: GdbClient, log: (String) -> Unit): Stop {
        val startLine = lineAt(curPc - base)
        val startFunc = dwarf?.at(curPc - base)
        var guard = 0
        while (true) {
            val st = stepInstr(gdb)
            if (st.exited || st.error) {
                st.reason = "next"
                return st
            }
            if (st.signal != 5) {
                st.reason = "signal"
                return st
            }
            val pc = st.pc()
            if (userBpHit(pc)) {
                st.reason = "breakpoint"
                return st
            }
            val func2 = dwarf?.at(pc - base)
            val sameFunc = func2 != null && startFunc != null && func2.low == startFunc.low
            if (!sameFunc) {
                // 走进了被调用函数 / PLT 桩 / 库函数：单步跳过要把整个调用跑完
                val ret = callReturn(gdb, st.regs)
                if (ret != null && ret != pc) {
                    if (insertedBpAt(ret) != null) {
                        // 返回地址上本来就有断点，继续就会停在那里
                        val r = Stop(gdb.send("c"))
                        r.reason = "breakpoint"
                        readRegs(gdb, r)
                        if (!r.exited && !r.error) curPc = r.pc()
                        return r
                    }
                    if (tempBpInsert(gdb, ret)) {
                        val r = Stop(gdb.send("c"))
                        tempBpRemove(gdb, ret)
                        if (r.exited || r.error) {
                            r.reason = "next"
                            return r
                        }
                        readRegs(gdb, r)
                        val rpc = r.pc()
                        curPc = rpc
                        if (userBpHit(rpc)) {
                            r.reason = "breakpoint"
                            return r
                        }
                        if (r.signal != 5) {
                            r.reason = "signal"
                            return r
                        }
                        val line2 = lineAt(rpc - base)
                        if (line2 == 0 || line2 != startLine) {
                            r.reason = "next"
                            return r
                        }
                        // 回到同一行（比如 x = f() + 1），接着按指令走
                        if (++guard > MAX_INSTR_STEPS) {
                            log(Loc.t("单步超过 %s 条指令，先停在这里", MAX_INSTR_STEPS))
                            r.reason = "next"
                            return r
                        }
                        continue
                    }
                    // 插不上临时断点：退回逐条指令（下面有 guard 兜底）
                } else if (startFunc == null) {
                    // 本来就没有源码行可依（停在库里），又没有可用的返回地址：停在这条指令
                    st.reason = "next"
                    return st
                } else if (!inOwnText(pc)) {
                    // 返回地址也不在自己的代码里：已经走出源程序（main 返回给 libc）
                    return endOfProgram(gdb)
                }
            }
            val line = lineAt(pc - base)
            // 换行就停；同一行（或同一函数里没有行号的地方）继续走
            if (line == 0 || line != startLine) {
                st.reason = "next"
                return st
            }
            if (++guard > MAX_INSTR_STEPS) {
                log(Loc.t("单步超过 %s 条指令，先停在这里", MAX_INSTR_STEPS))
                st.reason = "next"
                return st
            }
        }
    }

    /** 跳出：跑到调用者的返回地址 */
    private fun stepOut(gdb: GdbClient): Stop {
        val regs = gdb.readRegisters()
        val ret = frameReturn(gdb, regs)
        if (ret == null) {
            // 没有调用者（main）或调用者在库里：一路跑完
            val st = continueExec(gdb)
            st.reason = when {
                st.signal != 0 && st.signal != 5 -> "signal"
                userBpHit(st.pc()) -> "breakpoint"
                else -> "return"
            }
            return st
        }
        if (insertedBpAt(ret) != null) {
            val st = Stop(gdb.send("c"))
            st.reason = "breakpoint"
            readRegs(gdb, st)
            if (!st.exited && !st.error) curPc = st.pc()
            return st
        }
        if (!tempBpInsert(gdb, ret)) {
            val st = continueExec(gdb)
            st.reason = when {
                st.signal != 0 && st.signal != 5 -> "signal"
                userBpHit(st.pc()) -> "breakpoint"
                else -> "return"
            }
            return st
        }
        val st = Stop(gdb.send("c"))
        tempBpRemove(gdb, ret)
        if (st.exited || st.error) {
            st.reason = "return"
            return st
        }
        readRegs(gdb, st)
        if (!st.exited && !st.error) curPc = st.pc()
        st.reason = when {
            userBpHit(st.pc()) -> "breakpoint"
            st.signal != 5 -> "signal"
            else -> "return"
        }
        return st
    }

    /** 走出源程序之后（比如 main 里的 return）：继续跑，交给主循环报退出 */
    private fun endOfProgram(gdb: GdbClient): Stop {
        val st = continueExec(gdb)
        st.reason = when {
            st.signal != 0 && st.signal != 5 -> "signal"
            userBpHit(st.pc()) -> "breakpoint"
            else -> "next"
        }
        return st
    }

    /** 单条指令单步。当前 PC 上若有已插入的用户断点，先摘掉再 s */
    private fun stepInstr(gdb: GdbClient): Stop {
        val bp = insertedBpAt(curPc)
        if (bp != null) disableBp(gdb, bp)
        val st = Stop(gdb.send("s"))
        if (bp != null) enableBp(gdb, bp)
        if (!st.exited && !st.error) {
            readRegs(gdb, st)
            curPc = st.pc()
            Log.d("CodePadRSP", "s -> pc=0x" + java.lang.Long.toHexString(curPc))
        }
        return st
    }

    private fun stepOffBreakpoint(gdb: GdbClient): Stop? {
        val bp = insertedBpAt(curPc) ?: return null
        disableBp(gdb, bp)
        val st = Stop(gdb.send("s"))
        enableBp(gdb, bp)
        if (st.exited || st.error) return st
        readRegs(gdb, st)
        curPc = st.pc()
        if (st.signal != 5) return st
        return null
    }

    // ------------------------------------------------------------ 断点管理

    private fun insertedBpAt(addr: Long): Long? {
        if (addr <= 0) return null
        if (!bpAddrs.contains(addr)) return null
        return if (bpDisabled.contains(addr)) null else addr
    }

    private fun userBpHit(addr: Long): Boolean {
        if (addr <= 0) return false
        if (!bpAddrs.contains(addr)) return false
        return !bpDisabled.contains(addr)
    }

    private fun disableBp(gdb: GdbClient, addr: Long): Boolean {
        if (bpDisabled.contains(addr)) return true
        val r = gdb.send("z0,%x,%d".format(addr, BP_KIND))
        if (r != "OK") return false
        bpDisabled.add(addr)
        return true
    }

    private fun enableBp(gdb: GdbClient, addr: Long) {
        if (!bpDisabled.contains(addr)) return
        if (gdb.send("Z0,%x,%d".format(addr, BP_KIND)) == "OK") bpDisabled.remove(addr)
    }

    private fun tempBpInsert(gdb: GdbClient, addr: Long): Boolean =
        gdb.send("Z0,%x,%d".format(addr, BP_KIND)) == "OK"

    private fun tempBpRemove(gdb: GdbClient, addr: Long) {
        gdb.send("z0,%x,%d".format(addr, BP_KIND))
    }

    // ------------------------------------------------------------ 地址 → 行

    /** pc（相对加载基址）对应的源码行；0 表示不在当前文件的源码里 */
    private fun lineAt(rel: Long): Int {
        if (rel <= 0 || table.isEmpty()) return 0
        val func = dwarf?.at(rel)
        if (func != null) {
            // 已知函数：只有本文件的函数才查行号，std / 头文件里的帧不能算到本文件行上
            if (func.declFile.isNotEmpty() && !func.declFile.endsWith(srcName)) return 0
            return CToolchain.lineForAddress(table, rel) ?: 0
        }
        val all = dwarf?.all() ?: emptyList()
        if (all.isNotEmpty()) return 0
        // 没有 DWARF 函数信息时退回行表，但要卡在行表覆盖范围内
        if (rel < table.first().first || rel > table.last().first + 64) return 0
        return CToolchain.lineForAddress(table, rel) ?: 0
    }

    /** 地址是否落在被调试程序自己的可执行段里（libc / ld.so 不算） */
    private fun inOwnText(addr: Long): Boolean {
        if (addr <= 0) return false
        if (textRanges.isEmpty()) {
            val rel = addr - base
            return rel in 0x100L..0x4000000L
        }
        for (r in textRanges) if (addr >= r.first && addr <= r.last) return true
        return false
    }

    /** [x29 + 8]：本帧保存的返回地址（要求帧指针已经建立） */
    private fun stackReturn(gdb: GdbClient, regs: LongArray?): Long? {
        val fp = regs?.getOrNull(29) ?: 0L
        if (fp <= 0x1000) return null
        val v = gdb.readLong(fp + 8) ?: return null
        return if (v > 0) v else null
    }

    private fun linkRegister(regs: LongArray?): Long = regs?.getOrNull(30) ?: 0L

    /** 地址是否落在给定函数体内 */
    private fun insideFunc(addr: Long, func: DwarfFunc?): Boolean {
        if (func == null || addr <= 0) return false
        val rel = addr - base
        return rel >= func.low && rel < func.high
    }

    /**
     * 单步跳过用：刚执行完 bl 时 x30 就是调用点的下一条指令，
     * 所以优先 LR；LR 不可用时再看帧指针链。
     */
    private fun callReturn(gdb: GdbClient, regs: LongArray?): Long? {
        val l = linkRegister(regs)
        if (l > 0 && inOwnText(l)) return l
        val s = stackReturn(gdb, regs)
        if (s != null && inOwnText(s)) return s
        return null
    }

    /**
     * 跳出用：本函数的返回地址。
     * 帧指针链 [x29 + 8] 最可靠，但刚进函数（prologue 还没把 LR 存下来）时
     * x29 还是调用者的，只能用 LR；另外候选地址不能落在当前函数体内。
     */
    private fun frameReturn(gdb: GdbClient, regs: LongArray?): Long? {
        val pc = regs?.getOrNull(32) ?: 0L
        val func = dwarf?.at(pc - base)
        val l = linkRegister(regs)
        if (func != null && pc - func.low <= 12L) {
            return if (l > 0 && inOwnText(l) && !insideFunc(l, func)) l else null
        }
        val s = stackReturn(gdb, regs)
        if (s != null && inOwnText(s) && !insideFunc(s, func)) return s
        if (l > 0 && inOwnText(l) && !insideFunc(l, func)) return l
        return null
    }

    /** PIE 可执行文件的加载基址 + 自己的可执行段（/proc/pid/maps） */
    private class Maps(val base: Long, val exec: List<LongRange>)

    private fun loadMaps(pid: Int, exePath: String): Maps? {
        return try {
            val lines = File("/proc/$pid/maps").readLines()
            var base = 0L
            var lowest = 0L
            val exec = ArrayList<LongRange>()
            for (line in lines) {
                if (!line.contains(exePath)) continue
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 3) continue
                val start = parts[0].substringBefore("-").toLongOrNull(16) ?: continue
                val end = parts[0].substringAfter("-").toLongOrNull(16) ?: continue
                val offset = parts[2].toLongOrNull(16) ?: continue
                val perm = parts.getOrNull(1) ?: ""
                if (lowest == 0L || start < lowest) lowest = start
                if (offset == 0L && base == 0L) base = start
                if (perm.contains("x")) exec.add(start until end)
            }
            if (base == 0L) base = lowest
            if (base == 0L && exec.isEmpty()) null else Maps(base, exec)
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------ 暂停信息

    private fun reasonOf(st: Stop, normal: String): String = when {
        st.signal != 0 && st.signal != 5 -> "signal"
        else -> normal
    }

    private fun noteFor(st: Stop, func: DwarfFunc?, line: Int): String {
        if (st.signal != 0 && st.signal != 5) return Loc.t("收到信号 %s", signalName(st.signal))
        if (line > 0) return ""
        if (func != null && func.declFile.isNotEmpty() && !func.declFile.endsWith(srcName)) {
            return Loc.t("停在 %s（%s，不在当前文件）", func.name, func.declFile.substringAfterLast('/'))
        }
        if (func != null) return Loc.t("停在 %s（这一行没有源码）", func.name)
        return Loc.t("当前指令没有对应的源码行（多半在库函数里）")
    }

    private fun signalName(sig: Int): String = when (sig) {
        2 -> "SIGINT"
        4 -> "SIGILL"
        5 -> "SIGTRAP"
        6 -> "SIGABRT"
        7 -> "SIGBUS"
        8 -> "SIGFPE"
        9 -> "SIGKILL"
        11 -> "SIGSEGV"
        13 -> "SIGPIPE"
        15 -> "SIGTERM"
        else -> "SIG$sig"
    }

    private fun buildPause(
        pc: Long,
        line: Int,
        func: String,
        vars: List<Pair<String, String>>,
        frames: List<Triple<String, String, Int>>,
        reason: String,
        note: String,
    ): String {
        val varsJson = JSONArray()
        vars.forEach { (n, v) -> varsJson.put(JSONObject().put("name", n).put("value", v)) }
        val stackJson = JSONArray()
        frames.forEach { (f, file, ln) ->
            stackJson.put(JSONObject().put("func", f).put("file", file).put("line", ln))
        }
        return JSONObject()
            .put("type", "pause")
            .put("reason", reason)
            .put("line", line)
            .put("func", func)
            .put("pc", "0x" + java.lang.Long.toHexString(pc))
            .put("note", note)
            .put("file", srcName)
            .put("vars", varsJson)
            .put("stack", stackJson)
            .toString()
    }

    // ------------------------------------------------------------ 变量 / 调用栈

    private fun compileRust(source: File, exe: File): Pair<Boolean, String> {
        val cmd = listOf(
            File(CToolchain.root, "bin/rustc").absolutePath,
            "--edition", "2021",
            "-g", "-C", "debuginfo=2", "-C", "opt-level=0",
            "-C", "linker=" + CToolchain.rustLinkerScript().absolutePath,
            "-o", exe.absolutePath,
            source.absolutePath,
        )
        val pb = ProcessBuilder(cmd)
        pb.directory(source.parentFile)
        pb.redirectErrorStream(true)
        pb.environment().putAll(CToolchain.toolEnv())
        val p = pb.start()
        val text = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        return (code == 0) to text
    }

    private fun evalVars(
        gdb: GdbClient,
        regs: LongArray,
        func: DwarfFunc?,
        line: Int,
    ): List<Pair<String, String>> {
        if (func == null) return emptyList()
        val frameBase = regs.getOrNull(func.frameBaseReg) ?: 0L
        val out = ArrayList<Pair<String, String>>()
        for (v in func.vars) {
            // 还没执行到声明处的变量不显示（否则看到的是栈上的垃圾值）
            if (line > 0 && v.declLine > line && (v.declFile.isEmpty() || v.declFile.endsWith(srcName))) continue
            try {
                if (v.location.kind == "reg") {
                    val value = regs.getOrNull(v.location.reg) ?: continue
                    out.add(v.name to java.lang.Long.toString(value))
                    continue
                }
                val addr = when (v.location.kind) {
                    "fbreg" -> frameBase + v.location.offset
                    "breg" -> (regs.getOrNull(v.location.reg) ?: continue) + v.location.offset
                    "addr" -> v.location.addr + base
                    else -> continue
                }

                val text = if (v.typeOffset != 0L) {
                    formatTyped(gdb, v.typeOffset, addr, 0)
                } else {
                    val size = sizeOf(v.typeName)
                    val bytes = gdb.readMemory(addr, size) ?: continue
                    formatValue(v.typeName, bytes)
                }
                out.add(v.name to text)
            } catch (_: Throwable) {
            }
        }
        return out
    }

    // -------- 结构化取值（结构体 / String / Vec / 数组） --------

    private fun formatTyped(gdb: GdbClient, typeOffset: Long, addr: Long, depth: Int): String {
        val info = dwarf ?: return "?"
        if (depth > 3) return "…"
        val t = info.typeAt(typeOffset) ?: return "?"
        return when (t.tag) {
            "DW_TAG_base_type" -> {
                val size = if (t.byteSize > 0) t.byteSize else 8
                val bytes = gdb.readMemory(addr, size) ?: return "?"
                formatValue(t.name, bytes)
            }
            "DW_TAG_pointer_type", "DW_TAG_reference_type" -> {
                val v = gdb.readLong(addr) ?: return "?"
                "0x" + java.lang.Long.toHexString(v)
            }
            "DW_TAG_typedef", "DW_TAG_const_type" -> formatTyped(gdb, t.ref, addr, depth)
            "DW_TAG_structure_type", "DW_TAG_class_type", "DW_TAG_union_type" -> {
                val vecType = info.typeAt(t.members.firstOrNull { it.name == "vec" }?.typeOffset ?: 0)
                when {
                    t.name.endsWith("String") && vecType != null -> {
                        val bytes = readVecBytes(gdb, info, vecType, addr + t.members.first { it.name == "vec" }.offset)
                        if (bytes == null) "?" else "\"" + escapeText(String(bytes, Charsets.UTF_8)) + "\""
                    }
                    t.name == "&str" || t.name.endsWith("::&str") -> readStrSlice(gdb, info, t, addr)
                    t.name.contains("Vec<") && !t.name.contains("RawVec") ->
                        readRustVec(gdb, info, t, addr)
                    t.name.startsWith("Option<") && t.members.isEmpty() -> "None"
                    else -> formatStruct(gdb, info, t, addr, depth)
                }
            }
            "DW_TAG_array_type" -> {
                val elem = info.typeAt(t.ref) ?: return "?"
                val elemSize = if (elem.byteSize > 0) elem.byteSize else 1
                val n = t.count.coerceIn(0, 16)
                val parts = ArrayList<String>()
                for (i in 0 until n) {
                    parts.add(formatTyped(gdb, t.ref, addr + i * elemSize, depth + 1))
                }
                "[" + parts.joinToString(", ") + "]"
            }
            else -> "?"
        }
    }

    private fun formatStruct(gdb: GdbClient, info: DwarfInfo, t: DwarfType, addr: Long, depth: Int): String {
        if (t.members.isEmpty()) return "{}"
        val parts = ArrayList<String>()
        for (m in t.members.take(8)) {
            val v = if (m.typeOffset != 0L) {
                formatTyped(gdb, m.typeOffset, addr + m.offset, depth + 1)
            } else {
                val raw = gdb.readLong(addr + m.offset) ?: 0L
                "0x" + java.lang.Long.toHexString(raw)
            }
            parts.add(m.name + ": " + v)
        }
        if (t.members.size > 8) parts.add("…")
        return "{ " + parts.joinToString(", ") + " }"
    }

    private fun untag(p: Long): Long = p and 0x00FFFFFFFFFFFFFL

    /** 在结构体（含嵌套成员）里按名字找字段偏移 */
    private fun findMemberOffset(info: DwarfInfo, t: DwarfType, name: String, base: Int = 0, depth: Int = 0): Int? {
        for (m in t.members) {
            if (m.name == name) return base + m.offset
            if (depth < 4) {
                val sub = info.typeAt(m.typeOffset)
                if (sub != null) {
                    val r = findMemberOffset(info, sub, name, base + m.offset, depth + 1)
                    if (r != null) return r
                }
            }
        }
        return null
    }

    /** 通过 DWARF 成员名字定位 Vec 的 ptr/len（Rust 会重排字段，不能按固定偏移读） */
    private fun vecPtrLen(gdb: GdbClient, info: DwarfInfo, t: DwarfType, addr: Long): Pair<Long, Long>? {
        val lenOff = findMemberOffset(info, t, "len") ?: return null
        val ptrOff = findMemberOffset(info, t, "ptr") ?: return null
        val ptr = gdb.readLong(addr + ptrOff) ?: return null
        val len = gdb.readLong(addr + lenOff) ?: return null
        return untag(ptr) to len
    }

    private fun readVecBytes(gdb: GdbClient, info: DwarfInfo, t: DwarfType, addr: Long): ByteArray? {
        val (ptr, len) = vecPtrLen(gdb, info, t, addr) ?: return null
        if (ptr == 0L || len <= 0 || len > 65536) return ByteArray(0)
        return gdb.readMemory(ptr, len.toInt())
    }

    private fun readStrSlice(gdb: GdbClient, info: DwarfInfo, t: DwarfType, addr: Long): String {
        val ptrOff = findMemberOffset(info, t, "data_ptr") ?: findMemberOffset(info, t, "ptr") ?: return "?"
        val lenOff = findMemberOffset(info, t, "length") ?: findMemberOffset(info, t, "len") ?: return "?"
        val ptr = untag(gdb.readLong(addr + ptrOff) ?: return "?")
        val len = gdb.readLong(addr + lenOff) ?: return "?"
        if (ptr == 0L || len <= 0 || len > 65536) return "\"\""
        val bytes = gdb.readMemory(ptr, len.toInt()) ?: return "\"\""
        return "\"" + escapeText(String(bytes, Charsets.UTF_8)) + "\""
    }

    private fun readRustVec(gdb: GdbClient, info: DwarfInfo, t: DwarfType, addr: Long): String {
        val (ptr, len) = vecPtrLen(gdb, info, t, addr) ?: return formatStruct(gdb, info, t, addr, 0)
        if (ptr == 0L || len <= 0) return "[]"
        val inner = t.name.substringAfter('<').substringBefore(',').substringBefore('>').trim()
        val elem = info.baseTypeByName(inner)
            ?: return "[len=" + len + "]"
        val n = minOf(len, 8L).toInt()
        val parts = ArrayList<String>()
        for (i in 0 until n) {
            val b = gdb.readMemory(ptr + i.toLong() * elem.byteSize, elem.byteSize) ?: break
            parts.add(formatValue(elem.name, b))
        }
        if (len > n) parts.add("…")
        return "[" + parts.joinToString(", ") + "]"
    }

    private fun escapeText(s: String): String =
        s.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"")

    private fun sizeOf(type: String): Int {
        if (type.endsWith("*")) return 8
        Regex("""\[\d+]""").find(type)?.let {
            return it.value.drop(1).dropLast(1).toIntOrNull() ?: 8
        }
        // Rust 基本类型
        when (type) {
            "i8", "u8", "bool", "char" -> return 1
            "i16", "u16" -> return 2
            "i32", "u32", "f32" -> return 4
            "i64", "u64", "isize", "usize", "f64" -> return 8
        }
        if (type.startsWith("&") || type.startsWith("*")) return 8
        return when {
            type.startsWith("double") || type.startsWith("long") -> 8
            type.startsWith("float") -> 4
            type.startsWith("short") -> 2
            type.startsWith("char") -> 1
            else -> 4
        }
    }

    private fun formatValue(type: String, bytes: ByteArray): String {
        var value = 0L
        for (i in 0 until minOf(bytes.size, 8)) {
            value = value or ((bytes[i].toLong() and 0xff) shl (8 * i))
        }
        if (type.endsWith("*")) return "0x" + java.lang.Long.toHexString(value)
        if (type.startsWith("char") && type.contains("[")) {
            val text = String(bytes, Charsets.UTF_8).trimEnd('\u0000')
            return "\"" + text.replace("\n", "\\n").replace("\"", "\\\"") + "\""
        }
        when (type) {
            "f32" -> return java.lang.Float.intBitsToFloat(value.toInt()).toString()
            "f64" -> return java.lang.Double.longBitsToDouble(value).toString()
            "bool" -> return if (value != 0L) "true" else "false"
        }
        if (type.startsWith("u")) return java.lang.Long.toUnsignedString(value)
        if (type.startsWith("double")) return java.lang.Double.longBitsToDouble(value).toString()
        if (type.startsWith("float")) return java.lang.Float.intBitsToFloat(value.toInt()).toString()
        return when (bytes.size) {
            1 -> bytes[0].toString()
            2 -> value.toShort().toString()
            4 -> value.toInt().toString()
            else -> value.toString()
        }
    }

    private fun walkFrames(
        gdb: GdbClient,
        regs: LongArray,
        base: Long,
    ): List<Triple<String, String, Int>> {
        val frames = ArrayList<Triple<String, String, Int>>()
        // pc / lr / 帧指针链可能指向同一个函数同一行，去重，避免调用栈出现重复帧
        fun add(f: Triple<String, String, Int>?) {
            if (f == null) return
            if (frames.isNotEmpty() && frames.last() == f) return
            frames.add(f)
        }
        val pc = regs.getOrNull(32) ?: return frames
        val topFunc = dwarf?.at(pc - base)
        add(frameOf(pc, base) ?: Triple("0x" + java.lang.Long.toHexString(pc), "", 0))
        // 叶子函数可能不建帧指针，x30 里是调用者的返回地址；
        // 但同一个函数内部的 x30 只是上次调用的残留，不能算成一帧
        val lr = regs.getOrNull(30) ?: 0L
        if (lr > 0) {
            val lrFunc = dwarf?.at(lr - base)
            if (lrFunc == null || lrFunc.low != (topFunc?.low ?: -1L)) add(frameOf(lr, base))
        }
        var fp = regs.getOrNull(29) ?: return frames
        var guard = 0
        while (guard++ < 12 && fp > 0x1000) {
            val ret = gdb.readLong(fp + 8) ?: break
            val nextFp = gdb.readLong(fp) ?: break
            if (ret == 0L) break
            // 只保留能落到已知函数里的帧，链条一旦跑偏就停
            val frame = frameOf(ret, base) ?: break
            add(frame)
            if (nextFp <= fp) break
            fp = nextFp
        }
        return frames
    }

    private fun frameOf(pc: Long, base: Long): Triple<String, String, Int>? {
        val rel = pc - base
        val func = dwarf?.at(rel) ?: return null
        val line = lineAt(rel)
        return Triple(func.name, srcName, line)
    }

    // ------------------------------------------------------------ 生命周期

    fun command(cmd: String) {
        sessionQueue?.offer(cmd)
    }

    fun stop() {
        running = false
        sessionQueue?.offer("k")
        // 让旧会话的收尾逻辑失效：它会以为自己还在前台，不能碰新会话的进程和 UI
        sessionId++
        try {
            client?.close()
        } catch (_: Throwable) {
        }
        cleanup()
    }

    private fun stopInferior(gdb: GdbClient) {
        try {
            gdb.send("k")
        } catch (_: Throwable) {
        }
    }

    private fun cleanup() {
        try {
            serverPfd?.close()
        } catch (_: Throwable) {
        }
        serverPfd = null
        if (serverPidRef > 0) {
            try {
                com.codepad.ide.term.Pty.kill(serverPidRef)
            } catch (_: Throwable) {
            }
            serverPidRef = 0
        }
        // gdbserver 被 kill 后，被调试进程可能还停在 ptrace-stopped 状态
        val ip = inferiorPid
        inferiorPid = 0
        if (ip > 0) {
            try {
                android.os.Process.sendSignal(ip, 9)
            } catch (_: Throwable) {
            }
        }
        client = null
    }
}
