// 开启严格模式，避免误写全局变量等隐式行为。
'use strict';
// 用元素 ID 查找 DOM 节点，后续事件和状态更新共用此助手。
const $ = id => document.getElementById(id);
// 从本机浏览器存储恢复会话 ID，让刷新后继续同一后台会话。
let sessionId = localStorage.getItem('enterprise-session');
// 记录问答是否进行中，阻止并发提交同一会话。
let sending = false;
// 记录知识索引是否重建中，避免上传和查询同时修改知识状态。
let indexing = false;
// 待发送图片保留 File 和上传后的 ID，失败重试无需重复上传。
let pendingImages = [];
// 统一发送 HTTP 请求、解析 JSON 并处理后端错误。
async function request(url, options = {}) {
  // 等待 HTTP 响应，options 可指定 GET/POST、请求头和请求体。
  const response = await fetch(url, options);
  // 尝试读取 JSON；无法解析时保留 null，后面给出明确错误。
  const data = await response.json().catch(() => null);
  // HTTP 非成功状态抛出后端安全错误信息，缺失时使用状态码说明。
  if (!response.ok) throw new Error(data?.error || `请求失败（${response.status}），请检查服务后重试`);
  // 响应即便 HTTP 成功，也必须包含有效 JSON，不能误报处理完成。
  if (!data) throw new Error('服务未返回有效数据，请检查服务后重试');
  // 把已校验的响应对象交给具体页面操作。
  return data;
}
// 在指定反馈节点显示状态文本，并按 error 开关切换错误样式。
function feedback(id, text, error = false) {
  // 以纯文本写入提示内容，避免将服务端字符串作为 HTML 执行。
  $(id).textContent = text;
  // 添加或移除 error 类，让错误提示使用对应颜色。
  $(id).classList.toggle('error', error);
}
// 读取后台知识库状态，同步片段数、就绪标记与说明。
async function refresh() {
  // 把可能失败的请求放入保护范围，对应 catch 负责展示可理解的提示。
  try {
    // 调用只读状态接口，读取真实索引状态。
    const data = await request('/api/knowledge/status');
    // 把 ready 布尔值转换为中文就绪标记。
    $('status').textContent = data.ready ? '已就绪' : '未就绪';
    // 未就绪状态启用错误颜色，提醒先入库或重建。
    $('status').classList.toggle('error', !data.ready);
    // 显示后端实际分块计数，不从前端文件数量估算。
    $('chunk-count').textContent = data.chunkCount;
    // 优先显示后端重建说明，否则根据 ready 提供操作指引。
    $('status-detail').textContent = data.message || (data.ready ? '可以开始提问' : '请上传资料或重建知识库');
  // 把 HTTP 或解析失败转换为页面反馈，保留界面操作入口。
  } catch (error) {
    // 状态请求失败时显示连接问题，避免继续显示陈旧的就绪标记。
    $('status').textContent = '连接失败';
    // 连接失败时强调状态标记。
    $('status').classList.add('error');
    // 计数未知时显示占位符，不能伪装为零条知识。
    $('chunk-count').textContent = '—';
    // 展示已由请求封装整理的错误原因。
    $('status-detail').textContent = error.message;
  }
}
// 按问答和建库状态统一禁用按钮，减少重复提交和冲突操作。
function controls() {
  // 问答或索引更新期间禁止再次发送问题。
  $('send').disabled = sending || indexing;
  // 当前问答结束前禁止切换会话，防止回答显示到错误对话。
  $('new-chat').disabled = sending;
  // 索引更新或问答期间禁止再次上传。
  $('upload').disabled = indexing || sending;
  // 避免重建操作与当前索引更新或问答重叠。
  $('rebuild').disabled = indexing || sending;
  // 重建进行中禁止更换待上传文件。
  $('file').disabled = indexing;
  $('choose-images').disabled = sending || indexing;
  $('chat-images').disabled = sending || indexing;
  $('question').disabled = sending;
  document.querySelectorAll('.image-remove').forEach(button => { button.disabled = sending || indexing; });
}
function imageUrl(id, conversationId = sessionId) {
  return `/api/sessions/${encodeURIComponent(conversationId)}/images/${encodeURIComponent(id)}`;
}

