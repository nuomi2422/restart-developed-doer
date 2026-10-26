// semantics.test.mjs —— /api/rdd-semantics 的纯模块测试（P0-C 判据的离线部分）
//
// 判据（对应 81 号 §6）：
//   C1 逐条一致：11 条就是 11 条，字段不丢；
//   C2 不串伴：两个同伴各自计数，切谁看谁；
//   C3 四态分开：目录缺失 / 空目录 / 坏 JSON / 正常，不许都显示成 0 条；
//   C4 来源缺失 = UNKNOWN_SOURCE（不美化成“派生”）。
import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, mkdir, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createSemanticsView } from "../semantics.mjs";

async function withConfig(fn) {
  const root = await mkdtemp(join(tmpdir(), "rdd-sem-"));
  try { return await fn(root); } finally { await rm(root, { recursive: true, force: true }); }
}

const E = (k, extra) => ({ assetKey: k, importance: "IMPORTANT", nature: "EQUIPMENT", servesGoal: "goal-1", source: "derived:requirement_manifest", substitutes: [], ...(extra || {}) });

test("c1 11 条就是 11 条，字段逐条带出", async () => {
  await withConfig(async (root) => {
    const dir = join(root, "rdd-semantics");
    await mkdir(dir, { recursive: true });
    const entries = Array.from({ length: 11 }, (_, i) => E("minecraft:item_" + i));
    await writeFile(join(dir, "comp-a.json"), JSON.stringify(entries), "utf8");
    const data = await createSemanticsView(root).all();
    assert.equal(data.state, "OK");
    assert.equal(data.totalCount, 11);
    assert.equal(data.companions.length, 1);
    assert.equal(data.companions[0].entries.length, 11);
    assert.equal(data.companions[0].entries[0].assetKey, "minecraft:item_0");
    assert.equal(data.companions[0].entries[0].importance, "IMPORTANT");
  });
});

test("c2 两个同伴各自计数，不串", async () => {
  await withConfig(async (root) => {
    const dir = join(root, "rdd-semantics");
    await mkdir(dir, { recursive: true });
    await writeFile(join(dir, "comp-a.json"), JSON.stringify(Array.from({ length: 11 }, (_, i) => E("a:" + i))), "utf8");
    await writeFile(join(dir, "comp-b.json"), JSON.stringify(Array.from({ length: 3 }, (_, i) => E("b:" + i))), "utf8");
    const data = await createSemanticsView(root).all();
    assert.equal(data.totalCount, 14);
    const byId = Object.fromEntries(data.companions.map((c) => [c.companionId, c.count]));
    assert.equal(byId["comp-a"], 11);
    assert.equal(byId["comp-b"], 3);
  });
});

test("c3 目录缺失 / 空目录 / 坏 JSON 是三种不同状态", async () => {
  await withConfig(async (root) => {
    const missing = await createSemanticsView(root).all();
    assert.equal(missing.state, "NO_DIRECTORY");

    await mkdir(join(root, "rdd-semantics"), { recursive: true });
    const empty = await createSemanticsView(root).all();
    assert.equal(empty.state, "NO_FILES");

    await writeFile(join(root, "rdd-semantics", "bad.json"), "{not json", "utf8");
    const broken = await createSemanticsView(root).all();
    assert.equal(broken.state, "BROKEN");
    assert.equal(broken.companions[0].state, "CORRUPT");
    assert.match(broken.companions[0].note, /bad json/);
    assert.equal(broken.totalCount, 0, "坏文件不许冒充 0 条资产");
  });
});

test("c4 缺 source 的条目标为来源未知，不美化成派生", async () => {
  await withConfig(async (root) => {
    const dir = join(root, "rdd-semantics");
    await mkdir(dir, { recursive: true });
    const e1 = E("minecraft:iron_chestplate");
    const e2 = E("minecraft:white_bed", { source: "" });
    await writeFile(join(dir, "comp-a.json"), JSON.stringify([e1, e2]), "utf8");
    const data = await createSemanticsView(root).all();
    assert.equal(data.companions[0].unknownSourceCount, 1);
    assert.match(data.companions[0].note, /UNKNOWN_SOURCE/);
    assert.equal(data.companions[0].entries[1].source, "");
  });
});

test("c5 坏文件与好文件共存 = PARTIAL，好文件仍逐条可读", async () => {
  await withConfig(async (root) => {
    const dir = join(root, "rdd-semantics");
    await mkdir(dir, { recursive: true });
    await writeFile(join(dir, "good.json"), JSON.stringify([E("x:1")]), "utf8");
    await writeFile(join(dir, "bad.json"), "[]notarray", "utf8");
    const data = await createSemanticsView(root).all();
    assert.equal(data.state, "PARTIAL");
    assert.equal(data.totalCount, 1);
  });
});
