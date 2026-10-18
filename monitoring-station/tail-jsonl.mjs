import { open } from 'node:fs/promises';

// Bound memory by bytes, not total log size. Ignore the partial first/last line
// while a producer is appending; never refuse an entire long-running journal.

/**
 * ★ 为什么要有这个函数（2026-10-04，第 5 组红线「历史 ≠ 当前」）：
 *   `readJsonlTail` 只返回事件数组，**调用方无从知道窗口截没截**。
 *   于是「这个文件里只有 2 条」与「这个文件有 10 万条、我只读了尾部 4000 条」
 *   在页面上长得一模一样 —— 两者都显示成「2 条」。
 *   这正是本工程反复踩的那条：「0（或者说小数字）有多种含义，
 *   而读的人只能看到其中一种」。
 *
 * 不改 `readJsonlTail` 的签名与行为（4 个既有调用方依赖它），
 * 只加一个把窗口事实一起带出来的变体。
 */
export async function readJsonlWindow(file, maxBytes = 4 * 1024 * 1024, maxEvents = 500) {
  const handle = await open(file, 'r');
  try {
    const { size } = await handle.stat();
    const start = Math.max(0, size - maxBytes);
    const buffer = Buffer.alloc(Math.min(size, maxBytes));
    const { bytesRead } = await handle.read(buffer, 0, buffer.length, start);
    let data = buffer.subarray(0, bytesRead);
    // 截掉开头那半行（生产者正在追加时它是不完整的）
    if (start > 0) {
      const newline = data.indexOf(10);
      data = newline < 0 ? Buffer.alloc(0) : data.subarray(newline + 1);
    }
    const lastNewline = data.lastIndexOf(10);
    data = lastNewline < 0 ? Buffer.alloc(0) : data.subarray(0, lastNewline + 1);
    const allLines = data.toString('utf8').split(/\r?\n/).filter(Boolean);
    const kept = allLines.slice(-maxEvents);
    const events = kept.map(line => {
      try { return JSON.parse(line); }
      catch { return { category: 'health', type: 'invalid_jsonl', data: { file } }; }
    });
    return {
      events,
      file,
      sizeBytes: size,
      windowBytes: data.length,
      // 字节窗口切掉了多少（0 = 整个文件都在窗口里）
      bytesDropped: start,
      // 窗口里一共多少行、留下了多少行（差值 = 按事件数上限丢掉的）
      linesInWindow: allLines.length,
      linesDropped: allLines.length - kept.length,
      // ★ 两个维度任一发生就是「截断了」—— 只报一个会让另一种静默
      truncated: start > 0 || allLines.length > kept.length,
      limits: { maxBytes, maxEvents },
    };
  } finally { await handle.close(); }
}

export async function readJsonlTail(file, maxBytes = 4 * 1024 * 1024, maxEvents = 500) {
  return (await readJsonlWindow(file, maxBytes, maxEvents)).events;
}