function clearPendingImages() {
  pendingImages.forEach(item => URL.revokeObjectURL(item.preview));
  pendingImages = [];
  renderImagePreviews();
}

function renderImagePreviews() {
  const container = $('image-previews');
  container.replaceChildren();
  container.hidden = pendingImages.length === 0;
  for (const item of pendingImages) {
    const tile = document.createElement('div'); tile.className = 'image-preview';
    const img = document.createElement('img'); img.src = item.preview; img.alt = item.file.name;
    const remove = document.createElement('button'); remove.type = 'button'; remove.className = 'image-remove';
    remove.textContent = '×'; remove.setAttribute('aria-label', `移除 ${item.file.name}`);
    remove.disabled = sending || indexing;
    remove.addEventListener('click', () => {
      if (sending || indexing) return;
      URL.revokeObjectURL(item.preview);
      pendingImages = pendingImages.filter(candidate => candidate !== item);
      renderImagePreviews();
    });
    tile.append(img, remove); container.append(tile);
  }
}

function selectImages(files) {
  if (sending || indexing) return;
  const selected = Array.from(files);
  if (!selected.length) return;
  if (selected.length + pendingImages.length > 4) {
    feedback('chat-feedback', '每次最多发送 4 张图片。', true); return;
  }
  if (selected.some(file => !['image/png', 'image/jpeg'].includes(file.type) || file.size === 0 || file.size > 5 * 1024 * 1024)) {
    feedback('chat-feedback', '请选择非空的 PNG/JPEG 图片，每张不超过 5 MB。', true); return;
  }
  pendingImages.push(...selected.map(file => ({ file, preview: URL.createObjectURL(file), id: null })));
  renderImagePreviews(); feedback('chat-feedback', '');
}

