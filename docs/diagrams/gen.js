/* 数据库运维 Agent —— 架构图与流程图生成器
 * 生成纯 SVG（内嵌于 HTML），再由无头 Chrome 截图导出 PNG。
 */

const fs = require('fs');
const path = require('path');

const FONT = 'Microsoft YaHei, SimHei, PingFang SC, sans-serif';

/* 调色板（按层） */
const PALETTE = {
  present: { fill: '#e3f2fd', stroke: '#1e88e5', text: '#0d47a1', label: '#1565c0', band: '#f2f8fe' },
  api:     { fill: '#e8f5e9', stroke: '#43a047', text: '#1b5e20', label: '#2e7d32', band: '#f4faf4' },
  app:     { fill: '#fff3e0', stroke: '#fb8c00', text: '#e65100', label: '#ef6c00', band: '#fffbf2' },
  domain:  { fill: '#f3e5f5', stroke: '#ab47bc', text: '#4a148c', label: '#8e24aa', band: '#faf2fc' },
  infra:   { fill: '#eceff1', stroke: '#78909c', text: '#263238', label: '#455a64', band: '#f4f6f8' },
  external:{ fill: '#fce4ec', stroke: '#e53935', text: '#b71c1c', label: '#c62828', band: '#fef2f4' },
  cross:   { fill: '#fffde7', stroke: '#f9a825', text: '#7a5900', label: '#f57f17', band: '#fffdf0' },
  neutral: { fill: '#ffffff', stroke: '#90a4ae', text: '#37474f', label: '#546e7a', band: '#f7f8fa' },
};

