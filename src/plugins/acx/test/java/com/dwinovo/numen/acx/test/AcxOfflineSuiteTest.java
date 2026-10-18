package com.dwinovo.numen.acx.test;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ★ 把 ACX 的 209 项离线自检接成 <b>真正的门禁</b>（2026-10-03 收口第 1 步）。
 *
 * <h3>为什么需要这个类</h3>
 * <p>{@code AcxTestMain}（3590+ 行 / 209 项检查）是一个 {@code main()} 手工台，
 * 靠 Gradle 的 {@code JavaExec}（{@code acxOfflineTest}）跑，而那个任务写着
 * {@code ignoreExitValue = true} ⇒ <b>它的失败不会让构建失败</b>，
 * 而且结果只打在控制台、<b>不落在测试报告里</b>。
 *
 * <p>后果是两条都很严重：
 * <ol>
 *   <li>本模块的 {@code :plugins:acx:test}（JUnit）<b>一个测试都没有</b>，
 *       Gradle 9 遇到「有测试源码但一个都没发现」直接<b>让任务失败</b>
 *       ⇒ 模块的 test 任务本来就是红的。</li>
 *   <li>更坏的是：任何「本模块测试全绿」的结论都<b>不包含这 217 项</b>
 *       —— 我们按惯例「从测试报告 XML 汇总数字」时永远看不到它。</li>
 * </ol>
 *
 * <h3>本类做的事</h3>
 * <p>在一个<b>子进程</b>里真跑那 217 项（必须子进程：{@code main} 结尾是
 * {@code System.exit}，同进程跑会把测试 JVM 一起带走），
 * 然后用 {@code -Dacx.resultFile} 让它写出机器可读的 JSON，再断言三件事：
 * <ol>
 *   <li><b>总数不许悄悄变</b>（少了说明有人删了检查项）。</li>
 *   <li><b>失败数必须恰好等于已知的 3 项</b> —— 多了说明出了新问题（这才是关键：
 *       原来的 {@code ignoreExitValue} 让新问题也照样静默）。</li>
 *   <li><b>那 3 项必须还是那 3 个已知缺陷</b>（见 {@link #KNOWN_RED_MARKERS}）——
 *       修好了要改这里，但<b>必须显式改</b>，不许它悄悄变红或悄悄变绿。</li>
 * </ol>
 *
 * <h3>已知红项的历史（7 → 3，全部是引擎真缺陷，不是测试写错）</h3>
 * <p><b>2026-10-06 修好 4 项</b>（测试断言逐字保留，只把登记数改小）：
 * <ul>
 *   <li>TODO(AC-B19)：取消后迟到的成功回执会把这次运行翻回 SUCCESS ⇒
 *       成功收下前复查取消标志（{@code AcxRunner.cancelled} 单源出口）。</li>
 *   <li>TODO(AC-B20)：「动作已受理、还在世界里跑」被记成 STEP_FAILED ⇒
 *       PAUSED 单发 {@code STEP_PAUSED} 并带上 {@code task_id} 等机器可读字段
 *       （实机旧读数：{@code STEP_FAILED|PAUSED = 53} vs {@code STEP_FAILED|FAIL = 29}）。</li>
 *   <li>TODO(AC-B21)：记录里没有指纹就整体绕过指纹门 ⇒ null 现在也是<b>拒绝</b>
 *       （验不了 ≠ 验过了）。</li>
 *   <li>TODO(AC-B22)：会话层续跑时从不重新解析 AC 定义 ⇒ 门面续跑前按名字重查
 *       「当前生效定义」再走校验链（改过没升版本的脚本会被指纹门拦下）。</li>
 * </ul>
 * <p><b>剩余 3 项</b>（未修，原因见各自测试注释）：
 * <ul>
 *   <li>TODO(AC-B12) ×2：{@code for} 循环跨断点的参数快照/游标尚未实现
 *       （需要一整块状态机特性：列表快照 + 迭代游标 + 轮内子步定位）。</li>
 *   <li>TODO(AC-B18)：恢复会重发已受理的动作 —— 与 {@code PortToolAdapter}
 *       的「挂账 + resume 时探针」设计强耦合（适配器正是靠引擎重执行该步来查账），
 *       修它需要先设计「已受理步」在记录里的表示，不能只挪 {@code markStepCompleted}。</li>
 * </ul>
 *
 * <h3>检查项数 211 → 217 的来历（2026-10-05 第二批接线）</h3>
 * <p>为了让「learner 复审出的 AC 草稿」有<b>真实下游</b>，新增
 * {@code AcxArtifactAdopter}（扫 outbox → 解析 → 校验 → 发布为 {@code GENERATED} → 回写状态）
 * 及配套 6 项检查：采纳成功、拒收散文、坏 JSON、重复采纳幂等、状态回写、不越权上线。
 * <p>★ 这 6 项<b>全绿</b>；红项随后从原来 7 个修到 3 个（见上）。
 * 只有「总数」这一条被显式从 211 改成 217。<b>总数是硬断言，涨了必须显式改这里</b>，
 * 这正是它存在的意义：有人加检查项时会被迫承认，覆盖度变化不会静默通过。
 */
class AcxOfflineSuiteTest {

    /**
     * 已知红项数。修好其中任何一个都可以改小，但必须同时改下面的判定片段。
     *
     * <p>2026-10-06 从 7 改为 3（修好 AC-B19/B20/B21/B22，见类注释）。
     * 剩余三个已知缺陷：for/断点的两个（TODO AC-B12）、恢复重发已受理动作（TODO AC-B18）。
     */
    private static final int KNOWN_RED = 3;

    /**
     * 已知的 3 项红的判定片段 —— <b>刻意全用 ASCII</b>。
     *
     * <p>对应关系（判定只匹配<b>测试名</b>，分组名不参与，见 {@link #testNameOf}）：
     * <ul>
     *   <li>{@code "for"} → {@code for 循环；断点 pause 后 resume 看不见变量元组}</li>
     *   <li>{@code "64"}  → {@code 应绑定的 64 变量记录表}（同属 for/断点那个缺陷）</li>
     *   <li>{@code "AC-B18"} → {@code 恢复不能重复发已受理的动作（TODO AC-B18，当前会重发）}</li>
     * </ul>
     *
     * <p>★ <b>为什么不用中文</b>：本工程在 Windows 上改含中文的文件时，
     * PowerShell 的码点构造与控制台回读<b>都不可信</b>（实测同一个「应绑定」
     * 在两处显示成不同的码点），而判定词一旦写错就会<b>静默失去承重作用</b> ——
     * 同一组里任何新的失败都会被当成已知缺陷放过。ASCII 片段写盘读盘都不可能被代码页弄坏。
     *
     * <p>⚠️ <b>代价（必须知道）</b>：{@code "64"} <b>不够唯一</b>。
     * 将来若出现名字里带「64」的新失败项，它会被误判成已知缺陷。
     * ⇒ 那种情况要<b>显式改这里</b>，别默默放过。
     */
    private static final String[] KNOWN_RED_MARKERS = {"for", "64", "AC-B18"};

    @Test
    void theOfflineSuiteRunsAndItsKnownRedStaysVisible() throws Exception {
        // 与 Gradle 的 acxOfflineTest 用同一个工作目录：套件是按 ./ac-lib 找脚本库的
        Path workDir = Path.of("build", "acx-fixture").toAbsolutePath();
        assertTrue(Files.isDirectory(workDir),
                "找不到 acx-fixture=" + workDir + "；test 任务必须 dependsOn acxFixture，否则测的是不存在的东西");

        Path result = Files.createTempFile("acx-result", ".json");

        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("-Dacx.resultFile=" + result.toAbsolutePath());
        cmd.add("com.dwinovo.numen.acx.test.AcxTestMain");

        // ★★★ 输出必须重定向到**文件**，不能走管道。
        //   我第一版写的是 redirectErrorStream(true) + 退出后才 readAllBytes()
        //   ⇒ 经典的管道缓冲死锁：子进程把 64KB 管道写满就阻塞在 write，
        //   而我在等它退出才去读 ⇒ 双方互等。
        //   实测症状：子进程只烧了 1 秒 CPU 却 500 秒不退出（卡住，不是慢）。
        //   209 项每项一行输出早就超过 64KB，所以这个死锁是必然的，不是偶发。
        //   重定向到文件没有缓冲区上限，代价是要落一个临时文件（跑完删掉）。
        Path outFile = Files.createTempFile("acx-out", ".log");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(outFile.toFile());

        Process proc = pb.start();
        // 时限按实测给，不按直觉给：同一套件经 gradle JavaExec 跑约 20 秒，
        // 这里是在 JUnit 内部再起一个 JVM（多一层 classpath 扫描 + 无 daemon 复用）。
        boolean finished = proc.waitFor(300, TimeUnit.SECONDS);
        // ⚠️ 输出文件按**字节**读，不按字符读。
        //   子进程的 stdout 走 Windows 控制台代码页（GBK），而 readString(UTF_8) 遇到
        //   非法字节序列会抛 MalformedInputException —— 我第一版就踩了（"Input length = 1"）。
        //   而这份输出**只用于失败时给人看**，机器判据一律走 -Dacx.resultFile 那个 JSON。
        //   所以这里不解释编码、按 ISO-8859-1 无损映射成字符即可，绝不能让它把测试搞挂。
        String out = Files.isRegularFile(outFile)
                ? new String(Files.readAllBytes(outFile), StandardCharsets.ISO_8859_1)
                : "";
        if (!finished) {
            // 超时必须杀掉，否则子进程会一直挂在后台（今天已经吃过一次「后台进程残留」的亏）
            proc.destroyForcibly();
            Files.deleteIfExists(result);
            fail("ACX 离线自检 300 秒没跑完。子进程已杀掉，结果文件已清理。\n"
                    + "它不该需要这么久 —— 这通常是死锁而不是变慢。输出尾部：\n" + tail(out));
        }
        // 临时输出文件用完即删（它是诊断用的，不该留在 build 目录里变成第二份历史）
        Files.deleteIfExists(outFile);
        // 子进程退出码不可信：AcxTestMain 结尾是 System.exit(T.summary())，
        //   而 summary() 只在「零失败」时返 0 ⇒ 有已知红项时退出码就是 1。
        //   所以判据走结果文件，不走退出码（这一点写在这里，免得下一个人又去信退出码）。

        assertTrue(Files.isRegularFile(result),
                "自检没有写出结果文件，说明它没跑到 summary() 就退出了。输出尾部：\n" + tail(out));

        String json = Files.readString(result, StandardCharsets.UTF_8);
        int total = intField(json);
        int failed = failedField(json);

        // ① 总数不许悄悄变 —— 有人删检查项时这里会红
        //   2026-10-09：217 → 219。新增 2 项都是 `$pick=nearest` 的 `$origin` 解析
        //   （裸 {x,y,z} 必须能按距离选；默认字段写法仍有效）。这是修「引羊 AC 选错目标」
        //   那条引擎缺陷时补的回归，属于**有意的覆盖度增加**，不是被删项。
        assertEquals(219, total, "离线自检的检查项数变了。少项多半是有人删了检查项，"
                + "那等于把覆盖度悄悄拿走了。结果文件：" + json);

        // ② ★ 最关键的一条：失败数必须「恰好」是已知的 7 项。
        //    原来 ignoreExitValue=true 的后果就是：新问题也一样静默。
        assertEquals(KNOWN_RED, failed,
                "离线自检的失败数不是已知的 " + KNOWN_RED + " 项。"
                        + "多了 = 出了新问题（这正是原来被静默掉的那类）；"
                        + "少了 = 某个已知缺陷被修好了或被跳过了，两种都要显式处理。\n"
                        + "失败明细：" + failures(json) + "\n结果文件：" + json);

        // ③ 那 7 项必须还是那 7 个已知缺陷
        //    （for/断点 ×2 + 重复发 ×1 + 迟到回执 ×1 + 在飞动作记成失败 ×1
        //      + 没有指纹绕门 ×1 + 会话层不重新解析定义 ×1）
        //
        // ★★ 判定必须**只看测试名**，不能看整条文本。
        //   变异验证抓出来的：早先这里匹配的是整条失败文本，而失败文本的格式是
        //   `group + " / " + name + "  →  " + e` —— 于是**分组名也参与判定**。
        //   新缺陷那条在第 7 组「7 断点续跑契约」里，文本含「断点」二字，
        //   于是它被 {"for","断点"} 认成了那个 for/断点缺陷 ⇒ 我新加的判定词根本不承重，
        //   而且**同一组里以后任何新的失败都会被静默放过** ——
        //   而「新问题静默」正是这轮收口要消灭的东西。
        //   ⇒ 现在只取最后一个 " / " 之后、"  →  " 之前那截（就是测试名）来匹配。
        List<String> fails = failures(json);
        assertEquals(KNOWN_RED, fails.size(), "失败条数与 failed 字段不一致：" + json);
        for (String f : fails) {
            String name = testNameOf(f);
            boolean isTheKnownOne = false;
            for (String marker : KNOWN_RED_MARKERS) {
                if (name.contains(marker)) {
                    isTheKnownOne = true;
                }
            }
            assertTrue(isTheKnownOne,
                    "出现了一条不是已知缺陷的失败项：\n  测试名 = " + name + "\n  整条 = " + f
                            + "\n★ 已知的缺陷只剩这些：「for 循环里的变量跨断点看不见」（TODO AC-B12 ×2）"
                            + "、「恢复会重发已受理的动作」（TODO AC-B18）。"
                            + "（AC-B19/B20/B21/B22 已于 2026-10-06 修复并转绿。）"
                            + "新的失败项要先当真问题查，别直接改 KNOWN_RED。");
        }
    }

    /**
     * 从失败文本里取出<b>测试名</b>（不含分组名）。
     *
     * <p>格式由 {@code T.test} 决定：{@code group + " / " + name + "  →  " + e}。
     * 取<b>最后一个</b> {@code " / "} 之后、{@code "  →  "} 之前那截。
     *
     * <p>★ 为什么必须剥掉分组名：分组名里常有「断点」这种词，会让判定词误命中 ——
     * 那是变异验证实测出来的（去掉新加的判定词后仍然全绿）。
     */
    private static String testNameOf(String failureText) {
        int arrow = failureText.indexOf("  →  ");
        String head = arrow < 0 ? failureText : failureText.substring(0, arrow);
        int slash = head.lastIndexOf(" / ");
        return slash < 0 ? head : head.substring(slash + 3);
    }

    // ---- 极简 JSON 取值（这机器可读文件是我们自己写的，形状固定，不必引依赖） ----

    private static int intField(String json) {
        return readInt(json, "\"total\":", "total");
    }

    private static int failedField(String json) {
        return readInt(json, "\"failed\":", "failed");
    }

    /**
     * 从固定形状的结果 JSON 里取一个整数字段。
     *
     * <p>★ 缺字段就抛，<b>不返 0</b> ——「0」与「没有」长得一样是本工程反复踩的坑
     * （见 60 号 §6.2「0 的三种含义」）。取不到就崩，比给个假的 0 好。
     */
    private static int readInt(String json, String key, String label) {
        int i = json.indexOf(key);
        if (i < 0) {
            throw new AssertionError("结果文件里没有 " + label + " 字段：" + json);
        }
        int from = i + key.length();
        int to = from;
        while (to < json.length() && Character.isDigit(json.charAt(to))) {
            to++;
        }
        if (to == from) {
            throw new AssertionError("结果文件里 " + label + " 后面不是数字：" + json);
        }
        return Integer.parseInt(json.substring(from, to));
    }

    /** 取出 failures 数组里的每一条（够用即可：这是我们自己写的固定形状）。 */
    private static List<String> failures(String json) {
        List<String> out = new ArrayList<>();
        int start = json.indexOf("\"failures\":[");
        if (start < 0) {
            return out;
        }
        int end = json.indexOf(']', start);
        if (end < 0) {
            return out;
        }
        String body = json.substring(start + 12, end);
        for (String part : body.split("\",\"")) {
            String s = part.replace("[\"", "").replace("\"", "").replace("\\\\", "\\");
            if (!s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    private static String tail(String s) {
        return s.length() <= 1200 ? s : s.substring(s.length() - 1200);
    }
}