$('choose-images').addEventListener('click', () => $('chat-images').click());
$('chat-images').addEventListener('change', () => {
  selectImages($('chat-images').files); $('chat-images').value = '';
});
$('question').addEventListener('paste', event => {
  const files = Array.from(event.clipboardData?.files || []);
  if (files.length) { event.preventDefault(); selectImages(files); }
});
for (const name of ['dragenter', 'dragover']) $('chat-form').addEventListener(name, event => {
  if (!Array.from(event.dataTransfer.types).includes('Files')) return;
  event.preventDefault(); if (!sending && !indexing) $('chat-form').classList.add('dragging');
});
$('chat-form').addEventListener('dragleave', () => $('chat-form').classList.remove('dragging'));
$('chat-form').addEventListener('drop', event => {
  event.preventDefault(); $('chat-form').classList.remove('dragging'); selectImages(event.dataTransfer.files);
});
// 上传前快速检查扩展名和大小；后台仍会独立提取正文并验证内容。
function validateFile(file) {
  // 没有选择文件时拒绝上传，给出直接操作指引。
  if (!file) throw new Error('请先选择文件');
  // 只允许 PDF/Markdown，与后台文档读取规则一致。
  if (!/\.(pdf|md)$/i.test(file.name)) throw new Error('目前支持 PDF 和 Markdown 文件');
  // 拒绝空文件和超过 5 MB 的文件，与后端限制一致。
  if (file.size === 0 || file.size > 5 * 1024 * 1024) throw new Error('请选择非空且不超过 5 MB 的文件');
}
// 选择文件发生变化时，更新文件名和大小预览。
$('file').addEventListener('change', () => {
  // 读取当前单个待上传文件，不默认接收多文件批次。
  const file = $('file').files[0];
  // 显示文件名与 KB 大小，未选择时显示占位提示。
  $('selected-file').textContent = file ? `${file.name} · ${(file.size / 1024).toFixed(1)} KB` : '尚未选择文件';
});
// 拖入或在拖放区上方移动文件时处理高亮状态。
for (const event of ['dragenter', 'dragover']) $('drop-zone').addEventListener(event, e => {
  // 阻止浏览器直接打开拖入文件，仅在未建库时添加高亮。
  e.preventDefault(); if (!indexing) $('drop-zone').classList.add('dragging');
});
// 文件离开或完成拖放时恢复上传区外观。
for (const event of ['dragleave', 'drop']) $('drop-zone').addEventListener(event, e => {
  // 阻止默认文件打开行为并移除拖放高亮。
  e.preventDefault(); $('drop-zone').classList.remove('dragging');
});
// 放下文件时把它转移到文件输入框，复用普通上传流程。
$('drop-zone').addEventListener('drop', e => {
  // 索引更新过程中忽略拖放，避免改变进行中的上传内容。
  if (indexing) return;
  // 把可能失败的请求放入保护范围，对应 catch 负责展示可理解的提示。
  try {
    // 拖放也只允许一个文件，保持后台单文件上传接口的约定。
    if (e.dataTransfer.files.length !== 1) throw new Error('请一次上传一份资料');
    // 复用文件类型和大小校验，非法文件不能进入输入框。
    validateFile(e.dataTransfer.files[0]);
    // 让文件输入框持有拖入文件，后续 FormData 从同一位置读取。
    $('file').files = e.dataTransfer.files;
    // 主动触发选择变化事件，复用文件名和大小预览逻辑。
    $('file').dispatchEvent(new Event('change'));
    // 拖放只完成选择，提醒用户点击上传按钮才真正入库。
    feedback('upload-feedback', '文件已选择，点击“上传并入库”继续');
  // 校验失败时在上传区反馈，不向后台发送非法文件。
  } catch (error) { feedback('upload-feedback', error.message, true); }
});
// 处理上传入库或纯重建，并统一维护建库状态。
async function indexKnowledge(file) {
  // 已有操作时直接退出，防止知识读写状态冲突。
  if (indexing || sending) return;
  // 标记正在重建并立即禁用相关控件。
  indexing = true; controls();
  // 提示 Embedding 和双索引写入正在进行，不把请求发送当作入库完成。
  feedback('upload-feedback', '正在处理资料并写入知识库，请保持页面打开…');
  // 先更新状态徽标，表示本轮重建尚未发布 ready。
  $('status').textContent = '处理中';
  // 把可能失败的请求放入保护范围，对应 catch 负责展示可理解的提示。
  try {
    // 上传和重建都使用 POST，默认暂不携带请求体。
    let options = { method: 'POST' };
    // 有文件时用 multipart FormData，字段名 file 必须与后台一致。
    if (file) { const body = new FormData(); body.append('file', file); options.body = body; }
    // 根据有无文件选择上传或重建接口，等待后台完成整个索引发布。
    const data = await request(file ? '/api/knowledge/upload' : '/api/knowledge/rebuild', options);
    // 只有成功响应后才展示后台新的知识分块计数。
    feedback('upload-feedback', `入库完成，知识库现有 ${data.chunkCount} 个片段。可以开始提问。`);
    // 成功上传后清空文件选择，避免用户误重复提交同一文件。
    if (file) { $('upload-form').reset(); $('selected-file').textContent = '尚未选择文件'; }
  // 把 HTTP 或解析失败转换为页面反馈，保留界面操作入口。
  } catch (error) {
    // 提示文件可能已保存但索引未完成，修复后重建可避免重复上传。
    feedback('upload-feedback', `${error.message}。若资料已保存但索引失败，请修复服务连接后点击“重建知识库 / 失败后重试”，避免重复上传。`, true);
  // 不论成功或失败都解除建库标记，并重新读取后台状态。
  } finally { indexing = false; controls(); await refresh(); }
}
// 提交上传表单时运行本地校验和异步入库。
$('upload-form').addEventListener('submit', e => {
  // 阻止表单默认跳页或重新加载，用 JavaScript 接管提交流程。
  e.preventDefault();
  // 读取文件、校验并启动入库；void 表明当前事件不等待返回值。
  try { const file = $('file').files[0]; validateFile(file); void indexKnowledge(file); }
  // 本地校验失败直接显示，尚未进入异步入库。
  catch (error) { feedback('upload-feedback', error.message, true); }
});
// 点击重建按钮时不传文件，复用 indexKnowledge 的重建分支。
$('rebuild').addEventListener('click', () => indexKnowledge());
// 手动刷新按钮只读取后台状态，不修改知识库。
$('refresh').addEventListener('click', refresh);
// 创建一条纯文本用户或助手消息，并滚动到对话底部。
function addMessage(role, text, imageIds = [], conversationId = sessionId) {
  // 出现真实消息后隐藏欢迎区，避免与对话混在一起。
  $('welcome').hidden = true;
  // 创建消息容器并按 user/assistant 角色选择展示样式。
  const article = document.createElement('article'); article.className = `message ${role}`;
  // 创建角色标签，区分用户提问与助手回答。
  const label = document.createElement('div'); label.className = 'message-label'; label.textContent = role === 'user' ? '你' : '知答 · 助手';
  // 正文用 textContent 写入，服务端输出不会作为 HTML 执行。
  const body = document.createElement('div'); body.className = 'message-body'; body.textContent = text;
  // 把角色与正文加入消息区，滚动到新消息，并返回容器以便稍后更新。
  article.append(label, body);
  if (imageIds.length) {
    const gallery = document.createElement('div'); gallery.className = 'message-images';
    for (const id of imageIds) {
      const link = document.createElement('a'); link.href = imageUrl(id, conversationId); link.target = '_blank'; link.rel = 'noopener';
      const img = document.createElement('img'); img.src = link.href; img.alt = '用户上传的图片'; img.loading = 'lazy';
      img.addEventListener('load', scrollMessages);
      img.addEventListener('error', () => { img.replaceWith(document.createTextNode('图片无法加载，请重新上传')); });
      link.append(img); gallery.append(link);
    }
    article.append(gallery);
  }
  $('messages').append(article); scrollMessages(); return article;
}
// 将滚动位置设为消息区总高度，让最新内容可见。
function scrollMessages() { $('messages').scrollTop = $('messages').scrollHeight; }
// 给回答添加可展开的来源或处理步骤列表。
function addDetails(article, title, items) {
  // 没有来源或步骤时不创建空折叠区域。
  if (!items?.length) return;
  // 使用原生 details 控件，不需要额外组件处理展开与关闭。
  const details = document.createElement('details');
  // 设置折叠标题及条数，让用户先了解附加信息规模。
  const summary = document.createElement('summary'); summary.textContent = `${title}（${items.length}）`;
  // 用无序列表承载各条来源或步骤。
  const list = document.createElement('ul');
  // 每条列表内容以纯文本渲染，避免引用字符串注入页面。
  for (const item of items) { const li = document.createElement('li'); li.textContent = item; list.append(li); }
  // 组装折叠标题与列表，再挂到对应回答下方。
  details.append(summary, list); article.append(details);
}
// 发送问题时异步调用 chat 接口，并在现有消息位置更新结果。
$('chat-form').addEventListener('submit', async e => {
  // 阻止表单默认跳页或重新加载，用 JavaScript 接管提交流程。
  e.preventDefault();
  // 去除问题两端空白，避免提交看起来空但仍含空格的输入。
  const message = $('question').value.trim();
  // 空问题或当前已有问答/建库时不发起请求。
  if (sending || indexing) return;
  if (!message && !pendingImages.length) { feedback('chat-feedback', '请输入问题或选择图片。', true); return; }
  // 客户端提前生成 ID，使网络失败后重试仍然沿用同一会话。
  // 首次发问前生成会话 ID；失败重试仍沿用同一 ID，避免创建重复历史。
  sessionId ||= crypto.randomUUID();
  // 标记问答进行中、禁用冲突按钮并清理旧反馈。
  sending = true; controls(); feedback('chat-feedback', '');
  // 立刻显示用户问题并清空编辑框，让用户看到已提交内容。
  const selected = [...pendingImages];
  let answer;
  // 先建立助手占位消息，后台返回后在同一容器替换正文。
  // 把可能失败的请求放入保护范围，对应 catch 负责展示可理解的提示。
  try {
    if (selected.length) feedback('chat-feedback', '正在上传图片…');
    for (const item of selected) {
      if (item.id) continue;
      const form = new FormData(); form.append('sessionId', sessionId); form.append('file', item.file);
      const uploaded = await request('/api/images', { method: 'POST', body: form });
      item.id = uploaded.id;
    }
    const imageIds = selected.map(item => item.id);
    addMessage('user', message, imageIds); $('question').value = '';
    answer = addMessage('assistant', '正在分析问题并整理回答…');
    feedback('chat-feedback', '');
    // 以 JSON 提交固定会话 ID 与问题，等待来源、步骤及回答。
    const data = await request('/api/chat', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ sessionId, message, imageIds }) });
    clearPendingImages();
    // 使用后台确认的会话 ID，并保存到 localStorage 供刷新恢复。
    sessionId = data.sessionId; localStorage.setItem('enterprise-session', sessionId);
    // 用实际答案替换占位消息，继续保持纯文本渲染。
    answer.querySelector('.message-body').textContent = data.answer;
    // 分别展示本轮可核对的引用来源与公开执行步骤。
    addDetails(answer, '引用来源', data.sources); addDetails(answer, '处理步骤', data.steps);
    // 创建耗时显示节点，与答案正文分开。
    const timing = document.createElement('div'); timing.className = 'timing';
    // 展示检索毫秒数和完整回答秒数，两者统计口径不同。
    timing.textContent = `检索 ${data.retrievalMs} ms · 总耗时 ${(data.totalMs / 1000).toFixed(1)} s`; answer.append(timing);
  // 把 HTTP 或解析失败转换为页面反馈，保留界面操作入口。
  } catch (error) {
    // 失败时更新当前占位消息，让用户明确这次没有取得回答。
    if (answer) answer.querySelector('.message-body').textContent = `未能取得回答：${error.message}`;
    // 编辑框仍为空时恢复失败问题，避免覆盖用户已开始输入的新内容。
    if (!$('question').value.trim()) $('question').value = message;
    // 保留问题可重试状态，引导检查服务后重新提交。
    feedback('chat-feedback', `${error.message}；问题和图片已保留，可重新发送。`, true);
  // 总是解除发送状态、刷新按钮和滚动位置，并把焦点还给编辑框。
  } finally { sending = false; controls(); scrollMessages(); $('question').focus(); }
});
// 处理快捷发送，同时尊重中文输入法组合状态。
$('question').addEventListener('keydown', e => {
  // 普通 Enter 提交、Shift+Enter 保留换行、输入法组合时不误发送。
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); $('chat-form').requestSubmit(); }
});
// 开始新对话时清理浏览器会话标记与当前消息展示。
$('new-chat').addEventListener('click', () => {
  clearPendingImages();
  // 删除本地会话 ID 和页面消息；后台旧会话文件仍可按 ID 恢复。
  sessionId = null; localStorage.removeItem('enterprise-session'); document.querySelectorAll('.message').forEach(node => node.remove());
  // 恢复欢迎区、清空编辑框及反馈，并准备下一次输入。
  $('welcome').hidden = false; $('question').value = ''; feedback('chat-feedback', ''); $('question').focus();
});
// 为每个示例问题按钮注册事件，只填入编辑框而不自动发送。
document.querySelectorAll('.suggestions button').forEach(button => button.addEventListener('click', () => {
  // 把选择的示例问题放入输入框并获取焦点，用户可继续编辑。
  $('question').value = button.textContent; $('question').focus();
}));
// 页面载入时异步读取知识库就绪状态。
void refresh();

