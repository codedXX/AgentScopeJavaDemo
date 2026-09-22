'use strict';
const $ = id => document.getElementById(id);
let sessionId = null;
let sending = false;
let indexing = false;
async function request(url, options = {}) {
  const response = await fetch(url, options);
  const data = await response.json().catch(() => null);
  if (!response.ok) throw new Error(data?.error || `请求失败（${response.status}），请检查服务后重试`);
  if (!data) throw new Error('服务未返回有效数据，请检查服务后重试');
  return data;
}
function feedback(id, text, error = false) {
  $(id).textContent = text;
  $(id).classList.toggle('error', error);
}
async function refresh() {
  try {
    const data = await request('/api/knowledge/status');
    $('status').textContent = data.ready ? '已就绪' : '未就绪';
    $('status').classList.toggle('error', !data.ready);
    $('chunk-count').textContent = data.chunkCount;
    $('status-detail').textContent = data.message || (data.ready ? '可以开始提问' : '请上传资料或重建知识库');
  } catch (error) {
    $('status').textContent = '连接失败';
    $('status').classList.add('error');
    $('chunk-count').textContent = '—';
    $('status-detail').textContent = error.message;
  }
}
function controls() {
  $('send').disabled = sending || indexing;
  $('new-chat').disabled = sending;
  $('upload').disabled = indexing || sending;
  $('rebuild').disabled = indexing || sending;
  $('file').disabled = indexing;
}
function validateFile(file) {
  if (!file) throw new Error('请先选择文件');
  if (!/\.(txt|md)$/i.test(file.name)) throw new Error('目前支持 TXT 和 Markdown 文件');
  if (file.size === 0 || file.size > 5 * 1024 * 1024) throw new Error('请选择非空且不超过 5 MB 的文件');
}
$('file').addEventListener('change', () => {
  const file = $('file').files[0];
  $('selected-file').textContent = file ? `${file.name} · ${(file.size / 1024).toFixed(1)} KB` : '尚未选择文件';
});
for (const event of ['dragenter', 'dragover']) $('drop-zone').addEventListener(event, e => {
  e.preventDefault(); if (!indexing) $('drop-zone').classList.add('dragging');
});
for (const event of ['dragleave', 'drop']) $('drop-zone').addEventListener(event, e => {
  e.preventDefault(); $('drop-zone').classList.remove('dragging');
});
$('drop-zone').addEventListener('drop', e => {
  if (indexing) return;
  try {
    if (e.dataTransfer.files.length !== 1) throw new Error('请一次上传一份资料');
    validateFile(e.dataTransfer.files[0]);
    $('file').files = e.dataTransfer.files;
    $('file').dispatchEvent(new Event('change'));
    feedback('upload-feedback', '文件已选择，点击“上传并入库”继续');
  } catch (error) { feedback('upload-feedback', error.message, true); }
});
async function indexKnowledge(file) {
  if (indexing || sending) return;
  indexing = true; controls();
  feedback('upload-feedback', '正在处理资料并写入知识库，请保持页面打开…');
  $('status').textContent = '处理中';
  try {
    let options = { method: 'POST' };
    if (file) { const body = new FormData(); body.append('file', file); options.body = body; }
    const data = await request(file ? '/api/knowledge/upload' : '/api/knowledge/rebuild', options);
    feedback('upload-feedback', `入库完成，知识库现有 ${data.chunkCount} 个片段。可以开始提问。`);
    if (file) { $('upload-form').reset(); $('selected-file').textContent = '尚未选择文件'; }
  } catch (error) {
    feedback('upload-feedback', `${error.message}。若资料已保存但索引失败，请修复服务连接后点击“重建知识库 / 失败后重试”，避免重复上传。`, true);
  } finally { indexing = false; controls(); await refresh(); }
}
$('upload-form').addEventListener('submit', e => {
  e.preventDefault();
  try { const file = $('file').files[0]; validateFile(file); void indexKnowledge(file); }
  catch (error) { feedback('upload-feedback', error.message, true); }
});
$('rebuild').addEventListener('click', () => indexKnowledge());
$('refresh').addEventListener('click', refresh);
function addMessage(role, text) {
  $('welcome').hidden = true;
  const article = document.createElement('article'); article.className = `message ${role}`;
  const label = document.createElement('div'); label.className = 'message-label'; label.textContent = role === 'user' ? '你' : '知答 · 助手';
  const body = document.createElement('div'); body.className = 'message-body'; body.textContent = text;
  article.append(label, body); $('messages').append(article); scrollMessages(); return article;
}
function scrollMessages() { $('messages').scrollTop = $('messages').scrollHeight; }
function addDetails(article, title, items) {
  if (!items?.length) return;
  const details = document.createElement('details');
  const summary = document.createElement('summary'); summary.textContent = `${title}（${items.length}）`;
  const list = document.createElement('ul');
  for (const item of items) { const li = document.createElement('li'); li.textContent = item; list.append(li); }
  details.append(summary, list); article.append(details);
}
$('chat-form').addEventListener('submit', async e => {
  e.preventDefault();
  const message = $('question').value.trim();
  if (!message || sending || indexing) return;
  // 客户端提前生成 ID，使网络失败后重试仍然沿用同一会话。
  sessionId ||= crypto.randomUUID();
  sending = true; controls(); feedback('chat-feedback', '');
  addMessage('user', message); $('question').value = '';
  const answer = addMessage('assistant', '正在查找资料并整理回答…');
  try {
    const data = await request('/api/chat', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ sessionId, message }) });
    sessionId = data.sessionId;
    answer.querySelector('.message-body').textContent = data.answer;
    addDetails(answer, '引用来源', data.sources); addDetails(answer, '处理步骤', data.steps);
    const timing = document.createElement('div'); timing.className = 'timing';
    timing.textContent = `检索 ${data.retrievalMs} ms · 总耗时 ${(data.totalMs / 1000).toFixed(1)} s`; answer.append(timing);
  } catch (error) {
    answer.querySelector('.message-body').textContent = `未能取得回答：${error.message}`;
    if (!$('question').value.trim()) $('question').value = message;
    feedback('chat-feedback', '问题仍可编辑；检查服务后重新发送。', true);
  } finally { sending = false; controls(); scrollMessages(); $('question').focus(); }
});
$('question').addEventListener('keydown', e => {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); $('chat-form').requestSubmit(); }
});
$('new-chat').addEventListener('click', () => {
  sessionId = null; document.querySelectorAll('.message').forEach(node => node.remove());
  $('welcome').hidden = false; $('question').value = ''; feedback('chat-feedback', ''); $('question').focus();
});
document.querySelectorAll('.suggestions button').forEach(button => button.addEventListener('click', () => {
  $('question').value = button.textContent; $('question').focus();
}));
void refresh();
