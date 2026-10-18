package com.dwinovo.numen.plugins.camera.rec;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 录像：把「帧序列」落成 PNG 序列，再合成 mp4。
 *
 * <h2>为什么不复用 OpenClaw 的 record action</h2>
 * 那个 action（2026-09-30 实测）有两个真问题，本模块的设计就是绕开它们：
 * <ol>
 *   <li><b>文件落下时没有扩展名</b>。原版 {@code Screenshot.grab(dir, name, …)} 在
 *       {@code name != null} 时直接 {@code new File(screenshots, name)}，<b>不补
 *       {@code .png}</b>（见 {@code Screenshot._grab}）—— 所以那 12 个
 *       {@code frame_1790750568836_0} 用任何 {@code *.png} 通配都搜不到，看上去像
 *       「一张都没存」。本模块的文件名<b>自己带 {@code .png}</b>，不依赖那个分支。</li>
 *   <li><b>每帧之间世界不 tick</b>。它把 N 个任务排进 {@code mc.execute}，而命令本身
 *       已经在主线程任务里 → N 个任务被<b>同一次</b> {@code runAllTasks()} 排空，每个
 *       任务里的 {@code Thread.sleep(gap)} 真把渲染主线程堵住。磁盘证据：那 6 帧的
 *       字节数<b>完全相同</b>。</li>
 * </ol>
 *
 * <h2>本模块怎么做</h2>
 * <ul>
 *   <li>用 <b>tick 驱动</b>，每 N tick 抓一帧 —— 世界照常 tick，帧与帧之间内容会变。</li>
 *   <li>GL 读回放进 {@link RenderSystem#recordRenderCall}，编码写盘丢给
 *       {@link Util#ioPool()}：渲染线程只做读回，PNG 压缩在 IO 线程，不卡画面。</li>
 *   <li>每帧抽 <b>8×8 个采样点</b>算哈希；和上一帧<b>完全一样</b>就不写盘（静态画面
 *       存 900 份纯浪费）。这一条直接针对上面第 2 个问题留下的痕迹。</li>
 *   <li>{@code NativeImage.close()} 放在 IO 任务的 finally 里 —— 关早了会读到已释放的
 *       内存。这是这类代码最容易踩的泄漏/崩溃点。</li>
 * </ul>
 *
 * <h2>合 mp4</h2>
 * MC 1.21.1 <b>没有</b>任何 FFmpeg/GifEncoder 类（原生 sources jar 里 FFmpeg、
 * GifEncoder、MoviePlayer 一个都没有）。所以合 mp4 靠外部 ffmpeg：本机装了
 * WinGet 的 8.1.1，找不到就只留 PNG 序列并把路径告诉用户（PNG 序列 ffmpeg 随时能补）。
 */
public final class VideoRecorder {

    /** 默认帧率。 */
    private static final int DEFAULT_FPS = 10;

    /** 默认最长帧数（10fps → 90 秒）。防「忘了停」把磁盘写满。 */
    private static final int DEFAULT_MAX_FRAMES = 900;

    /** 采样网格 8×8。 */
    private static final int SAMPLE_GRID = 8;

    private static volatile boolean recording;
    private static volatile int requestedFps = DEFAULT_FPS;
    private static volatile int maxFrames = DEFAULT_MAX_FRAMES;
    private static volatile File dir;

    private static volatile int intervalTicks;
    private static int tickCounter;
    private static volatile int frameIndex;
    private static volatile int pendingWrites;
    private static volatile int written;
    private static volatile int dropped;
    private static volatile int failed;

    /**
     * 落盘文件名用的<b>连续</b>序号，和 {@link #frameIndex}（抓帧计数）分开。
     *
     * <p><b>为什么必须分开</b>：ffmpeg 的 image2 解码器把 {@code f_%05d.png} 当成
     * 「从 00000 起连续递增」来读，<b>遇到第一个缺口就停</b>。2026-10-04 实测：
     * 抓了 82 次、去重后落盘 12 张（序号 0,10,11,…,68），ffmpeg 只编出
     * <b>1 帧</b> mp4。所以文件名不能用「抓帧序号」，要用「落盘计数」。
     */
    private static volatile int outIndex;

    /**
     * 画面完全静止时跳过一帧（省磁盘）。
     *
     * <p><b>默认关</b>：录视频要的是连续时间轴，站着不动也是一段真实画面。
     * 2026-10-04 首版默认开着，8 秒 10fps 只落 12 张（去重 70），出来的「视频」
     * 是 1.2 fps 的定格动画 —— 那是把「省磁盘」的默认值用错了地方。
     */
    private static volatile boolean dedupe;

    /** IO 线程池是多线程，所以这几个计数都必须是 volatile（否则丢帧/重复封盘）。 */
    private static volatile int lastSampleHash;
    private static volatile boolean haveLastHash;

    /** 没在封盘。 */
    private static final int SEAL_NONE = 0;
    /** 已停录，在途 IO 未排空。 */
    private static final int SEAL_WAIT_IO = 1;
    /** PNG 已落盘，ffmpeg 子进程在跑。 */
    private static final int SEAL_WAIT_FFMPEG = 2;

    private static volatile int sealPhase = SEAL_NONE;
    private static volatile Process ffmpegProc;

    private static volatile long startMs;
    private static volatile String lastResult = "还没录过。/cam rec start [fps]";

    private VideoRecorder() {
    }

    public static boolean recording() {
        return recording;
    }

    public static String status() {
        if (!recording && frameIndex == 0) {
            return lastResult;
        }
        long secs = frameIndex == 0 ? 0 : Math.max(1, (System.currentTimeMillis() - startMs) / 1000);
        String head = sealPhase != SEAL_NONE ? "○ 封盘中" : (recording ? "● 录像中" : "○ 已停");
        return String.format(Locale.ROOT,
                "%s 帧率=%d 帧=%d/%d 落盘=%d 去重=%d 失败=%d 在途=%d 已录=%ds",
                head, requestedFps, frameIndex, maxFrames,
                written, dropped, failed, pendingWrites, secs);
    }

    public static String start(int fps) {
        return start(fps, false);
    }

    public static String start(int fps, boolean dedupe) {
        if (recording || sealPhase != SEAL_NONE) {
            return "已经在录了（或正在封盘）。" + status();
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getMainRenderTarget() == null) {
            return "还没进世界（没有主渲染目标），录不了。";
        }
        requestedFps = Math.max(1, Math.min(30, fps));
        intervalTicks = Math.max(1, Math.round(20.0f / requestedFps));
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
        File d = new File(new File(mc.gameDirectory, "screenshots"), "numencam_" + stamp);
        if (!d.isDirectory() && !d.mkdirs()) {
            return "建不了目录：" + d.getAbsolutePath();
        }
        dir = d;
        frameIndex = 0;
        outIndex = 0;
        pendingWrites = 0;
        written = 0;
        dropped = 0;
        failed = 0;
        haveLastHash = false;
        VideoRecorder.dedupe = dedupe;
        tickCounter = 0;
        startMs = System.currentTimeMillis();
        recording = true;
        lastResult = "开始录像。";
        return String.format(Locale.ROOT,
                "开始录像：%d fps（每 %d tick 一帧），上限 %d 帧，去重=%s → %s",
                requestedFps, intervalTicks, maxFrames, dedupe ? "开" : "关", d.getAbsolutePath());
    }

    /** 停录。真正封盘（等 IO 排空 + 合成 mp4）在 tick 里分步做，这里只翻标志。 */
    public static String stop() {
        if (!recording) {
            return "没在录。" + status();
        }
        recording = false;
        sealPhase = SEAL_WAIT_IO;
        return "已停，等最后 " + pendingWrites + " 帧写完就自动封盘（看 /cam rec status）。";
    }

    public static String setMaxFrames(int n) {
        maxFrames = Math.max(1, Math.min(5000, n));
        return "单次上限 = " + maxFrames + " 帧";
    }

    public static void tick() {
        if (!recording) {
            if (sealPhase != SEAL_NONE) {
                stepSeal();
            }
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getMainRenderTarget() == null) {
            recording = false;
            sealPhase = SEAL_NONE;
            lastResult = "录像中断：退出世界了。";
            return;
        }
        if (frameIndex >= maxFrames) {
            recording = false;
            sealPhase = SEAL_WAIT_IO;
            return;
        }
        if (++tickCounter < intervalTicks) {
            return;
        }
        tickCounter = 0;
        capture(mc);
    }

    private static void capture(Minecraft mc) {
        frameIndex++;
        pendingWrites++;
        try {
            RenderSystem.recordRenderCall(() -> {
                NativeImage img;
                try {
                    img = Screenshot.takeScreenshot(mc.getMainRenderTarget());
                } catch (Throwable t) {
                    failed++;
                    pendingWrites--;
                    return;
                }
                Util.ioPool().execute(() -> {
                    try {
                        int h = sampleHash(img);
                        if (dedupe && haveLastHash && h == lastSampleHash) {
                            dropped++;   // 画面一动没动，别浪费一张 PNG（默认关，见 dedupe 字段注）
                        } else {
                            // 文件名用落盘计数 outIndex，保证 f_00000,f_00001,... 连续无缺口。
                            File target = new File(dir, String.format(Locale.ROOT, "f_%05d.png", outIndex++));
                            img.writeToFile(target);
                            written++;
                        }
                        lastSampleHash = h;
                        haveLastHash = true;
                    } catch (IOException | RuntimeException ex) {
                        failed++;
                    } finally {
                        // 必须在这里关：writeToFile 要读这块 native 内存，提前 close 会读到已释放的指针。
                        img.close();
                        pendingWrites--;
                    }
                });
            });
        } catch (RuntimeException e) {
            pendingWrites--;
            failed++;
        }
    }

    /**
     * 抽 8×8 个点算个廉价指纹。
     *
     * <p>为什么不逐像素哈希：{@code getPixelRGBA} 每像素一次 JNI 调用，1280×720 就是
     * 92 万次，纯粹为了判重不值得（实测这类逐像素回读单帧就要几十毫秒，直接把录像
     * 变成掉帧）。64 个采样点足够抓住「整个画面完全静止」这个真正要治的情况，
     * 代价可以忽略。
     */
    private static int sampleHash(NativeImage img) {
        int h = 0;
        int w = img.getWidth();
        int hgt = img.getHeight();
        for (int gy = 0; gy < SAMPLE_GRID; gy++) {
            for (int gx = 0; gx < SAMPLE_GRID; gx++) {
                int x = Math.min(w - 1, (w - 1) * gx / SAMPLE_GRID);
                int y = Math.min(hgt - 1, (hgt - 1) * gy / SAMPLE_GRID);
                h = h * 31 + img.getPixelRGBA(x, y);
            }
        }
        return h;
    }

// ── 封盘（异步，绝不在客户端线程上等 ffmpeg）────────────────────────────

    /**
     * 封盘三步，每步都<b>不阻塞</b>：
     * <ol>
     *   <li>等在途 IO 写盘排空（轮询，不 sleep）</li>
     *   <li>写 numencam.txt 元数据</li>
     *   <li>把 ffmpeg 丢到独立守护线程上跑，客户端继续玩</li>
     * </ol>
     *
     * <p><b>为什么不能直接 waitFor</b>：ffmpeg 编码 900 帧 720p 要好几秒，客户端线程
     * 被卡住就是「游戏冻住」。用户看到「录像把游戏卡死了」会直接把这功能删了，
     * 而卡死的原因只是我在等一个外部进程。
     */
    private static void stepSeal() {
        File d = dir;
        if (d == null) {
            sealPhase = SEAL_NONE;
            return;
        }
        switch (sealPhase) {
            case SEAL_WAIT_IO -> {
                if (pendingWrites > 0) {
                    return;     // 还在写，下一 tick 再看
                }
                writeMeta(d);
                File exe = locateFfmpeg();
                if (exe == null) {
                    sealPhase = SEAL_NONE;
                    lastResult = "封盘完成。" + summary(d)
                            + "\n⚠ 没找到 ffmpeg，只留了 PNG 序列。装好 ffmpeg 后自己合："
                            + "\n  ffmpeg -y -framerate " + requestedFps
                            + " -i \"" + new File(d, "f_%05d.png").getAbsolutePath() + "\""
                            + " -c:v libx264 -pix_fmt yuv420p"
                            + " -vf scale=trunc(iw/2)*2:trunc(ih/2)*2 \"" + new File(d, "numencam.mp4").getAbsolutePath() + "\"";
                    return;
                }
                launchFfmpeg(exe, d);
                sealPhase = SEAL_WAIT_FFMPEG;
            }
            case SEAL_WAIT_FFMPEG -> {
                Process p = ffmpegProc;
                if (p != null && p.isAlive()) {
                    lastResult = "封盘完成（PNG 已全部落盘），ffmpeg 合成 mp4 中… " + summary(d);
                    return;
                }
                File mp4 = new File(d, "numencam.mp4");
                boolean ok = mp4.isFile() && mp4.length() > 0;
                sealPhase = SEAL_NONE;
                lastResult = ok
                        ? "封盘完成。mp4 = " + mp4.getAbsolutePath()
                          + String.format(Locale.ROOT, "（%.1f MB）", mp4.length() / 1048576.0) + summary(d)
                        : "封盘完成，但 mp4 没合成出来（看 " + new File(d, "ffmpeg.log").getName()
                          + "）。PNG 序列还在：" + summary(d);
                frameIndex = 0;
            }
            default -> {
                // SEAL_NONE：什么都不做，等下一次 start
            }
        }
    }

    private static String summary(File d) {
        return String.format(Locale.ROOT, " ｜ 帧率=%d 落盘=%d 去重=%d 失败=%d 目录=%s",
                requestedFps, written, dropped, failed, d.getAbsolutePath());
    }

    private static void writeMeta(File d) {
        long secs = Math.max(1, (System.currentTimeMillis() - startMs) / 1000);
        try {
            Files.writeString(d.toPath().resolve("numencam.txt"),
                    "fps=" + requestedFps + "\nframes=" + written + "\ndropped=" + dropped
                            + "\nfailed=" + failed + "\nduration_s=" + secs + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 元数据写不了不影响视频本身
        }
    }

    /** 在独立守护线程上跑 ffmpeg；进程句柄存起来给 tick 轮询。 */
    private static void launchFfmpeg(File exe, File d) {
        File out = new File(d, "numencam.mp4");
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.getAbsolutePath());
        cmd.add("-y");
        cmd.add("-framerate");
        cmd.add(String.valueOf(requestedFps));
        cmd.add("-i");
        cmd.add(new File(d, "f_%05d.png").getAbsolutePath());
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
        // yuv420p 要求宽高都是偶数，缩到最近的偶数，否则 libx264 直接报错。
        cmd.add("-vf");
        cmd.add("scale=trunc(iw/2)*2:trunc(ih/2)*2");
        cmd.add(out.getAbsolutePath());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File(d, "ffmpeg.log"));
        Thread t = new Thread(() -> {
            try {
                ffmpegProc = pb.start();
            } catch (IOException e) {
                ffmpegProc = null;
            }
        }, "numencam-ffmpeg-launch");
        t.setDaemon(true);
        t.start();
    }

    /** 先找 PATH 上的 ffmpeg，再找本机已知的 WinGet 安装位置。 */
    private static File locateFfmpeg() {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(";")) {
                File f = new File(dir, "ffmpeg.exe");
                if (f.isFile()) {
                    return f;
                }
                File f2 = new File(dir, "ffmpeg");
                if (f2.isFile()) {
                    return f2;
                }
            }
        }
        File winget = new File(System.getProperty("user.home"),
                "AppData/Local/Microsoft/WinGet/Packages");
        File[] pkgs = winget.listFiles();
        if (pkgs != null) {
            for (File p : pkgs) {
                if (!p.getName().contains("FFmpeg")) {
                    continue;
                }
                // 版本号会变（8.x / 7.x / 未来 9.x），所以扫子目录而不是写死版本。
                File bin = new File(p, "bin");
                File[] kids = bin.listFiles();
                if (kids != null) {
                    for (File k : kids) {
                        if (k.isDirectory()) {
                            File exe = new File(k, "ffmpeg.exe");
                            if (exe.isFile()) {
                                return exe;
                            }
                            File[] deep = k.listFiles();
                            if (deep != null) {
                                for (File b2 : deep) {
                                    File exe2 = new File(b2, "ffmpeg.exe");
                                    if (exe2.isFile()) {
                                        return exe2;
                                    }
                                }
                            }
                        }
                        File exe3 = new File(k, "ffmpeg.exe");
                        if (exe3.isFile()) {
                            return exe3;
                        }
                    }
                }
            }
        }
        return null;
    }
}