// 根据保存的会话 ID 读取后台落盘历史，重建最近对话。
async function restoreConversation() {
  // 没有保存过会话 ID 时无需发历史请求。
  if (!sessionId) return;
  const restoringId = sessionId;
  // 把可能失败的请求放入保护范围，对应 catch 负责展示可理解的提示。
  try {
    // 编码会话 ID 后调用只读恢复接口，避免 ID 中的特殊字符改变 URL。
    const history = await request('/api/sessions/' + encodeURIComponent(sessionId));
    if (sessionId !== restoringId || sending) return;
    // 逐条恢复用户/助手文本，缺失 turns 时按空历史处理。
    for (const turn of history.turns || []) addMessage(turn.role === 'assistant' ? 'assistant' : 'user', turn.text, turn.imageIds || [], restoringId);
    // 有早期摘要时提醒用户：页面显示近期轮次，助手仍掌握早期概要。
    if (history.summary) feedback('chat-feedback', '已恢复最近对话，助手保留早期对话摘要。');
  // 历史读取失败单独提示，不妨碍用户开始新的提问。
  } catch (error) { feedback('chat-feedback', '历史恢复失败：' + error.message, true); }
}
// 提交独立业务查询表单，支持筛选统计和可选 Excel 导出。
$('sql-form').addEventListener('submit', async event => {
  // 阻止默认表单跳页、禁用重复查询、显示等待状态并清理旧结果。
  event.preventDefault(); $('sql-submit').disabled = true; feedback('sql-feedback', '正在查询业务数据…'); $('sql-result').replaceChildren();
  // 把可能失败的请求放入保护范围，对应 catch 负责展示可理解的提示。
  try {
    // 提交自然语言业务问题，由后台生成并校验只读 SQL。
    const data = await request('/api/sql/query', {method:'POST',headers:{'Content-Type':'application/json'},
      // 问题去除首尾空白，export 从复选框读取是否导出 Excel。
      body:JSON.stringify({question:$('sql-question').value.trim(),export:$('sql-export').checked})});
    // 创建结果表格并设置紧凑字号，方便在侧栏查看。
    const table = document.createElement('table'); table.style.fontSize = '12px';
    // 准备列名行，列顺序与后台 JDBC 元数据保持一致。
    const heading = document.createElement('tr');
    // 按后台列名生成表头，以 textContent 避免字段名注入 HTML。
    for (const column of data.columns) {const th=document.createElement('th');th.textContent=column;heading.append(th);} table.append(heading);
    // 逐行创建单元格，null 显示为空字符串，其他值仍以纯文本写入。
    for (const row of data.rows) {const tr=document.createElement('tr');for(const value of row){const td=document.createElement('td');td.textContent=value??'';tr.append(td);}table.append(tr);}
    // 把完整结果表格加入业务查询区域。
    $('sql-result').append(table);
    // 仅接受本项目 UUID 下载路径，避免把任意外部 URL 当作导出链接。
    if(data.exportUrl && /^\/api\/sql\/exports\/[a-f0-9-]+$/.test(data.exportUrl)) {const a=document.createElement('a');a.href=data.exportUrl;a.textContent='下载 Excel';a.className='text-button';$('sql-result').append(a);}
    // 明确显示模拟数据、返回行数及是否达到后台 200 行截断限制。
    feedback('sql-feedback', `演示数据 · ${data.rows.length} 行${data.truncated?'（结果已截断至200行）':''}`);
  // 展示业务查询错误，并在 finally 恢复查询按钮。
  } catch(error) {feedback('sql-feedback',error.message,true);} finally {$('sql-submit').disabled=false;}
});
// 页面载入时恢复最近会话，不等待它完成才显示工作台。
void restoreConversation();
