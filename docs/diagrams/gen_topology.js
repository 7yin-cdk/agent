/* 数据库运维 Agent —— 硬件拓扑与配置要求（蓝图纸 / 网络拓扑风格）
 * 与 gen.js 的浅色流程图风格区分：深色机架 + 圆柱存储 + 云服务 + 正交网络连线。
 * 生成纯 SVG（内嵌 HTML），由无头 Chrome 截图导出 PNG。
 */
const fs = require('fs');
const path = require('path');

const FONT = 'Microsoft YaHei, PingFang SC, sans-serif';
const MONO = 'Consolas, "Courier New", monospace';

const NAVY = '#0f2a4a';      /* 机架/边框深蓝 */
const NAVY2 = '#0a1e36';     /* 更深深蓝 */
const BODY = '#e9f2fb';      /* 机架浅蓝底 */
const LINE = '#1e3a5f';      /* 网络线 */
const DOTS = '#0ea5e9';      /* 连接点 */
const TXT = '#0f2a4a';
const GRID = '#dce7f3';

function esc(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/* ---------- 基础形状 ---------- */

/* 机架服务器节点 */
function rack(x, y, w, h, name, specs) {
  let out = '';
  out += `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="7" fill="${NAVY}"/>`;
  out += `<rect x="${x + 4}" y="${y + 4}" width="${w - 8}" height="${h - 8}" rx="5" fill="${BODY}"/>`;
  /* 顶部挡板 */
  out += `<rect x="${x + 4}" y="${y + 4}" width="${w - 8}" height="24" rx="5" fill="${NAVY}"/>`;
  out += `<circle cx="${x + 20}" cy="${y + 16}" r="3" fill="#4ade80"/>`;
  out += `<circle cx="${x + 32}" cy="${y + 16}" r="3" fill="#38bdf8"/>`;
  out += `<circle cx="${x + 44}" cy="${y + 16}" r="3" fill="#fbbf24"/>`;
  out += `<text x="${x + 56}" y="${y + 21}" font-family="${FONT}" font-size="13.5" font-weight="bold" fill="#e8f4ff">${esc(name)}</text>`;
  /* 正文规格 */
  const lines = Array.isArray(specs) ? specs : [specs];
  let ly = y + 48;
  for (const ln of lines) {
    out += `<text x="${x + 14}" y="${ly}" font-family="${FONT}" font-size="11.5" fill="${TXT}">${esc(ln)}</text>`;
    ly += 17;
  }
  /* 底部散热条 */
  out += `<rect x="${x + 12}" y="${y + h - 12}" width="${w - 24}" height="3" rx="1.5" fill="#b6cbe3"/>`;
  return out;
}

/* 圆柱存储节点（数据库/对象存储） */
function cylinder(x, y, w, h, name, specs) {
  const r = Math.min(14, h * 0.14);
  let out = '';
  out += `<rect x="${x}" y="${y + r}" width="${w}" height="${h - r}" fill="#d9e7f6" stroke="${NAVY}" stroke-width="1.5"/>`;
  out += `<ellipse cx="${x + w / 2}" cy="${y + r}" rx="${w / 2}" ry="${r}" fill="#eef5ff" stroke="${NAVY}" stroke-width="1.5"/>`;
  out += `<ellipse cx="${x + w / 2}" cy="${y + h - r}" rx="${w / 2}" ry="${r}" fill="none" stroke="${NAVY}" stroke-width="1.2"/>`;
  /* 顶部标签 */
  out += `<text x="${x + w / 2}" y="${y + 34}" text-anchor="middle" font-family="${FONT}" font-size="14" font-weight="bold" fill="${NAVY}">${esc(name)}</text>`;
  const lines = Array.isArray(specs) ? specs : [specs];
  let ly = y + 60;
  for (const ln of lines) {
    out += `<text x="${x + w / 2}" y="${ly}" text-anchor="middle" font-family="${FONT}" font-size="11.5" fill="${TXT}">${esc(ln)}</text>`;
    ly += 17;
  }
  return out;
}

/* 云服务卡片 */
function cloudCard(x, y, w, h, name, specs) {
  let out = '';
  /* 云图标 */
  const cx = x + 30, cy = y + 24;
  out += `<path d="M ${cx - 18},${cy + 8} a 10,10 0 0 1 2,-16 a 13,13 0 0 1 24,-4 a 11,11 0 0 1 4,20 z" fill="#e6f4ff" stroke="${NAVY}" stroke-width="1.4"/>`;
  out += `<rect x="${x + 52}" y="${y + 4}" width="${w - 62}" height="${h - 8}" rx="6" fill="#eef5ff" stroke="${NAVY}" stroke-width="1.5"/>`;
  out += `<text x="${x + 62}" y="${y + 26}" font-family="${FONT}" font-size="13.5" font-weight="bold" fill="${NAVY}">${esc(name)}</text>`;
  const lines = Array.isArray(specs) ? specs : [specs];
  let ly = y + 48;
  for (const ln of lines) {
    out += `<text x="${x + 62}" y="${ly}" font-family="${FONT}" font-size="11" fill="${TXT}">${esc(ln)}</text>`;
    ly += 16;
  }
  return out;
}

/* 交换机节点 */
function switchNode(x, y, w, h, name, specs) {
  let out = '';
  out += `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="6" fill="${NAVY}"/>`;
  out += `<text x="${x + 14}" y="${y + 22}" font-family="${FONT}" font-size="13.5" font-weight="bold" fill="#e8f4ff">${esc(name)}</text>`;
  const lines = Array.isArray(specs) ? specs : [specs];
  let ly = y + 44;
  for (const ln of lines) {
    out += `<text x="${x + 14}" y="${ly}" font-family="${FONT}" font-size="11.5" fill="#cfe2f5">${esc(ln)}</text>`;
    ly += 17;
  }
  /* 端口灯 */
  for (let i = 0; i < 12; i++) {
    const px = x + 16 + i * 14;
    out += `<circle cx="${px}" cy="${y + h - 10}" r="2.6" fill="#7dd3fc"/>`;
  }
  out += `<text x="${x + 190}" y="${y + h - 12}" font-family="${MONO}" font-size="9" fill="#7dd3fc">1G/10G × 12</text>`;
  return out;
}

/* 防火墙 / 网关节点 */
function firewallNode(x, y, w, h, name, specs) {
  let out = '';
  out += `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="7" fill="${NAVY}"/>`;
  out += `<rect x="${x + 4}" y="${y + 4}" width="${w - 8}" height="${h - 8}" rx="5" fill="${BODY}"/>`;
  /* 盾牌图标 */
  const sx = x + 26, sy = y + 34;
  out += `<path d="M ${sx},${sy - 14} L ${sx + 16},${sy - 18} L ${sx + 16},${sy - 2} Q ${sx + 16},${sy + 10} ${sx},${sy + 16} Q ${sx - 16},${sy + 10} ${sx - 16},${sy - 2} L ${sx - 16},${sy - 18} Z" fill="#dbeafe" stroke="${NAVY}" stroke-width="1.4"/>`;
  out += `<text x="${sx}" y="${sy + 5}" text-anchor="middle" font-family="${FONT}" font-size="10" font-weight="bold" fill="${NAVY}">FW</text>`;
  out += `<text x="${x + 56}" y="${y + 26}" font-family="${FONT}" font-size="13.5" font-weight="bold" fill="${NAVY}">${esc(name)}</text>`;
  const lines = Array.isArray(specs) ? specs : [specs];
  let ly = y + 50;
  for (const ln of lines) {
    out += `<text x="${x + 14}" y="${ly}" font-family="${FONT}" font-size="11" fill="${TXT}">${esc(ln)}</text>`;
    ly += 17;
  }
  return out;
}

/* 客户端显示器节点 */
function monitor(x, y, w, h, name, specs) {
  let out = '';
  out += `<rect x="${x}" y="${y}" width="${w}" height="${h - 12}" rx="6" fill="#cbdae9" stroke="${NAVY}" stroke-width="1.5"/>`;
  out += `<rect x="${x + 8}" y="${y + 8}" width="${w - 16}" height="${h - 28}" rx="3" fill="#eaf4ff" stroke="${NAVY}" stroke-width="1"/>`;
  out += `<rect x="${x + w / 2 - 7}" y="${y + h - 12}" width="14" height="8" fill="${NAVY}"/>`;
  out += `<rect x="${x + w / 2 - 26}" y="${y + h - 4}" width="52" height="4" rx="2" fill="${NAVY}"/>`;
  out += `<text x="${x + w / 2}" y="${y + h - 20}" text-anchor="middle" font-family="${FONT}" font-size="13" font-weight="bold" fill="${NAVY}">${esc(name)}</text>`;
  const lines = Array.isArray(specs) ? specs : [specs];
  let ly = y + h - 34;
  for (const ln of lines.slice(0, 1)) {
    out += `<text x="${x + w / 2}" y="${ly}" text-anchor="middle" font-family="${FONT}" font-size="10.5" fill="${TXT}">${esc(ln)}</text>`;
  }
  return out;
}

/* 区域边框 + 标签 */
function zone(x, y, w, h, tag) {
  let out = '';
  out += `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="10" fill="none" stroke="${LINE}" stroke-width="1.4" stroke-dasharray="7,5"/>`;
  out += `<rect x="${x + 6}" y="${y - 13}" width="${tag.length * 14 + 22}" height="26" rx="13" fill="${NAVY}"/>`;
  out += `<text x="${x + 6 + (tag.length * 14 + 22) / 2}" y="${y + 5}" text-anchor="middle" font-family="${FONT}" font-size="13" font-weight="bold" fill="#e8f4ff">${esc(tag)}</text>`;
  return out;
}

/* 正交网络连线 */
function netLine(pts, color, label, lx, ly) {
  color = color || LINE;
  let d = 'M ' + pts[0][0] + ' ' + pts[0][1];
  for (let i = 1; i < pts.length; i++) d += ' L ' + pts[i][0] + ' ' + pts[i][1];
  let out = '';
  out += `<path d="${d}" fill="none" stroke="${color}" stroke-width="1.8"/>`;
  out += `<rect x="${pts[0][0] - 3.5}" y="${pts[0][1] - 3.5}" width="7" height="7" fill="${DOTS}" transform="rotate(45 ${pts[0][0]} ${pts[0][1]})"/>`;
  const last = pts[pts.length - 1];
  out += `<rect x="${last[0] - 3.5}" y="${last[1] - 3.5}" width="7" height="7" fill="${DOTS}" transform="rotate(45 ${last[0]} ${last[1]})"/>`;
  if (label) {
    out += `<rect x="${lx - 3}" y="${ly - 15}" width="${label.length * 11.5 + 6}" height="18" rx="9" fill="#ffffff" stroke="${color}" stroke-width="1"/>`;
    out += `<text x="${lx + label.length * 5.75}" y="${ly - 2}" text-anchor="middle" font-family="${FONT}" font-size="11" fill="${color}">${esc(label)}</text>`;
  }
  return out;
}

/* 标题栏 */
function header(W, titleStr, rightStr) {
  let out = '';
  out += `<rect x="30" y="16" width="${W - 60}" height="46" rx="7" fill="${NAVY}"/>`;
  out += `<rect x="36" y="22" width="3" height="34" rx="1.5" fill="#38bdf8"/>`;
  out += `<text x="50" y="46" font-family="${FONT}" font-size="20" font-weight="bold" fill="#ffffff">${esc(titleStr)}</text>`;
  out += `<text x="${W - 50}" y="44" text-anchor="end" font-family="${FONT}" font-size="13" fill="#b8cfe6">${esc(rightStr)}</text>`;
  return out;
}

/* 画布背景网格 */
function gridBG(W, H) {
  return `<defs><pattern id="grid" width="24" height="24" patternUnits="userSpaceOnUse">` +
    `<path d="M 24 0 L 0 0 0 24" fill="none" stroke="${GRID}" stroke-width="1"/></pattern></defs>` +
    `<rect width="${W}" height="${H}" fill="#f6f9fe"/><rect width="${W}" height="${H}" fill="url(#grid)"/>`;
}

function svgDoc(W, H, body) {
  return `<!DOCTYPE html>
<html><head><meta charset="utf-8">
<style>html,body{margin:0;padding:0;background:#f6f9fe;overflow:hidden}</style>
</head>
<body>
<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" style="display:block">
${body}
</svg>
</body></html>`;
}

function validate(W, H, b) {
  const re = /<rect x="([\d.-]+)" y="([\d.-]+)" width="([\d.-]+)" height="([\d.-]+)"/g;
  let m, bad = 0;
  while ((m = re.exec(b)) !== null) {
    const x = parseFloat(m[1]), y = parseFloat(m[2]), w = parseFloat(m[3]), h = parseFloat(m[4]);
    if (x < -0.5 || y < -0.5 || x + w > W + 0.5 || y + h > H + 0.5) {
      console.log('  [越界] rect x=' + x + ' y=' + y + ' w=' + w + ' h=' + h);
      bad++;
    }
  }
  if (bad === 0) console.log('  [校验] 所有矩形均在画布内');
  return bad;
}

/* ==================== 主拓扑图 ==================== */
function topology() {
  const W = 1540, H = 1080;
  let b = '';
  b += gridBG(W, H);
  b += header(W, '数据库运维 Agent —— 硬件拓扑设计', 'V1.0 · 2026-08 · 内网 10.0.0.0/24 · 服务端口 8084');

  /* 区域 */
  b += zone(30, 90, 420, 332, 'Z1 客户端与接入区');
  b += zone(490, 90, 470, 332, 'Z2 应用服务区');
  b += zone(30, 470, 930, 122, 'Z3 核心交换网络');
  b += zone(30, 632, 930, 380, 'Z4 数据与中间件区');
  b += zone(1000, 90, 510, 332, 'Z5 外部云服务（HTTPS 443 出站）');

  /* 网络连线（先画线，后画节点） */
  /* 客户端 → 网关 */
  b += netLine([[160, 240], [185, 285]], LINE, '', 0, 0);
  b += netLine([[350, 240], [330, 285]], LINE, '', 0, 0);
  /* 网关 → LB */
  b += netLine([[365, 335], [540, 335]], LINE, '内网转发 · 8084', 420, 322);
  /* LB → 应用节点 */
  b += netLine([[610, 300], [610, 262]], LINE, '', 0, 0);
  b += netLine([[840, 300], [840, 262]], LINE, '', 0, 0);
  /* 应用(LB) → 交换机 */
  b += netLine([[610, 370], [610, 505]], LINE, '', 0, 0);
  b += netLine([[840, 370], [840, 505]], LINE, '', 0, 0);
  /* 交换机 → 数据区主干 */
  b += netLine([[545, 565], [545, 625]], LINE, '', 0, 0);
  b += netLine([[175, 625], [660, 625]], LINE, '', 0, 0);
  b += netLine([[175, 625], [175, 690]], LINE, '', 0, 0);
  b += netLine([[430, 625], [430, 690]], LINE, '', 0, 0);
  b += netLine([[660, 625], [660, 690]], LINE, '', 0, 0);
  b += netLine([[545, 625], [545, 835]], LINE, '', 0, 0);
  b += netLine([[175, 835], [660, 835]], LINE, '', 0, 0);
  b += netLine([[175, 835], [175, 850]], LINE, '', 0, 0);
  b += netLine([[430, 835], [430, 850]], LINE, '', 0, 0);
  b += netLine([[660, 835], [660, 850]], LINE, '', 0, 0);
  /* 应用 → 外网出站 */
  b += netLine([[940, 205], [1000, 205]], '#0e7490', 'HTTPS 443 出站（LLM API）', 990, 192);

  /* Z1 节点 */
  b += monitor(70, 150, 160, 92, '员工浏览器', ['对话 / 流式页面']);
  b += monitor(280, 150, 160, 92, '运维终端', ['SSH / 巡检 / 监控']);
  b += firewallNode(145, 285, 220, 92, '接入网关 / 防火墙', ['WAF · TLS 终止 · 限流', '仅暴露 8084（HTTP/SSE）', '内网 10.0.0.10']);

  /* Z2 节点 */
  b += rack(510, 150, 200, 110, 'Agent 应用 01', ['Spring Boot 3.2.5 / JDK17', '端口 8084 · SSE 长连接', '4C8G / 100GB SSD']);
  b += rack(740, 150, 200, 110, 'Agent 应用 02', ['Spring Boot 3.2.5 / JDK17', '端口 8084 · SSE 长连接', '4C8G / 100GB SSD']);
  b += rack(540, 300, 370, 70, '反向代理 / 负载均衡', ['Nginx · 支持 SSE · session 亲和', '2C4G / 20GB SSD']);

  /* Z3 节点 */
  b += switchNode(250, 505, 700, 60, '核心交换机（万兆）', ['VLAN：app / data / middleware 隔离 · 全双工 · 冗余链路']);

  /* Z4 节点 */
  b += cylinder(50, 690, 250, 130, 'PostgreSQL + pgvector', ['业务库 rag_db · HNSW 向量索引', '端口 5432 · 4C16G', '200GB SSD · 主备异步流复制']);
  b += rack(330, 690, 200, 120, 'Redis 7', ['Token / 短期缓存', '端口 6379 · 2C4G', '20GB SSD']);
  b += rack(560, 690, 200, 120, 'Elasticsearch 8', ['关键词倒排索引', '端口 9200 · 4C8G', '100GB SSD（堆 4G）']);
  b += cylinder(50, 850, 250, 120, 'MinIO 对象存储', ['文档文件 · rag-bucket', '端口 9000/9001 · 2C8G', '500GB HDD+SSD']);
  b += rack(330, 850, 200, 110, 'RocketMQ', ['NameServer 9876 + Broker 10911', 'topic: rag-ingest-topic', '2C4G / 50GB SSD']);
  b += rack(560, 850, 200, 110, '可观测 / 日志', ['OTel Exporter · 日志采集', '指标 / Trace 存储', '2C4G / 100GB SSD']);

  /* Z5 节点 */
  b += cloudCard(1030, 150, 220, 110, '阿里云百炼 DashScope', ['text-embedding-v4 · 1536维', 'qwen-plus 对话', 'qwen3-rerank 重排']);
  b += cloudCard(1280, 150, 210, 110, 'DeepSeek', ['deepseek-v4-pro', '对话 + 流式 SSE', 'api.deepseek.com']);
  b += cloudCard(1030, 295, 220, 85, 'SMTP 邮件服务', ['smtp.qq.com:465（TLS）', '告警通知收件']);
  /* 互联网云标记 */
  b += `<path d="M 1420,330 a 9,9 0 0 1 2,-14 a 11,11 0 0 1 20,-4 a 9,9 0 0 1 3,18 z" fill="#e6f4ff" stroke="${NAVY}" stroke-width="1.3"/>`;
  b += `<text x="1458" y="336" text-anchor="middle" font-family="${FONT}" font-size="11" fill="${NAVY}">互联网</text>`;

  /* 底部说明 */
  b += `<rect x="30" y="1032" width="1480" height="34" rx="7" fill="#e6effa" stroke="${LINE}" stroke-width="1"/>`;
  b += `<text x="50" y="1054" font-family="${FONT}" font-size="12" fill="${TXT}">说明：以上为生产最小化部署建议（单实例可承载；SSE 长连接数大时应用节点横向扩容）。全部中间件内网互通，仅网关对外暴露 8084；LLM 调用为 HTTPS 443 出站。</text>`;

  const html = svgDoc(W, H, b);
  fs.writeFileSync(path.join(__dirname, 'topology_hardware.html'), html, 'utf8');
  console.log('generated topology_hardware.html (' + W + 'x' + H + ')');
  validate(W, H, b);
}

topology();