function esc(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/* 文本框（支持多行副标题，居中对齐） */
function box(x, y, w, h, title, subLines, pal, titleSize, subSize) {
  const p = pal || PALETTE.neutral;
  titleSize = titleSize || 15;
  subSize = subSize || 12;
  const sub = Array.isArray(subLines) ? subLines : [subLines];
  let out = '';
  out += `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="8" fill="${p.fill}" stroke="${p.stroke}" stroke-width="1.5"/>`;
  const cx = x + w / 2;
  const lines = [title, ...sub].filter(s => s && String(s).trim() !== '');
  const total = lines.length;
  const lineGap = 17;
  const startY = y + h / 2 - ((total - 1) * lineGap) / 2 + 5;
  for (let i = 0; i < total; i++) {
    const isTitle = i === 0;
    const fsize = isTitle ? titleSize : subSize;
    const fillc = isTitle ? p.text : '#546e7a';
    const weight = isTitle ? 'bold' : 'normal';
    out += `<text x="${cx}" y="${startY + i * lineGap}" text-anchor="middle" font-family="${FONT}" font-size="${fsize}" font-weight="${weight}" fill="${fillc}">${esc(lines[i])}</text>`;
  }
  return out;
}

/* 横排一组文本框（等宽等高，自动排列），返回末尾 x 坐标 */
function hbox(row, x, y, w, h, gap, title, subLines, pal, titleSize, subSize) {
  if (row) {
    return box(x, y, w, h, title, subLines, pal, titleSize, subSize);
  }
  return box(x, y, w, h, title, subLines, pal, titleSize, subSize);
}

/* 层带（band）：背景 + 左上角层标签 */
function band(x, y, w, h, label, pal, labelSize) {
  const p = pal || PALETTE.neutral;
  labelSize = labelSize || 18;
  let out = '';
  out += `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="10" fill="${p.band}" stroke="${p.label}" stroke-width="1.2" stroke-dasharray="0"/>`;
  out += `<text x="${x + 14}" y="${y + 24}" font-family="${FONT}" font-size="${labelSize}" font-weight="bold" fill="${p.label}">${esc(label)}</text>`;
  return out;
}

/* 分组小标题（层带内左侧竖向或横向） */
function groupLabel(x, y, textStr, pal, size) {
  const p = pal || PALETTE.neutral;
  size = size || 13;
  return `<text x="${x}" y="${y}" font-family="${FONT}" font-size="${size}" font-weight="bold" fill="${p.label}">${esc(textStr)}</text>`;
}

/* 垂直箭头 + 标签 */
function varrow(x, y1, y2, label, color) {
  color = color || '#607d8b';
  let out = '';
  out += `<defs><marker id="m${color.replace(/[^a-zA-Z0-9]/g, '')}" markerWidth="10" markerHeight="10" refX="5" refY="5" orient="auto">`;
  out += `<path d="M0,0 L10,5 L0,10 z" fill="${color}"/></marker></defs>`;
  out += `<line x1="${x}" y1="${y1}" x2="${x}" y2="${y2 - 4}" stroke="${color}" stroke-width="2" marker-end="url(#m${color.replace(/[^a-zA-Z0-9]/g, '')})"/>`;
  if (label) {
    out += `<text x="${x + 10}" y="${(y1 + y2) / 2 - 6}" font-family="${FONT}" font-size="12.5" fill="${color}">${esc(label)}</text>`;
  }
  return out;
}

/* 决策菱形（水平定向，cx/cy 为中心，w 为宽、h 为高） */
function diamond(cx, cy, w, h, text, pal) {
  const p = pal || PALETTE.neutral;
  const x = cx - w / 2, y = cy - h / 2;
  let out = '';
  out += `<polygon points="${x},${cy} ${cx},${y} ${x + w},${cy} ${cx},${y + h}" fill="${p.fill}" stroke="${p.stroke}" stroke-width="1.6"/>`;
  const lines = String(text).split('\n');
  const gap = 16;
  const startY = cy - ((lines.length - 1) * gap) / 2 + 5;
  for (let i = 0; i < lines.length; i++) {
    out += `<text x="${cx}" y="${startY + i * gap}" text-anchor="middle" font-family="${FONT}" font-size="13" font-weight="bold" fill="${p.text}">${esc(lines[i])}</text>`;
  }
  return out;
}

/* 流程步骤框（带编号，居中对齐） */
function step(x, y, w, h, num, titleStr, subLines, pal, titleSize, subSize) {
  const p = pal || PALETTE.app;
  let out = '';
  out += box(x, y, w, h, (num ? num + '. ' : '') + titleStr, subLines, p, titleSize || 14.5, subSize || 11.5);
  return out;
}

/* 侧注小框（无边框的提示文字） */
function sideNote(x, y, w, textStr, color) {
  color = color || '#78909c';
  return `<text x="${x}" y="${y}" font-family="${FONT}" font-size="11.5" fill="${color}">${esc(textStr)}</text>`;
}

/* 通用箭头（从 (x1,y1) 到 (x2,y2)） */
function arrow(x1, y1, x2, y2, color) {
  color = color || '#607d8b';
  const id = 'a' + color.replace(/[^a-zA-Z0-9]/g, '');
  let out = '';
  out += `<defs><marker id="${id}" markerWidth="10" markerHeight="10" refX="5" refY="5" orient="auto">`;
  out += `<path d="M0,0 L10,5 L0,10 z" fill="${color}"/></marker></defs>`;
  out += `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="${color}" stroke-width="1.8" marker-end="url(#${id})"/>`;
  return out;
}

/* 水平箭头 */
function harrow(x1, x2, y, label, color) {
  color = color || '#607d8b';
  const id = 'h' + color.replace(/[^a-zA-Z0-9]/g, '');
  let out = '';
  out += `<defs><marker id="${id}" markerWidth="10" markerHeight="10" refX="5" refY="5" orient="auto">`;
  out += `<path d="M0,0 L10,5 L0,10 z" fill="${color}"/></marker></defs>`;
  out += `<line x1="${x1}" y1="${y}" x2="${x2 - 4}" y2="${y}" stroke="${color}" stroke-width="2" marker-end="url(#${id})"/>`;
  if (label) {
    out += `<text x="${(x1 + x2) / 2}" y="${y - 8}" text-anchor="middle" font-family="${FONT}" font-size="12" fill="${color}">${esc(label)}</text>`;
  }
  return out;
}

/* 顶部大标题 */
function title(w, textStr, size) {
  size = size || 24;
  return `<text x="${w / 2}" y="42" text-anchor="middle" font-family="${FONT}" font-size="${size}" font-weight="bold" fill="#1a237e">${esc(textStr)}</text>`;
}

function footer(w, h, textStr) {
  return `<text x="${w / 2}" y="${h - 16}" text-anchor="middle" font-family="${FONT}" font-size="11" fill="#9e9e9e">${esc(textStr)}</text>`;
}

function svgDoc(w, h, body) {
  return `<!DOCTYPE html>
<html><head><meta charset="utf-8">
<style>html,body{margin:0;padding:0;background:#ffffff;overflow:hidden}</style>
</head>
<body>
<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" style="display:block">
${body}
</svg>
</body></html>`;
}

const OUT_DIR = __dirname;

function write(name, w, h, body) {
  const html = svgDoc(w, h, body);
  fs.writeFileSync(path.join(OUT_DIR, name + '.html'), html, 'utf8');
  console.log('generated ' + name + '.html  (' + w + 'x' + h + ')');
}

module.exports = { PALETTE, esc, box, band, groupLabel, varrow, harrow, title, footer, svgDoc, write };

/* 校验所有矩形不超出画布边界 */
function validate(W, H, svgBody) {
  const re = /<rect x="([\d.-]+)" y="([\d.-]+)" width="([\d.-]+)" height="([\d.-]+)"/g;
  let m, bad = 0;
  while ((m = re.exec(svgBody)) !== null) {
    const x = parseFloat(m[1]), y = parseFloat(m[2]);
    const w = parseFloat(m[3]), h = parseFloat(m[4]);
    if (x < -0.5 || y < -0.5 || x + w > W + 0.5 || y + h > H + 0.5) {
      console.log('  [越界] rect x=' + x + ' y=' + y + ' w=' + w + ' h=' + h);
      bad++;
    }
  }
  if (bad === 0) console.log('  [校验] 所有矩形均在画布内');
  return bad;
}

/* ==================== 1. 总体架构图 ==================== */
function architecture() {
  const W = 1610, H = 1720;
  let b = '';
  b += title(W, '数据库运维 Agent —— 系统总体架构图');
  b += footer(W, H, '项目：Agent（Spring Boot 3.2.5 + LangChain4j 1.15.0）  ·  大模型：阿里云百炼 / DeepSeek（OpenAI 兼容）  ·  端口 8084');

  const BW = 1280, BX = 30;   /* 层带：30..1310 */
  const AX = 670;             /* 层带中心 x（箭头位置） */
  const P = PALETTE;

  /* L1 展示层 */
  const l1 = { y: 78, h: 128 };
  b += band(BX, l1.y, BW, l1.h, 'L1 展示层（Presentation）—— 前端静态页面', P.present);
  b += box(50, l1.y + 44, 300, 66, '聊天界面', ['index.html · 对话/流式输入'], P.present);
  b += box(366, l1.y + 44, 300, 66, '流式对话页', ['stream.html · SSE 打字机效果'], P.present);
  b += box(682, l1.y + 44, 300, 66, '可观测面板', ['observability.html · Trace/LLM/工具'], P.present);
  b += box(998, l1.y + 44, 300, 66, 'RAG 演示页', ['rag.html · 检索效果演示'], P.present);
  const l2 = { y: 272, h: 232 };
  b += varrow(AX, l1.y + l1.h, l2.y, 'HTTP / SSE（JSON）', '#1e88e5');
  b += band(BX, l2.y, BW, l2.h, 'L2 接入与 API 层（Controllers / 拦截器）', P.api);
  const rows2 = [
    [
      ['AuthController', ['/auth · 注册/登录/登出/me'], P.api],
      ['AgentController', ['/agent/chat · 同步 + SSE 流式'], P.api],
      ['AgentReactiveController', ['/agent/chat/reactive/stream · SSE'], P.api],
      ['ConversationController', ['/agent/conversations · 会话 CRUD'], P.api],
    ],
    [
      ['HealthCheckController', ['/healthcheck/run · 手动巡检'], P.api],
      ['ObservabilityController', ['/agent/observability · 可观测'], P.api],
      ['RagController', ['/rag/ingest · 已废弃'], P.neutral],
      ['BEIR 评测接口', ['/eval/beir · 检索评测'], P.api],
    ],
  ];
  const rw = 298, rgap = 18;
  for (let r = 0; r < rows2.length; r++) {
    const yy = l2.y + 42 + r * 70;
    for (let c = 0; c < rows2[r].length; c++) {
      const [t, sub, pal] = rows2[r][c];
      b += box(50 + c * (rw + rgap), yy, rw, 60, t, sub, pal, 14.5, 11.5);
    }
  }
  /* 横切中间件注释 */
  b += `<rect x="50" y="${l2.y + 186}" width="1240" height="34" rx="6" fill="${P.cross.band}" stroke="${P.cross.stroke}" stroke-width="1"/>`;
  b += `<text x="60" y="${l2.y + 208}" font-family="${FONT}" font-size="12.5" fill="${P.cross.text}">横切中间件：AuthTokenFilter（Token 鉴权）  ·  TracingFilter（全链路 Trace）  ·  CorsConfig（跨域）  ·  UserContextHolder（用户上下文）</text>`;

  const l3 = { y: 568, h: 282 };
  b += varrow(AX, l2.y + l2.h, l3.y, '调用 Service', '#fb8c00');
  b += band(BX, l3.y, BW, l3.h, 'L3 应用编排层（Application Services）', P.app);
  const rows3 = [
    [
      ['AgentServiceImpl', ['对话编排：意图识别/路由/记忆'], P.app],
      ['ReactiveStreamingService', ['SSE 流式输出编排'], P.app],
      ['ToolCallingService', ['ReAct 工具循环编排'], P.app],
      ['RagService', ['知识库检索 + 文档入库'], P.app],
      ['QueryRewriteService', ['KB 意图下查询改写'], P.app],
    ],
    [
      ['ConversationService', ['会话创建/查询/标题'], P.app],
      ['ShortTermMemoryService', ['短期记忆（最近消息）'], P.app],
      ['ConversationSummaryService', ['会话摘要生成与触发'], P.app],
      ['HealthCheckRunner', ['定时健康巡检（@Scheduled）'], P.app],
      ['ConversationTraceService', ['可观测数据持久化'], P.app],
    ],
  ];
  const cw = 240, cgap = 12;
  for (let r = 0; r < rows3.length; r++) {
    const yy = l3.y + 46 + r * 68;
    for (let c = 0; c < rows3[r].length; c++) {
      const [t, sub, pal] = rows3[r][c];
      b += box(50 + c * (cw + cgap), yy, cw, 58, t, sub, pal, 14, 11.5);
    }
  }
  /* 意图路由说明条 */
  b += `<rect x="50" y="${l3.y + 236}" width="1240" height="32" rx="6" fill="#fff8e1" stroke="#ffb300" stroke-width="1"/>`;
  b += `<text x="60" y="${l3.y + 257}" font-family="${FONT}" font-size="12.5" fill="#8d6e00">意图路由：KNOWLEDGE_BASE → RAG 检索问答  ·  COMPLEX_TASK → ReAct 工具编排  ·  SIMPLE_CHAT → 直接对话</text>`;

  const l4 = { y: 916, h: 306 };
  b += varrow(AX, l3.y + l3.h, l4.y, '依赖模型 / 知识处理 / 工具', '#ab47bc');
  b += band(BX, l4.y, BW, l4.h, 'L4 领域模型层（Domain / Model / Tools）', P.domain);
  b += groupLabel(50, l4.y + 40, '大模型调用', P.domain);
  b += box(50, l4.y + 50, 380, 62, 'LlmService', ['统一接口：chat / chatStream / embed / rerank'], P.domain, 14, 11.5);
  b += box(455, l4.y + 50, 258, 62, 'DeepSeek Chat', ['deepseek-v4-pro · 流式/非流式'], P.external, 14, 11.5);
  b += box(735, l4.y + 50, 258, 62, '百炼 Embedding', ['text-embedding-v4 · 1536 维'], P.external, 14, 11.5);
  b += box(1015, l4.y + 50, 265, 62, '百炼 Rerank', ['qwen3-rerank · 重排序'], P.external, 14, 11.5);
  b += groupLabel(50, l4.y + 136, '知识处理 / 检索', P.domain);
  b += box(50, l4.y + 146, 300, 58, 'DocumentParser', ['Tika 解析 PDF/Word/TXT'], P.domain, 14, 11.5);
  b += box(372, l4.y + 146, 300, 58, '分块引擎', ['段落切分/递归拆分/合并/overlap'], P.domain, 14, 11.5);
  b += box(694, l4.y + 146, 300, 58, 'KeywordSearchService', ['ES 关键词倒排检索'], P.domain, 14, 11.5);
  b += box(1016, l4.y + 146, 264, 58, 'VectorStoreService', ['pgvector 向量读写'], P.domain, 14, 11.5);
  b += groupLabel(50, l4.y + 228, '可调用工具集（LangChain4j @Tool）', P.domain);
  const tools = [
    ['DatabaseHealthCheckTool', '健康指标'],
    ['DatabaseMetricsTool', '性能指标'],
    ['SlowQueryTool', '慢查询'],
    ['SqlExecutionPlanTool', '执行计划'],
    ['EmailAlertTool', '邮件告警'],
    ['WeatherTool', '天气示例'],
  ];
  const tw = 196, tgap = 14;
  for (let i = 0; i < tools.length; i++) {
    b += box(50 + i * (tw + tgap), l4.y + 240, tw, 52, tools[i][0], tools[i][1], P.domain, 13, 11.5);
  }

  const l5 = { y: 1290, h: 226 };
  b += varrow(AX, l4.y + l4.h, l5.y, '数据读写 / 消息 / 存储', '#78909c');
  b += band(BX, l5.y, BW, l5.h, 'L5 基础设施层（Infrastructure）', P.infra);
  const infra = [
    ['PostgreSQL', ['业务库 + pgvector 向量', '（会话/记忆/记录/向量）'], P.infra],
    ['Redis', ['登录 Token', '短期记忆缓存'], P.infra],
    ['Elasticsearch', ['关键词倒排索引', 'chunk 检索'], P.infra],
    ['MinIO', ['文档对象存储', 'rag-bucket'], P.infra],
    ['RocketMQ', ['rag-ingest-topic', '异步文档入库'], P.infra],
    ['SMTP 邮件', ['QQ 邮箱', '告警通知'], P.infra],
  ];
  const iw = 196, igap = 14;
  for (let i = 0; i < infra.length; i++) {
    const [t, sub, pal] = infra[i];
    b += box(50 + i * (iw + igap), l5.y + 52, iw, 66, t, sub, pal, 14.5, 11.5);
  }
  b += `<rect x="50" y="${l5.y + 134}" width="1240" height="80" rx="6" fill="#eef4f6" stroke="#b0bec5" stroke-width="1"/>`;
  b += `<text x="60" y="${l5.y + 158}" font-family="${FONT}" font-size="13" fill="#37474f" font-weight="bold">存储与检索要点</text>`;
  b += `<text x="60" y="${l5.y + 180}" font-family="${FONT}" font-size="12" fill="#546e7a">· pgvector 向量表 text_chunk_vector（HNSW 索引） + 业务表 text_chunk / file_metadata / agent_short_term_memory / conversation_trace ...</text>`;
  b += `<text x="60" y="${l5.y + 200}" font-family="${FONT}" font-size="12" fill="#546e7a">· ES 与 pgvector 均存 chunk_id，检索阶段 RRF 融合后回表取 chunk 文本</text>`;

  const l6 = { y: 1586, h: 108 };
  b += varrow(AX, l5.y + l5.h, l6.y, '大模型 API（OpenAI 兼容，HttpURLConnection）', '#e53935');
  b += band(BX, l6.y, BW, l6.h, 'L6 外部 AI 服务（External LLM Services）', P.external);
  b += box(50, l6.y + 42, 600, 56, '阿里云百炼 DashScope', ['text-embedding-v4（Embedding） · qwen-plus（Chat） · qwen3-rerank（Rerank）'], P.external, 14.5, 11.5);
  b += box(700, l6.y + 42, 580, 56, 'DeepSeek', ['deepseek-v4-pro（Chat + 流式 SSE）'], P.external, 14.5, 11.5);

  /* 右侧横切关注点竖条 */
  const crossX = 1330;
  const cross = [
    '横切关注点',
    '· 鉴权：Token + Redis',
    '· 全链路追踪：Micrometer + OTel',
    '· 可观测：Trace / LLM / 工具调用',
    '· 异步：RocketMQ',
  ];
  let cy = 130;
  for (let i = 0; i < cross.length; i++) {
    const cw2 = 250, chh = i === 0 ? 34 : 56;
    b += box(crossX, cy, cw2, chh, cross[i], '', P.cross, 13.5, 0);
    cy += chh + 12;
  }

  write('architecture_overall', W, H, b);
  validate(W, H, b);
}

architecture();

/* ==================== 2. Agent 对话主流程 ==================== */
function chatFlow() {
  const W = 1300, H = 1340;
  const P = PALETTE;
  let b = '';
  b += title(W, 'Agent 对话处理主流程（POST /agent/chat）');
  b += footer(W, H, 'AgentServiceImpl.chat() → 意图识别 → 查询改写 → 路由（RAG / ReAct / 直接对话） → 记忆落库');

  const sx = 250, sw = 560, cx = sx + sw / 2;
  const sideX = 830;

  /* 1-8 主链路 */
  b += step(sx, 70, sw, 50, 1, '请求进入', ['POST /agent/chat  ·  ChatRequest(query, conversationId)'], P.app);
  b += varrow(cx, 120, 158, '', '#fb8c00');
  b += step(sx, 158, sw, 50, 2, '参数校验 validateChatRequest', ['query 为空 → 400 参数错误'], P.app);
  b += sideNote(sideX, 188, 440, 'query 为空 → 400 参数错误', '#c62828');
  b += varrow(cx, 208, 246, '', '#fb8c00');
  b += step(sx, 246, sw, 50, 3, '获取当前用户 requireCurrentUserId', ['从 UserContextHolder 读取登录用户'], P.app);
  b += sideNote(sideX, 276, 440, '无 userId → 401 未登录', '#c62828');
  b += varrow(cx, 296, 334, '', '#fb8c00');
  b += step(sx, 334, sw, 50, 4, '解析 / 创建会话 resolveConversationId', ['校验会话归属；未传 ID 则自动创建（标题取前20字）'], P.app);
  b += varrow(cx, 384, 422, '', '#fb8c00');
  b += step(sx, 422, sw, 54, 5, '加载历史与摘要', ['短期记忆 listRecentMessages（近20条） + 会话摘要 getSummary'], P.app);
  b += varrow(cx, 476, 514, '', '#fb8c00');
  b += step(sx, 514, sw, 54, 6, '意图识别 identifyIntent', ['先规则 identifyByRules（制度/内部/报销…关键词）', '未命中 → LLM buildIntentPrompt → parseIntent；异常兜底 SIMPLE_CHAT'], P.app);
  b += varrow(cx, 568, 606, '', '#fb8c00');
  b += step(sx, 606, sw, 54, 7, '查询改写 rewriteQueryIfNeeded', ['仅 KNOWLEDGE_BASE 触发（结合会话摘要与历史）', '其余意图原样返回（unchanged）'], P.app);
  b += varrow(cx, 660, 698, '', '#fb8c00');
  b += step(sx, 698, sw, 54, 8, '构建 AgentChatContext', ['userId / conversationId / rewrittenQuery / 历史 / 摘要 / intentType'], P.app);
  b += varrow(cx, 752, 790, '', '#fb8c00');
  b += step(sx, 790, sw, 50, 9, '路由 route(intentType)', ['switch 分派到三条执行路径'], P.app);

  /* 决策菱形 */
  b += arrow(cx, 840, cx, 858, '#546e7a');
  b += diamond(cx, 892, 380, 62, 'intentType\n意图类型?', P.cross);

  /* 三条路由分支 */
  const branchY = 952;
  b += arrow(350, 892, 260, 950, '#43a047');
  b += box(90, branchY, 340, 78, 'KNOWLEDGE_BASE', ['RAG 检索问答 RagService.queryStream', '向量+ES关键词+RRF+重排 → LLM 流式回答'], P.present, 14, 11);
  b += arrow(530, 922, 640, 950, '#ef6c00');
  b += box(470, branchY, 340, 78, 'COMPLEX_TASK', ['构建路由/任务 Prompt → LLM 选工具', 'ReAct 循环执行 @Tool（ToolCallingService）'], P.domain, 14, 11);
  b += arrow(710, 892, 1020, 950, '#1565c0');
  b += box(850, branchY, 340, 78, 'SIMPLE_CHAT', ['构建简单 Prompt（含摘要/历史）', 'llmService.chatStream 流式直接对话'], P.infra, 14, 11);

  /* 汇合 */
  const convY = 1060;
  b += `<line x1="260" y1="1030" x2="260" y2="${convY}" stroke="#546e7a" stroke-width="1.8"/>`;
  b += `<line x1="640" y1="1030" x2="640" y2="${convY}" stroke="#546e7a" stroke-width="1.8"/>`;
  b += `<line x1="1020" y1="1030" x2="1020" y2="${convY}" stroke="#546e7a" stroke-width="1.8"/>`;
  b += `<line x1="260" y1="${convY}" x2="1020" y2="${convY}" stroke="#546e7a" stroke-width="1.8"/>`;
  b += arrow(cx, convY, cx, 1088, '#546e7a');

  /* 10-12 */
  b += step(sx, 1088, sw, 54, 10, '保存消息与更新会话', ['saveUserAndAssistantMessages 写短期记忆（含 intentType 元数据）', 'touchConversation 更新会话活跃时间'], P.app);
  b += varrow(cx, 1142, 1180, '', '#fb8c00');
  b += step(sx, 1180, sw, 54, 11, '触发会话摘要 triggerSummaryIfNeeded', ['达到条件时异步生成/更新会话摘要'], P.app);
  b += varrow(cx, 1234, 1272, '', '#fb8c00');
  b += step(sx, 1272, sw, 54, 12, '返回响应', ['ChatResponse(conversationId, answer)'], P.app);

  write('flow_chat_main', W, H, b);
  validate(W, H, b);
}

chatFlow();

/* ==================== 3. RAG 文档入库异步流程 ==================== */
function ragIngestFlow() {
  const W = 960, H = 970;
  const P = PALETTE;
  let b = '';
  b += title(W, 'RAG 文档入库异步流程（RocketMQ 解耦）');
  b += footer(W, H, 'RagService.ingest → RocketMQ(rag-ingest-topic) → RagIngestConsumer → RagAsyncProcessor（解析/分块/向量化/入库）');

  const sx = 250, sw = 440, cx = sx + sw / 2;
  b += step(sx, 70, sw, 52, 1, '文档上传 POST /rag/ingest', ['RagService.ingest(file) · 前端 MultipartFile 上传'], P.api);
  b += sideNote(730, 96, 220, '旧版入口，仍为入库链路起点', '#c62828');
  b += varrow(cx, 122, 150, '', '#fb8c00');
  b += step(sx, 150, sw, 52, 2, '上传至 MinIO uploadToMinio', ['bucket 不存在自动创建 · objectName = UUID_文件名'], P.app);
  b += varrow(cx, 202, 230, '', '#fb8c00');
  b += step(sx, 230, sw, 52, 3, '写入 file_metadata', ['记录 fileUrl / size / contentType，status=UPLOADED'], P.app);
  b += varrow(cx, 282, 310, '', '#fb8c00');
  b += step(sx, 310, sw, 56, 4, '发送 RocketMQ 消息 ragIngestProducer.send', ['RagIngestMessage(fileId, bucket, objectName, fileName)'], P.app);
  b += sideNote(730, 336, 220, 'topic=rag-ingest-topic', '#00695c');
  b += varrow(cx, 366, 394, '', '#fb8c00');
  b += step(sx, 394, sw, 52, 5, '消费消息 RagIngestConsumer.onMessage', ['RocketMQListener · 反序列化 MessageExt'], P.app);
  b += varrow(cx, 446, 474, '', '#fb8c00');
  b += step(sx, 474, sw, 52, 6, '读取 MinIO 对象 GetObjectArgs', ['按 bucket + objectName 拉取 InputStream'], P.app);
  b += varrow(cx, 526, 554, '', '#fb8c00');
  b += step(sx, 554, sw, 52, 7, '文档解析 DocumentParser.parse（Tika）', ['PDF / Word / TXT 等 → 纯文本'], P.domain);
  b += varrow(cx, 606, 634, '', '#ab47bc');
  b += step(sx, 634, sw, 58, 8, '文本分块 splitText', ['段落切分 → 合并短段 → 递归拆分', '→ 合并小块 → 添加 overlap'], P.domain);
  b += `<rect x="730" y="634" width="200" height="118" rx="6" fill="#f3e5f5" stroke="#ab47bc" stroke-width="1" stroke-dasharray="5,3"/>`;
  b += `<text x="740" y="656" font-family="${FONT}" font-size="12" font-weight="bold" fill="#4a148c">分块参数</text>`;
  b += `<text x="740" y="678" font-family="${FONT}" font-size="11" fill="#4a148c">MAX_CHUNK_SIZE = 400</text>`;
  b += `<text x="740" y="697" font-family="${FONT}" font-size="11" fill="#4a148c">OVERLAP_SIZE = 100</text>`;
  b += `<text x="740" y="716" font-family="${FONT}" font-size="11" fill="#4a148c">分隔符：\\n 。！？ ， ,</text>`;
  b += `<text x="740" y="735" font-family="${FONT}" font-size="11" fill="#4a148c">递归拆分 + 强制截断兜底</text>`;
  b += varrow(cx, 692, 718, '', '#ab47bc');
  b += step(sx, 718, sw, 52, 9, '分块向量化 llmService.embed', ['每批≤10 条 · text-embedding-v4 → 1536 维'], P.domain);
  b += varrow(cx, 770, 798, '', '#ab47bc');
  b += step(sx, 798, sw, 56, 10, '分片与向量入库 saveChunks（事务）', ['雪花ID → text_chunk + text_chunk_vector 批量插入', '+ keywordSearchService.indexChunks → ES 索引'], P.app);
  b += varrow(cx, 854, 882, '', '#fb8c00');
  b += step(sx, 882, sw, 52, 11, '异步任务完成', ['文本 chunk 与向量 chunk 均落库，ES 同步建立倒排'], P.present);

  write('flow_rag_ingest', W, H, b);
  validate(W, H, b);
}

ragIngestFlow();

/* ==================== 4. RAG 检索问答流程 ==================== */
function ragQueryFlow() {
  const W = 960, H = 820;
  const P = PALETTE;
  let b = '';
  b += title(W, 'RAG 检索增强问答流程（混合检索 + 重排）');
  b += footer(W, H, 'embed → pgvector 向量 TopK + ES 关键词 TopK → RRF 融合 → 回表 → rerank → LLM 流式回答');

  const cx = 480;
  b += step(220, 70, 520, 50, 1, '触发 RAG 检索问答', ['意图=KNOWLEDGE_BASE · 原始 query + 改写后 rewrittenQuery'], P.present);
  b += varrow(cx, 120, 146, '', '#1e88e5');
  b += step(220, 146, 520, 50, 2, '确定检索问题 normalizeRetrievalQuestion', ['优先使用改写后的问题 rewrittenQuery'], P.present);
  b += varrow(cx, 196, 222, '', '#1e88e5');
  b += step(220, 222, 520, 50, 3, '查询向量化 llmService.embed', ['retrievalQuestion → float[1536]'], P.domain);
  b += arrow(420, 272, 290, 306, '#43a047');
  b += arrow(540, 272, 670, 306, '#43a047');
  b += box(120, 306, 340, 64, 'pgvector 向量检索', ['textChunkVectorMapper.selectTopKChunkIds', '(vector, 100) → TopK 100'], P.infra, 14, 11.5);
  b += box(500, 306, 340, 64, 'ES 关键词检索', ['keywordSearchService.searchChunkIds', '(retrievalQuestion, 100) → TopK 100'], P.infra, 14, 11.5);
  /* 融合汇合 */
  b += `<line x1="290" y1="370" x2="290" y2="392" stroke="#546e7a" stroke-width="1.8"/>`;
  b += `<line x1="670" y1="370" x2="670" y2="392" stroke="#546e7a" stroke-width="1.8"/>`;
  b += `<line x1="290" y1="392" x2="670" y2="392" stroke="#546e7a" stroke-width="1.8"/>`;
  b += arrow(cx, 392, cx, 414, '#546e7a');
  b += step(220, 414, 520, 54, 4, 'RRF 融合 mergeByRrf', ['score = Σ 1/(60 + rank) · 取 TopK 80'], P.app);
  b += varrow(cx, 468, 494, '', '#fb8c00');
  b += step(220, 494, 520, 50, 5, '回表取 chunk 文本', ['textChunkMapper.selectByChunkIds(chunkIds)'], P.app);
  b += varrow(cx, 544, 570, '', '#fb8c00');
  b += step(220, 570, 520, 54, 6, '重排序 llmService.rerank', ['(retrievalQuestion, chunks, topN=5, minScore=0.7)', '过滤低相关片段，保留 Top5'], P.domain);
  b += varrow(cx, 624, 650, '', '#ab47bc');
  b += step(220, 650, 520, 54, 7, '构建 RAG Prompt', ['参考资料(重排结果) + 会话摘要 + 历史消息', 'PromptBuilder.buildRagPrompt'], P.domain);
  b += varrow(cx, 704, 730, '', '#1e88e5');
  b += step(220, 730, 520, 56, 8, 'LLM 流式回答', ['llmService.chatStream(prompt, onDelta)', '→ SSE delta 事件逐 token 推送'], P.present);

  write('flow_rag_query', W, H, b);
  validate(W, H, b);
}

ragQueryFlow();

/* ==================== 5. ReAct 工具调用编排流程 ==================== */
function reactFlow() {
  const W = 1160, H = 1000;
  const P = PALETTE;
  let b = '';
  b += title(W, 'ReAct 工具调用编排流程（COMPLEX_TASK）');
  b += footer(W, H, 'ToolCallingService.chatWithTasks · 循环上限 10 轮 · 连续失败 3 次熔断 · 工具执行超时 30s');

  const sx = 140, sw = 520, cx = sx + sw / 2;
  const rx = 700, rw = 420;

  b += step(sx, 70, sw, 52, 0, '构建任务 Prompt', ['路由 Prompt → LLM 选工具 → buildTaskPrompt（任务模板）'], P.app);
  b += varrow(cx, 122, 146, '', '#fb8c00');
  b += step(sx, 146, sw, 52, 1, '初始化 ReAct 状态', ['step=1 · MAX_REACT_STEPS=10 · 连续失败计数=0'], P.app);
  b += varrow(cx, 198, 222, '', '#fb8c00');
  b += step(sx, 222, sw, 52, 2, '组装 Prompt（替换 {{react_history}}）', ['任务 + 工具目录 + 会话历史(≤8条) + ReAct 历史'], P.domain);
  b += varrow(cx, 274, 298, '', '#ab47bc');
  b += step(sx, 298, sw, 52, 3, 'LLM 决策 chatModel.chat', ['输出严格 JSON：type=tool 或 type=finish'], P.domain);
  b += varrow(cx, 350, 374, '', '#ab47bc');
  b += step(sx, 374, sw, 52, 4, '解析 JSON parseDecision', ['提取 thought / tool / finish'], P.domain);
  b += arrow(660, 400, 700, 400, '#e53935');
  /* 循环回到步骤 2 的标签 */
  b += sideNote(104, 815, 220, '否 · 下一轮', '#546e7a');
  b += box(rx, 374, rw, 64, 'JSON 非法 / 空', ['失败计数+1 → ≥3 次熔断返回', '“工具暂时不可用，请稍后再试”'], P.external, 13.5, 11);
  b += arrow(cx, 426, cx, 448, '#546e7a');
  b += diamond(cx, 486, 360, 62, 'type = finish ?', P.cross);
  b += arrow(585, 486, 700, 476, '#43a047');
  b += box(rx, 448, rw, 56, '是 → 返回最终答案', ['finish.answer（空则提示无答案）'], P.present, 13.5, 11);
  b += arrow(cx, 514, cx, 560, '#546e7a');
  b += step(sx, 560, sw, 52, 6, '校验工具动作 validateToolAction', ['arguments 与 argument_sources 键完全一致', '必填参数不能仅 HISTORY_ONLY / 缺参'], P.app);
  b += arrow(660, 586, 700, 570, '#ef6c00');
  b += box(rx, 550, rw, 56, '校验不通过', ['返回澄清信息：请用户显式提供/确认参数'], P.external, 13.5, 11);
  b += arrow(cx, 612, cx, 636, '#546e7a');
  b += step(sx, 636, sw, 52, 7, '执行工具 executeTool（30s 超时）', ['CompletableFuture + orTimeout · 线程池 tool-exec'], P.app);
  b += arrow(660, 662, 700, 646, '#e53935');
  b += box(rx, 626, rw, 56, '失败 / 超时 / 取消', ['失败计数+1 → ≥3 次熔断返回', '记录 observation(error) 继续'], P.external, 13.5, 11);
  b += arrow(cx, 688, cx, 712, '#546e7a');
  b += step(sx, 712, sw, 52, 8, '记录 observation 到 steps', ['thought + action + observation（不编造工具结果）'], P.app);
  b += arrow(cx, 764, cx, 786, '#546e7a');
  b += diamond(cx, 824, 380, 62, 'step + 1 > 10 ?', P.cross);
  b += arrow(595, 824, 700, 806, '#ef6c00');
  b += box(rx, 782, rw, 60, '是 → 返回', ['“Too many ReAct steps，请给出更清晰条件”'], P.external, 13.5, 11);
  /* 循环回到步骤 2 */
  b += arrow(220, 824, 80, 824, '#546e7a');
  b += `<line x1="80" y1="824" x2="80" y2="249" stroke="#546e7a" stroke-width="1.8"/>`;
  b += arrow(80, 249, 140, 249, '#546e7a');
  b += sideNote(84, 545, 220, 'ReAct 循环 ≤10 轮', '#546e7a');
  b += varrow(cx, 852, 888, '', '#fb8c00');
  b += step(sx, 888, sw, 52, 10, '返回最终答案', ['finish 答案 或 熔断信息'], P.present);

  write('flow_react_tool', W, H, b);
  validate(W, H, b);
}

reactFlow();

/* ==================== 6. 健康巡检流程 ==================== */
function healthcheckFlow() {
  const W = 1080, H = 980;
  const P = PALETTE;
  let b = '';
  b += title(W, '数据库健康巡检流程（定时 + LLM + 规则兜底）');
  b += footer(W, H, 'HealthCheckRunner · @Scheduled 触发 → ReAct 巡检 → 规则兜底补发邮件 → HealthCheckRecord 落库 + Trace 持久化');

  const sx = 180, sw = 560, cx = sx + sw / 2;
  const rx = 780, rw = 260;

  b += step(sx, 70, sw, 52, 1, '触发巡检', ['@Scheduled(cron=每分钟) 或 POST /healthcheck/run 手动'], P.app);
  b += sideNote(780, 96, 260, 'agent.healthcheck.enabled=false → 直接返回', '#c62828');
  b += varrow(cx, 122, 150, '', '#fb8c00');
  b += step(sx, 150, sw, 52, 2, '读取配置 agent.healthcheck', ['targets（待巡检实例） + thresholds（异常阈值）'], P.app);
  b += varrow(cx, 202, 230, '', '#fb8c00');
  b += step(sx, 230, sw, 56, 3, '构建巡检 Prompt buildPrompt', ['实例列表 / 可用工具 / 阈值标准 / 执行步骤 / 输出格式'], P.app);
  b += varrow(cx, 286, 314, '', '#fb8c00');
  b += step(sx, 314, sw, 52, 4, 'LLM ReAct 巡检 runLlmReAct', ['ToolCallingService.chatWithTasks（带 Trace 采集）'], P.domain);
  b += box(rx, 314, rw, 122, 'ReAct 循环内', ['· checkDatabaseHealth 取实时指标', '· LLM 依阈值判定异常', '· sendAlertEmail 发告警邮件', '· 返回巡检总结 llmSummary'], P.domain, 13, 11);
  b += varrow(cx, 366, 394, '', '#ab47bc');
  b += step(sx, 394, sw, 52, 5, '对每个实例执行以下循环', ['persistAndFallback(runId, target, llmSummary)'], P.app);
  b += `<rect x="150" y="474" width="620" height="310" rx="10" fill="none" stroke="#78909c" stroke-width="1.3" stroke-dasharray="6,4"/>`;
  b += `<text x="160" y="494" font-family="${FONT}" font-size="12.5" font-weight="bold" fill="#455a64">循环：对每个配置的 Target 实例</text>`;
  b += step(sx, 506, sw, 50, 6, '采集实时指标', ['DatabaseMetricsCollector.collect(target)'], P.app);
  b += sideNote(780, 530, 260, 'activeSessions / bufferHitRate / lockWaitingSessions / idleInTransaction / deadTupleRatio / replicationLag…', '#455a64');
  b += varrow(cx, 556, 578, '', '#fb8c00');
  b += step(sx, 578, sw, 50, 7, '规则兜底判定', ['AnomalyEvaluator.evaluate(metrics, thresholds)'], P.app);
  b += varrow(cx, 628, 650, '', '#fb8c00');
  b += step(sx, 650, sw, 50, 8, '判定并落库 HealthCheckRecord', ['status: NORMAL / ANOMALY / ERROR · abnormalMetrics JSON'], P.app);
  b += varrow(cx, 700, 722, '', '#fb8c00');
  b += step(sx, 722, sw, 50, 9, '邮件补发兜底', ['LLM 未发(wasSent=false) 且异常/连接错误 → sendFallback'], P.app);
  b += sideNote(780, 744, 260, '发送成功 → sendRegistry.markSent(runId, instance)', '#00695c');
  b += varrow(cx, 772, 812, '', '#fb8c00');
  b += step(sx, 812, sw, 52, 10, '持久化 Trace', ['traceService.save(collector, SUCCESS/ERROR)'], P.app);
  b += varrow(cx, 864, 892, '', '#fb8c00');
  b += step(sx, 892, sw, 52, 11, '返回巡检结果', ['runId + llmSummary + 各实例 details'], P.present);

  write('flow_healthcheck', W, H, b);
  validate(W, H, b);
}

healthcheckFlow();

/* ==================== 7. 认证鉴权流程 ==================== */
function authFlow() {
  const W = 960, H = 830;
  const P = PALETTE;
  let b = '';
  b += title(W, '认证鉴权流程（JWT Token + Redis）');
  b += footer(W, H, 'AuthController + AuthService + AuthTokenFilter · Token 存 Redis（agent:login:token:*，TTL 604800s）');

  const sx = 220, sw = 520, cx = sx + sw / 2;
  b += step(sx, 70, sw, 52, 1, '注册 POST /auth/register', ['AuthService.register · 用户名密码校验'], P.api);
  b += sideNote(780, 96, 160, 'BCrypt 加密存 agent_user', '#2e7d32');
  b += varrow(cx, 122, 150, '', '#43a047');
  b += step(sx, 150, sw, 52, 2, '登录 POST /auth/login', ['校验用户名密码 → 生成 Token（TokenUtil）'], P.api);
  b += varrow(cx, 202, 230, '', '#43a047');
  b += step(sx, 230, sw, 56, 3, 'Token 写入 Redis', ['key=agent:login:token:{token} · TTL=604800s（7天）'], P.app);
  b += sideNote(780, 258, 160, '返回 LoginResponse(token, userInfo)', '#2e7d32');
  b += varrow(cx, 286, 314, '', '#fb8c00');
  b += step(sx, 314, sw, 52, 4, '后续请求携带 Token', ['Authorization: Bearer {token} 或 X-Auth-Token'], P.api);
  b += varrow(cx, 366, 394, '', '#43a047');
  b += step(sx, 394, sw, 52, 5, 'AuthTokenFilter 拦截解析', ['解析 Token → Redis 校验 → 组装 UserContext'], P.cross);
  b += sideNote(780, 420, 160, '校验失败 → 401 未授权', '#c62828');
  b += varrow(cx, 446, 474, '', '#43a047');
  b += step(sx, 474, sw, 56, 6, '写入 UserContextHolder（ThreadLocal）', ['后续 Service 通过 getUserId() 获取当前用户'], P.cross);
  b += varrow(cx, 530, 558, '', '#fb8c00');
  b += step(sx, 558, sw, 52, 7, '访问业务接口', ['/agent/chat · /agent/conversations · /healthcheck/run 等'], P.app);
  b += varrow(cx, 610, 638, '', '#fb8c00');
  b += step(sx, 638, sw, 52, 8, '退出登录 POST /auth/logout', ['删除 Redis 中的 Token 记录'], P.api);
  b += varrow(cx, 690, 718, '', '#fb8c00');
  b += step(sx, 718, sw, 52, 9, 'GET /auth/me 查询当前用户', ['从 UserContextHolder 返回用户信息'], P.api);

  write('flow_auth', W, H, b);
  validate(W, H, b);
}

authFlow();

/* ==================== 8. SSE 流式对话流程 ==================== */
function streamingFlow() {
  const W = 1000, H = 1060;
  const P = PALETTE;
  let b = '';
  b += title(W, 'SSE 流式对话流程（打字机效果）');
  b += footer(W, H, 'AgentReactiveController/AgentController → SseEmitter(120s) → 后台线程：meta → status → delta* → done/error');

  const sx = 220, sw = 560, cx = sx + sw / 2;
  b += step(sx, 70, sw, 52, 1, 'POST /agent/chat/stream（SSE）', ['AgentController.chatStream / AgentReactiveController'], P.api);
  b += varrow(cx, 122, 150, '', '#43a047');
  b += step(sx, 150, sw, 52, 2, '参数解析 + 鉴权', ['query/conversationId；UserContextHolder 无用户 → error 事件'], P.api);
  b += varrow(cx, 202, 230, '', '#43a047');
  b += step(sx, 230, sw, 52, 3, '解析 / 创建会话', ['未传 conversationId → 自动创建'], P.app);
  b += varrow(cx, 282, 310, '', '#fb8c00');
  b += step(sx, 310, sw, 56, 4, '创建 SseEmitter（120s）+ 采集器', ['后台线程 CompletableFuture.runAsync', '恢复 Trace Span + MDC'], P.app);
  b += varrow(cx, 366, 394, '', '#fb8c00');
  b += step(sx, 394, sw, 52, 5, '加载历史 + 摘要', ['listRecentMessages(20) + getSummary'], P.app);
  b += varrow(cx, 446, 474, '', '#fb8c00');
  b += step(sx, 474, sw, 52, 6, '意图识别 + 查询改写', ['规则/LLM 识别 · KNOWLEDGE_BASE 才改写（记录 INTENT 调用）'], P.domain);
  b += varrow(cx, 526, 554, '', '#ab47bc');
  b += step(sx, 554, sw, 56, 7, '发送 meta + status 事件', ['meta(conversationId) → status(intentType, rewrittenQuery)'], P.cross);
  b += varrow(cx, 610, 638, '', '#ab47bc');
  b += step(sx, 638, sw, 56, 8, '流式路由 routeStream', ['SIMPLE_CHAT/KNOWLEDGE_BASE → chatStream 逐 token', 'COMPLEX_TASK → ReAct 后按 6 字符分块'], P.domain);
  b += varrow(cx, 694, 722, '', '#1e88e5');
  b += step(sx, 722, sw, 52, 9, '发送 delta 事件', ['每次收到 token → sendEvent(delta, content)'], P.present);
  b += varrow(cx, 774, 802, '', '#fb8c00');
  b += step(sx, 802, sw, 52, 10, '保存消息 + 持久化 Trace', ['短期记忆落库 · traceService.save(SUCCESS)'], P.app);
  b += varrow(cx, 854, 882, '', '#fb8c00');
  b += step(sx, 882, sw, 52, 11, '发送 done 事件', ['done(conversationId, answer)；异常 → error 事件'], P.cross);
  b += varrow(cx, 934, 962, '', '#43a047');
  b += step(sx, 962, sw, 52, 12, '前端打字机渲染 SSE', ['事件序列：meta → status → delta* → done/error'], P.present);

  b += box(800, 720, 180, 96, 'SSE 事件序列', ['meta', 'status', 'delta（多个）', 'done / error'], P.cross, 13, 12);

  write('flow_streaming', W, H, b);
  validate(W, H, b);
}

streamingFlow();

/* ==================== 9. 会话与记忆子系统框图 ==================== */
function memoryDiagram() {
  const W = 1060, H = 860;
  const P = PALETTE;
  let b = '';
  b += title(W, '会话与记忆子系统框图');
  b += footer(W, H, '短期记忆(agent_short_term_memory) + 会话摘要(agent_conversation_summary) + 会话管理(agent_conversation) · 存储于 PostgreSQL');

  /* 顶部：对话主流程触发 */
  b += box(330, 66, 400, 54, '对话主流程（AgentServiceImpl / ReactiveStreamingService）', ['每轮：读历史 + 读摘要 + 写消息 + 触发摘要'], P.app, 14.5, 11.5);
  b += arrow(530, 120, 190, 168, '#546e7a');
  b += arrow(530, 120, 530, 168, '#546e7a');
  b += arrow(530, 120, 870, 168, '#546e7a');

  /* 三个服务 */
  b += box(70, 168, 240, 260, 'ShortTermMemoryService', ['listRecentMessages(userId, convId, 20)', 'saveUserAndAssistantMessages', 'saveMessage(role, content, metadata)', 'estimateTokenCount(content)'], P.domain, 14.5, 11.5);
  b += box(410, 168, 240, 260, 'ConversationSummaryService', ['getSummary(userId, convId)', 'triggerSummaryIfNeeded（@Async）', '消息>20 且新增≥10 → LLM 生成', 'covered_message_order 增量更新'], P.domain, 14.5, 11.5);
  b += box(750, 168, 240, 260, 'ConversationService', ['createConversation / list / get', 'updateTitle / delete', 'validateConversationOwner', 'touchConversation（更新活跃时间）'], P.domain, 14.5, 11.5);

  /* 存储映射 */
  b += arrow(190, 428, 190, 470, '#78909c');
  b += arrow(530, 428, 530, 470, '#78909c');
  b += arrow(870, 428, 870, 470, '#78909c');
  b += box(70, 470, 240, 92, 'agent_short_term_memory', ['userId + conversationId 隔离', 'role / content / message_order', 'token_count / metadata(JSONB)'], P.infra, 14, 11.5);
  b += box(410, 470, 240, 92, 'agent_conversation_summary', ['userId + conversationId', 'summary（≤2000 字，中文）', 'covered_message_order'], P.infra, 14, 11.5);
  b += box(750, 470, 240, 92, 'agent_conversation', ['会话标题 / 创建时间', '最近活跃时间 / 消息数'], P.infra, 14, 11.5);

  /* 底部说明 */
  b += `<rect x="70" y="600" width="920" height="210" rx="10" fill="#f4f6f8" stroke="#78909c" stroke-width="1"/>`;
  b += `<text x="90" y="628" font-family="${FONT}" font-size="13.5" font-weight="bold" fill="#455a64">记忆策略要点</text>`;
  b += `<text x="90" y="654" font-family="${FONT}" font-size="12" fill="#546e7a">· 回答阶段读取最近 20 条短期记忆；意图识别阶段仅取最近 5 条（减少上下文噪声）。</text>`;
  b += `<text x="90" y="676" font-family="${FONT}" font-size="12" fill="#546e7a">· 会话摘要用于承载超出 20 条窗口的长期信息：每轮对话后触发 triggerSummaryIfNeeded，增量压缩。</text>`;
  b += `<text x="90" y="698" font-family="${FONT}" font-size="12" fill="#546e7a">· 摘要逻辑：保留稳定目标/偏好/约束/决策/关键实体/文件名/接口名/参数/错误与未决问题，丢弃寒暄。</text>`;
  b += `<text x="90" y="720" font-family="${FONT}" font-size="12" fill="#546e7a">· 会话标题：首次提问截取前 20 字；未传 conversationId 时自动创建会话。</text>`;
  b += `<text x="90" y="742" font-family="${FONT}" font-size="12" fill="#546e7a">· 短期记忆元数据：记录 intentType、rewrittenQuery、queryRewritten 供后续检索与回溯。</text>`;
  b += `<text x="90" y="764" font-family="${FONT}" font-size="12" fill="#546e7a">· Redis 仅用于登录 Token；会话消息与摘要均落 PostgreSQL，保证可查询与恢复。</text>`;
  b += `<text x="90" y="786" font-family="${FONT}" font-size="12" fill="#546e7a">· 历史消息上限 200 条供前端展示；删除会话采用软删除（deleted 标记）。</text>`;

  write('diagram_memory', W, H, b);
  validate(W, H, b);
}

memoryDiagram();

/* ==================== 10. 可观测性子系统框图 ==================== */
function observabilityDiagram() {
  const W = 1100, H = 860;
  const P = PALETTE;
  let b = '';
  b += title(W, '可观测性子系统框图（Trace / LLM / 工具调用）');
  b += footer(W, H, 'ConversationTraceCollector → ConversationTraceService.save → conversation_trace + llm_call_record + tool_call_record · ObservabilityController 查询');

  /* 采集源头 */
  b += box(70, 70, 300, 86, '数据采集源头', ['ReactiveStreamingService / AgentServiceImpl', '每轮对话创建 collector'], P.present, 14.5, 11.5);
  b += box(400, 70, 300, 86, '定时巡检 ReAct', ['HealthCheckRunner 巡检同样使用', 'collector 记录 LLM/工具调用'], P.present, 14.5, 11.5);
  b += box(730, 70, 300, 86, '全链路追踪（横切）', ['TracingFilter + Micrometer Tracing', 'OpenTelemetry OTLP 导出 · baggage: userId/conversationId'], P.cross, 14.5, 11.5);

  b += arrow(220, 156, 220, 200, '#546e7a');
  b += arrow(550, 156, 550, 200, '#546e7a');
  b += arrow(880, 156, 880, 200, '#546e7a');

  /* Collector 核心 */
  b += box(70, 200, 960, 120, 'ConversationTraceCollector（单次对话轮次）', ['recordLlmCall(modelName, callType, prompt, response, tokens, duration)', 'recordToolCall(toolName, input, output, success, duration)', 'setIntentType · getSnapshot(status, error) → ConversationTraceSnapshot'], P.domain, 15, 12.5);

  b += arrow(550, 320, 550, 364, '#78909c');

  /* 持久化服务 */
  b += box(70, 364, 960, 96, 'ConversationTraceService.save（事务）', ['conversation_trace：汇总（duration/tokens/llmCount/toolCount/status/intentType）', 'llm_call_record：LLM 调用明细（callSequence/callType/input/output/tokens）', 'tool_call_record：工具调用明细（toolName/input/output/success/duration）'], P.app, 15, 12.5);

  b += arrow(550, 460, 550, 504, '#546e7a');

  /* 查询层 */
  b += box(70, 504, 440, 96, 'ObservabilityController', ['GET /agent/observability/traces（用户 Trace 列表）', 'GET /agent/observability/traces/{id}（详情）', 'GET /agent/observability/stats（汇总统计）'], P.api, 14.5, 11.5);
  b += box(550, 504, 480, 96, '前端 observability.html', ['Trace 列表 / 详情查看', 'LLM 调用次数与 token 统计', '工具调用成功率与耗时'], P.present, 14.5, 11.5);

  b += arrow(290, 600, 290, 646, '#78909c');
  b += arrow(790, 600, 790, 646, '#78909c');

  /* 底层表 */
  b += box(70, 646, 220, 96, 'conversation_trace', ['traceId / userId', 'userQuery / intentType', 'totalDurationMs / totalTokens'], P.infra, 14, 11.5);
  b += box(330, 646, 220, 96, 'llm_call_record', ['traceId / callSequence', 'modelName / callType', 'inputTokens / outputTokens'], P.infra, 14, 11.5);
  b += box(590, 646, 220, 96, 'tool_call_record', ['traceId / callSequence', 'toolName / toolInput', 'success / durationMs'], P.infra, 14, 11.5);
  b += box(850, 646, 180, 96, 'PostgreSQL', ['业务库 rag_db 持久化'], P.infra, 14, 11.5);

  /* 底部说明 */
  b += `<rect x="70" y="772" width="960" height="52" rx="8" fill="#fffde7" stroke="#f9a825" stroke-width="1"/>`;
  b += `<text x="90" y="796" font-family="${FONT}" font-size="12.5" fill="#7a5900">可观测覆盖范围：每轮对话（LLM 调用：INTENT/ROUTE/RAG_GENERATE/SIMPLE_CHAT/REACT_LLM；工具调用：全部 @Tool）</text>`;
  b += `<text x="90" y="814" font-family="${FONT}" font-size="12.5" fill="#7a5900">· 巡检（scheduled-healthcheck） · 前端以 observability.html 展示，Actuator 暴露 health/info/tracings 端点</text>`;

  write('diagram_observability', W, H, b);
  validate(W, H, b);
}

observabilityDiagram();
