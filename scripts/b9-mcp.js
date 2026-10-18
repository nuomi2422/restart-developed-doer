#!/usr/bin/env node
/**
 * b9-mcp.js — 取实机证据用的一次性 MCP 驱动（B6/B7/B8）。
 * 用法: node b9-mcp.js <tool> [jsonFile]
 * 为什么不 inline 传 JSON：PS 5.1 会把 '{...}' 的引号吃掉（B9 实测报
 * "Expected property name or '}' at position 1"）。jsonFile 走文件不过 shell。
 */
'use strict';
const { execFileSync } = require('child_process');
const fs = require('fs');
const HERE = __dirname;
const CLI = HERE + '/numen-mcp.js';

const tool = process.argv[2];
if (!tool) { console.error('usage: node b9-mcp.js <tool> [jsonFile]'); process.exit(2); }
console.log('>>> ' + tool);
const out = execFileSync(process.execPath, [CLI, 'call', tool, ...(process.argv[3] ? [fs.readFileSync(process.argv[3], 'utf8').trim()] : [])], {
  encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, timeout: 180000,
});
console.log(typeof out === 'string' ? out : String(out));