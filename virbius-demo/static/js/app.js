// ============ 炫酷背景：矩阵雨 + 扫描线 ============
(function(){
  // 扫描线遮罩
  const scan = document.createElement('div');
  scan.className = 'scan';
  document.body.appendChild(scan);

  // 矩阵雨
  const cv = document.createElement('canvas');
  cv.id = 'matrix';
  document.body.appendChild(cv);
  const ctx = cv.getContext('2d');
  const chars = 'アカサタナハマヤラ0123456789ABCDEF<>{}[]#$%&LLMSEC';
  let cols, drops, fs = 14;
  function resize(){
    cv.width = innerWidth; cv.height = innerHeight;
    cols = Math.floor(cv.width / fs);
    drops = Array(cols).fill(0).map(()=>Math.random()*-100);
  }
  resize(); addEventListener('resize', resize);
  function draw(){
    ctx.fillStyle = 'rgba(5,7,13,0.10)';
    ctx.fillRect(0,0,cv.width,cv.height);
    ctx.font = fs + 'px monospace';
    for(let i=0;i<cols;i++){
      const c = chars[Math.floor(Math.random()*chars.length)];
      const x = i*fs, y = drops[i]*fs;
      ctx.fillStyle = Math.random()>0.97 ? '#22d3ee' : '#00ffae';
      ctx.fillText(c, x, y);
      if(y > cv.height && Math.random() > 0.975) drops[i] = 0;
      drops[i] += 0.6;
    }
    requestAnimationFrame(draw);
  }
  draw();
})();

// 右上角目标模型切换
document.addEventListener('DOMContentLoaded', ()=>{
  const sel = document.getElementById('model-select');
  if(sel){
    sel.addEventListener('change', async ()=>{
      const r = await postJSON('/api/set-model', {model: sel.value});
      const lbl = sel.closest('.target-sel');
      if(lbl){ lbl.classList.add('flash'); setTimeout(()=>lbl.classList.remove('flash'), 600); }
      if(!r.ok) sel.value = r.current;
    });
  }

  // 全局 VirbiusAgent 防护开关
  const prot = document.getElementById('prot-toggle');
  if(prot){
    prot.addEventListener('change', async ()=>{
      const r = await postJSON('/api/set-protection', {enabled: prot.checked});
      if(r.ok && r.enabled !== prot.checked) prot.checked = r.enabled;
      const lbl = prot.closest('.prot-sel');
      if(lbl){ lbl.classList.add('flash'); setTimeout(()=>lbl.classList.remove('flash'), 600); }
    });
  }
});

// 点击「完整渗透语句」直接填入输入框
document.addEventListener('click', e=>{
  const p = e.target.closest('.payload');
  if(!p) return;
  const box = document.getElementById('msg');
  if(box){ box.value = p.dataset.fill || p.textContent; box.focus();
    box.dispatchEvent(new Event('input')); window.scrollTo({top:box.getBoundingClientRect().top+scrollY-200,behavior:'smooth'}); }
});

// 对话检查器：渲染每轮完整提交 messages + 模型原始返回
const ROLE_CN = {system:'system', user:'user', assistant:'assistant', note:'note'};
function appendInspector(debug){
  const box = document.getElementById('inspector');
  if(!box || !debug) return;
  const ph = box.querySelector('.insp-ph'); if(ph) ph.remove();
  const n = box.querySelectorAll('.insp-round').length + 1;
  const wrap = document.createElement('div'); wrap.className = 'insp-round';
  wrap.appendChild(elInsp('div','insp-rh','▸ ROUND '+n+' · 提交 messages（'+debug.sent.length+' 条）'));
  debug.sent.forEach(m=>{
    const b = document.createElement('div'); b.className = 'insp-msg insp-'+(m.role||'user');
    const tag = document.createElement('span'); tag.className='insp-role'; tag.textContent='['+(ROLE_CN[m.role]||m.role)+']';
    const c = document.createElement('span'); c.className='insp-c'; c.textContent = m.content;
    b.appendChild(tag); b.appendChild(c); wrap.appendChild(b);
  });
  wrap.appendChild(elInsp('div','insp-rh ret','◂ 模型原始返回'));
  wrap.appendChild(elInsp('div','insp-raw', debug.raw));
  box.appendChild(wrap); box.scrollTop = box.scrollHeight;
}
function elInsp(t,c,txt){ const e=document.createElement(t); e.className=c; e.textContent=txt; return e; }
function clearInspector(){
  const box=document.getElementById('inspector');
  if(box) box.innerHTML='<p class="spin insp-ph">发送后，这里显示每轮发给模型的完整 prompt 与原始返回</p>';
}

// 通用工具
async function postJSON(url, body){
  const r = await fetch(url, {method:'POST', headers:{'Content-Type':'application/json'},
    body: JSON.stringify(body)});
  return r.json();
}
function el(tag, cls, text){
  const e = document.createElement(tag);
  if(cls) e.className = cls;
  if(text!=null) e.textContent = text;
  return e;
}
// 安全的轻量 Markdown：先转义 HTML 防 XSS，再渲染 **粗体** / `代码` / *斜体* / 换行
function mdSafe(s){
  const esc = String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  return esc
    .replace(/\*\*([^*]+)\*\*/g,'<strong>$1</strong>')
    .replace(/`([^`]+)`/g,'<code>$1</code>')
    .replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g,'$1<em>$2</em>')
    .replace(/\n/g,'<br>');
}
function addMsg(box, cls, text){
  const m = el('div', 'msg '+cls);
  // 仅对模型回答(bot)渲染 Markdown；用户/拦截/系统提示保持纯文本
  if(/\bbot\b/.test(cls)) m.innerHTML = mdSafe(text);
  else m.textContent = text;
  box.appendChild(m);
  box.scrollTop = box.scrollHeight;
  return m;
}

// ---------- 配置提示弹窗 + 模型就绪校验（发送前调用） ----------
function showConfigModal(reason, settingsUrl, provider){
  const mask = document.createElement('div'); mask.className='modal-mask';
  const modal = document.createElement('div'); modal.className='modal';
  modal.innerHTML =
    '<div class="m-ico">⚙️</div>'+
    '<h3>需要先完成模型配置</h3>'+
    '<p>'+(reason||'当前模型尚未配置完成。')+'</p>'+
    '<div class="mrow">'+
      '<button class="ghost modal-close">取消</button>'+
      '<button class="modal-go">去设置</button>'+
    '</div>';
  mask.appendChild(modal);
  document.body.appendChild(mask);
  const close = ()=>{ mask.remove(); };
  modal.querySelector('.modal-close').onclick = close;
  modal.querySelector('.modal-go').onclick = ()=>{
    close();
    if(settingsUrl){
      // 带 provider 跳转，设置页会自动选中当前模型对应的配置表单
      const sep = settingsUrl.includes('?') ? '&' : '?';
      location.href = settingsUrl + sep + 'provider=' + encodeURIComponent(provider||'');
    }
  };
  mask.addEventListener('click', e=>{ if(e.target===mask) close(); });
  return mask;
}

// 发送前校验当前模型是否可用（DeepSeek/OpenRouter 需 key，本地需 Ollama 可达且模型已装）。
// 返回 Promise<boolean>：true=可正常发送；false=已弹窗拦截，调用方应中止发送。
async function ensureModelReady(){
  try{
    const r = await fetch('/api/model-status').then(res=>res.json());
    if(r.ready) return true;
    showConfigModal(r.reason, r.settings_url, r.provider);
    return false;
  }catch(e){
    return true; // 状态接口异常不阻塞，交由发送链路报错
  }
}
