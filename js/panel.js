(function(){
  function css(){
    if(document.getElementById('dshb-ep-css'))return;
    var st=document.createElement('style');
    st.id='dshb-ep-css';
    st.textContent=
      '#dshb-ep{position:fixed;inset:0;display:none;z-index:2147483000;align-items:center;justify-content:center;'+
      'background:rgba(15,23,42,.42);backdrop-filter:blur(3px);-webkit-backdrop-filter:blur(3px);'+
      'font:13px/1.5 -apple-system,system-ui,"Segoe UI",Roboto,sans-serif;padding:16px;box-sizing:border-box}'+
      '#dshb-ep[data-open=1]{display:flex;animation:dshbEpFade .18s ease-out}'+
      '@keyframes dshbEpFade{from{opacity:0}to{opacity:1}}'+
      '#dshb-ep-card{width:min(400px,94vw);max-height:88vh;overflow:auto;box-sizing:border-box;'+
      'background:var(--dsw-alias-bg-layer-1,#fff);color:var(--dsw-alias-label-primary,#0f172a);'+
      'border:1px solid var(--dsw-alias-border-1,rgba(15,23,42,.08));border-radius:20px;'+
      'box-shadow:0 28px 70px -18px rgba(15,23,42,.45),0 2px 10px rgba(15,23,42,.10);'+
      'padding:18px 18px 16px;animation:dshbEpRise .22s cubic-bezier(.2,.9,.3,1)}'+
      '@keyframes dshbEpRise{from{opacity:0;transform:translateY(10px) scale(.985)}to{opacity:1;transform:none}}'+
      '#dshb-ep-card h2{margin:0;font-size:15.5px;font-weight:650;letter-spacing:.01em;display:flex;align-items:center;gap:8px}'+
      '#dshb-ep-card h2 .ico{width:22px;height:22px;border-radius:7px;display:inline-flex;align-items:center;'+
      'justify-content:center;background:linear-gradient(135deg,#3b82f6,#6366f1);color:#fff;font-size:12px;flex:none}'+
      '#dshb-ep-x{width:26px;height:26px;border-radius:8px;display:inline-flex;align-items:center;justify-content:center;'+
      'cursor:pointer;color:var(--dsw-alias-label-secondary,#64748b);font-size:16px;line-height:1;'+
      'transition:background .15s ease,color .15s ease}'+
      '#dshb-ep-x:hover{background:rgba(15,23,42,.06);color:var(--dsw-alias-label-primary,#0f172a)}'+
      '#dshb-ep-tabs{display:flex;gap:4px;margin:12px 0 0;padding:3px;border-radius:10px;background:rgba(15,23,42,.05)}'+
      '#dshb-ep-tabs button{position:relative;flex:1;min-height:30px;border:0;border-radius:8px;background:transparent;'+
      'font:600 12px/1 system-ui;color:var(--dsw-alias-label-secondary,#64748b);cursor:pointer}'+
      '#dshb-ep-tabs button.on{background:var(--dsw-alias-bg-layer-1,#fff);color:var(--dsw-alias-label-primary,#0f172a);'+
      'box-shadow:0 1px 2px rgba(15,23,42,.10)}'+
      '#dshb-ep-tabs button .badge{position:absolute;top:5px;right:8px;width:5px;height:5px;border-radius:50%;'+
      'background:#f59e0b;display:none}'+
      '#dshb-ep-tabs button.has .badge{display:block}'+
      '#dshb-ep-shellsub{margin:12px 0 0;font-size:11.5px;color:var(--dsw-alias-label-secondary,#64748b)}'+
      '#dshb-ep-shellacts{margin-top:16px;display:flex;flex-direction:column;gap:2px}'+
      '#dshb-ep-shellacts .primary{width:100%;min-height:44px;border:0;border-radius:13px;font:650 13.5px/1 system-ui;color:#fff;cursor:pointer;background:linear-gradient(180deg,#3b82f6,#2563eb);display:flex;align-items:center;justify-content:center;gap:8px;box-shadow:0 8px 20px -10px rgba(37,99,235,.7),inset 0 1px 0 rgba(255,255,255,.22);transition:filter .15s ease,transform .08s ease}'+
      '#dshb-ep-shellacts .primary:hover{filter:brightness(1.05)}'+
      '#dshb-ep-shellacts .primary:active{transform:scale(.985)}'+
      '#dshb-ep-shellacts .primary:disabled{opacity:.5;cursor:default}'+
      '#dshb-ep-shellacts .primary .ar{font-size:15px;line-height:1;opacity:.9}'+
      '#dshb-ep-shellacts .link{border:0;background:transparent;min-height:34px;cursor:pointer;font:500 12px/1 system-ui;color:var(--dsw-alias-label-secondary,#64748b);transition:color .15s ease}'+
      '#dshb-ep-shellacts .link:hover{color:var(--dsw-alias-label-primary,#0f172a)}'+
      '#dshb-ep-shellacts .link:disabled{opacity:.45;cursor:default}'+
      '#dshb-shellprog{display:none;margin-top:16px}'+
      '#dshb-shellprog .t{display:flex;justify-content:space-between;align-items:baseline;margin-bottom:7px}'+
      '#dshb-shellprog .lbl{font:600 11.5px/1 system-ui;letter-spacing:.01em;color:var(--dsw-alias-label-primary,#0f172a)}'+
      '#dshb-shellprog .pct{font:600 11.5px/1 ui-monospace,SFMono-Regular,Menlo,monospace;color:#3b82f6}'+
      '#dshb-shellprog .tr{position:relative;height:6px;border-radius:999px;overflow:hidden;background:rgba(148,163,184,.22)}'+
      '#dshb-shellprog .fi{height:100%;width:0;border-radius:999px;background:linear-gradient(90deg,#60a5fa,#2563eb);transition:width .18s linear}'+
      '#dshb-shellprog .tr:after{content:\"\";position:absolute;inset:0;background:linear-gradient(100deg,transparent 30%,rgba(255,255,255,.42) 50%,transparent 70%);transform:translateX(-100%);animation:dshb-shimmer 1.5s ease-in-out infinite}'+
      '#dshb-shellprog.done .tr:after,#dshb-shellprog.err .tr:after{display:none}'+
      '#dshb-shellprog .mt{margin-top:6px;font:400 10.5px/1.4 ui-monospace,SFMono-Regular,Menlo,monospace;color:var(--dsw-alias-label-secondary,#64748b)}'+
      '#dshb-shellprog.done .fi{background:linear-gradient(90deg,#34d399,#10b981)}'+
      '#dshb-shellprog.done .pct{color:#10b981}'+
      '#dshb-shellprog.err .fi{background:linear-gradient(90deg,#fb7185,#e11d48)}'+
      '#dshb-shellprog.err .pct{color:#e11d48}'+
      '@keyframes dshb-shimmer{0%{transform:translateX(-100%)}60%,100%{transform:translateX(100%)}}'+
      '#dshb-ep-sub{margin:6px 0 0 30px;font-size:11.5px;color:var(--dsw-alias-label-secondary,#64748b);'+
      'display:flex;align-items:center;gap:6px}'+
      '#dshb-ep-sub .pill{font-size:10.5px;font-weight:600;padding:2px 7px;border-radius:99px;'+
      'background:rgba(34,197,94,.12);color:#15803d}'+
      '#dshb-ep-sub .pill.warn{background:rgba(245,158,11,.14);color:#b45309}'+
      '#dshb-ep-body,#dshb-ep-shellbody{margin-top:14px;border:1px solid var(--dsw-alias-border-1,rgba(15,23,42,.07));'+
      'border-radius:12px;overflow:hidden;background:rgba(15,23,42,.015)}'+
      '#dshb-ep-body .row,#dshb-ep-shellbody .row{display:flex;justify-content:space-between;align-items:center;gap:12px;'+
      'padding:9px 12px;font-size:12.5px}'+
      '#dshb-ep-body .row+.row,#dshb-ep-shellbody .row+.row{border-top:1px solid var(--dsw-alias-border-1,rgba(15,23,42,.06))}'+
      '#dshb-ep-body .k,#dshb-ep-shellbody .k{color:var(--dsw-alias-label-secondary,#64748b);flex:none}'+
      '#dshb-ep-body .v,#dshb-ep-shellbody .v{font:500 12px/1.4 ui-monospace,SFMono-Regular,Menlo,monospace;'+
      'color:var(--dsw-alias-label-primary,#0f172a);text-align:right;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}'+
      '#dshb-ep-btns{display:flex;gap:8px;margin-top:16px}'+
      '#dshb-ep-btns button{flex:1;min-height:38px;border:0;border-radius:11px;font:600 13px/1 system-ui;'+
      'cursor:pointer;transition:transform .08s ease,filter .15s ease,background .15s ease}'+
      '#dshb-ep-btns button:active{transform:scale(.98)}'+
      '#dshb-ep-btns button:disabled{opacity:.45;cursor:default}'+
      '#dshb-ep-btns .primary{background:linear-gradient(180deg,#3b82f6,#2563eb);color:#fff;flex:1.6;'+
      'box-shadow:0 1px 2px rgba(37,99,235,.35),inset 0 1px 0 rgba(255,255,255,.22)}'+
      '#dshb-ep-btns .primary:hover{filter:brightness(1.04)}'+
      '#dshb-ep-btns .ghost{background:rgba(15,23,42,.055);color:var(--dsw-alias-label-primary,#0f172a)}'+
      '#dshb-ep-btns .ghost:hover{background:rgba(15,23,42,.09)}'+
      '#dshb-ep-msg{margin-top:11px;font-size:11.5px;min-height:15px;color:var(--dsw-alias-label-secondary,#64748b)}'+
      '#dshb-ep-bar{display:none;height:6px;border-radius:99px;background:rgba(15,23,42,.08);margin-top:9px;overflow:hidden}'+
      '#dshb-ep-fill{height:100%;width:0%;border-radius:99px;background:linear-gradient(90deg,#60a5fa,#3b82f6);'+
      'transition:width .3s ease;box-shadow:0 0 8px rgba(59,130,246,.45)}'+
      '#dshb-ep-stage{display:none;font-size:11px;color:var(--dsw-alias-label-secondary,#64748b);margin-top:6px}'+
      '#dshb-ep-logwrap{margin-top:12px}'+
      '#dshb-ep-logwrap summary{cursor:pointer;font-size:11.5px;color:var(--dsw-alias-label-secondary,#64748b);'+
      'list-style:none;user-select:none}'+
      '#dshb-ep-logwrap summary::-webkit-details-marker{display:none}'+
      '#dshb-ep-logwrap summary:before{content:"›";display:inline-block;margin-right:5px;transition:transform .15s ease}'+
      '#dshb-ep-logwrap[open] summary:before{transform:rotate(90deg)}'+
      '#dshb-ep-log{display:block;margin:7px 0 0;max-height:150px;overflow:auto;font:11px/1.5 ui-monospace,monospace;'+
      'background:rgba(15,23,42,.05);border-radius:9px;padding:8px 10px;white-space:pre-wrap;word-break:break-all}'+
      '@media (prefers-color-scheme:dark){'+
      '#dshb-ep-body,#dshb-ep-shellbody{background:rgba(255,255,255,.03)}'+
      '#dshb-ep-btns .ghost{background:rgba(255,255,255,.08)}'+
      '#dshb-ep-log{background:rgba(255,255,255,.06)}}';
    document.head.appendChild(st);
  }
  css();
  var box=document.getElementById('dshb-ep');
  if(!box){
    box=document.createElement('div');
    box.id='dshb-ep';
    var card=document.createElement('div');
    card.id='dshb-ep-card';
    card.innerHTML=
      '<div style="display:flex;align-items:center;justify-content:space-between;gap:8px">'+
        '<h2><span class="ico">◈</span>引擎 / 外壳</h2>'+
        '<span id="dshb-ep-x" role="button" aria-label="关闭">✕</span>'+
      '</div>'+
      '<div id="dshb-ep-tabs">'+
        '<button data-tab="engine" class="on">引擎<span class="badge"></span></button>'+
        '<button data-tab="shell">外壳<span class="badge"></span></button>'+
      '</div>'+
      '<div id="dshb-ep-panel-engine">'+
      '<div id="dshb-ep-sub"><span id="dshb-ep-subtext">正在读取版本…</span><span id="dshb-ep-pill" class="pill" style="display:none"></span></div>'+
      '<div id="dshb-ep-body"></div>'+
      '<div id="dshb-ep-btns"></div>'+
      '<div id="dshb-ep-msg"></div>'+
      '<div id="dshb-ep-bar"><div id="dshb-ep-fill"></div></div>'+
      '<div id="dshb-ep-stage"></div>'+
      '<details id="dshb-ep-logwrap"><summary>最近事件</summary><pre id="dshb-ep-log"></pre></details>'+
      '<div id="dshb-ep-more"></div>'+
      '</div>'+
      '<div id="dshb-ep-panel-shell" style="display:none">'+
        '<div id="dshb-ep-shellsub">读取中…</div>'+
        '<div id="dshb-ep-shellbody"></div>'+
        '<div id="dshb-shellprog"><div class="t"><span class="lbl">准备下载</span><span class="pct">0%</span></div>'+
          '<div class="tr"><div class="fi"></div></div><div class="mt"></div></div>'+
        '<div id="dshb-ep-shellacts"></div>'+
      '</div>';
    box.appendChild(card);
    document.body.appendChild(box);
    function close(){box.setAttribute('data-open','0');box.style.display='none';}
    function tab(name){
      box.setAttribute('data-tab',name);
      var bs=document.querySelectorAll('#dshb-ep-tabs button');
      for(var i=0;i<bs.length;i++){bs[i].classList.toggle('on',bs[i].getAttribute('data-tab')===name);}
      document.getElementById('dshb-ep-panel-engine').style.display=name==='engine'?'block':'none';
      document.getElementById('dshb-ep-panel-shell').style.display=name==='shell'?'block':'none';
      if(name==='shell'){try{window.DSHBridge.shellCheck();}catch(e){}}
    }
    window.__dshbEPClose=close;
    document.getElementById('dshb-ep-x').onclick=function(){close();};
    box.addEventListener('click',function(e){if(e.target===box)close();});
    document.addEventListener('keydown',function(e){if(e.key==='Escape')close();});
    var tbs=document.querySelectorAll('#dshb-ep-tabs button');
    for(var ti=0;ti<tbs.length;ti++){(function(b){b.onclick=function(){tab(b.getAttribute('data-tab'));};})(tbs[ti]);}
    tab('engine');
    var more=document.getElementById('dshb-ep-more');
    if(more){
      more.innerHTML='';
      var hint=document.createElement('span');
      hint.id='dshb-ep-morehint';
      hint.style.cssText='color:#94a3b8;font-size:11px;margin-left:8px';
      more.appendChild(hint);
    }
  }
  function norm(v){return String(v==null?'':v).trim().replace(/^v/,'');}
  function cmp(a,b){
    var pa=norm(a).split(/[.+-]/),pb=norm(b).split(/[.+-]/);
    var n=Math.max(pa.length,pb.length);
    for(var i=0;i<n;i++){
      var x=pa[i]||'',y=pb[i]||'';
      var nx=parseInt(x,10),ny=parseInt(y,10);
      var xn=!isNaN(nx),yn=!isNaN(ny);
      if(xn&&yn){if(nx!==ny)return nx<ny?-1:1;continue;}
      if(xn!==yn)return xn?-1:1;
      if(x!==y)return x<y?-1:1;
    }
    return 0;
  }
  function row(k,v){
    var d=document.createElement('div');d.className='row';
    var a=document.createElement('span');a.className='k';a.textContent=k;
    var b=document.createElement('span');b.className='v';b.textContent=v;
    d.appendChild(a);d.appendChild(b);return d;
  }
  function btn(text,cls,fn,busy){
    var e=document.createElement('button');
    e.className=cls;e.textContent=text;e.onclick=fn;
    if(busy){e.disabled=true;}
    return e;
  }
  window.__dshbEP=function(s){
    s=s||{};
    var cur=norm(s.cur);
    var bnd=norm(s.bundle);
    var bundleNewer=!!(bnd&&bnd!=='无'&&cur&&cur!=='未知'&&cmp(bnd,cur)>0);
    var latest=bundleNewer?bnd:norm(s.npm);
    var has=bundleNewer;
    engineBusy=!!s.busy;engineHasUpdate=has;
    engineState=(cur&&cur!=='未知')?'ok':'unknown';
    syncChip();
    var sub=document.getElementById('dshb-ep-subtext');
    var pill=document.getElementById('dshb-ep-pill');
    if(sub){
      if(s.busy){sub.textContent='正在处理，请稍候…';}
      else if(cur==='未知'||!cur){sub.textContent='引擎版本未知';}
      else if(has){sub.textContent='发现新版本 '+latest;}
      else{sub.textContent='已是最新';}
    }
    if(pill){
      if(cur&&cur!=='未知'&&latest&&!s.busy){
        pill.style.display='inline-block';
        pill.textContent=has?'可更新':'已最新';
        pill.className='pill'+(has?' warn':'');
      }else{pill.style.display='none';}
    }
    var body=document.getElementById('dshb-ep-body');
    body.innerHTML='';
    body.appendChild(row('当前引擎',s.cur||'未知'));
    body.appendChild(row('npm 最新',s.npm||'—'));
    // 本地预构建包只在它比当前引擎新时才值得显示；否则就是一枚过期的回退包
    if(bundleNewer)body.appendChild(row('本地预构建包',s.bundle));
    var btns=document.getElementById('dshb-ep-btns');
    btns.innerHTML='';
    var npmN=norm(s.npm),npmNewer=!!(cur&&cur!=='未知'&&npmN&&cmp(npmN,cur)>0);
    var canBuild=!!s.canBuild&&npmNewer;
    var portNeeded=!!s.portNeeded&&canBuild;
    var mh=document.getElementById('dshb-ep-morehint');
    if(mh){
      mh.textContent=canBuild?('· '+(portNeeded?'将自动迁移补丁集后构建':'可原地从 npm 构建')+' '+npmN):(npmNewer?'· npm 有新版但暂无补丁集':'');
    }
    if(has&&s.url){
      btns.appendChild(btn(s.busy?'处理中…':'下载并更新到 '+latest,'primary',function(){
        if(!confirm('下载 '+(s.bundle||'')+' 并替换当前引擎？更新期间界面会短暂重启。'))return;
        window.DSHBridge.epUpdate();
      },s.busy));
      if(canBuild)btns.appendChild(btn('从 npm 构建 '+npmN,'ghost',function(){
        if(!confirm('在手机里 npm 安装依赖 + 覆盖补丁 + 冒烟，约几分钟，期间别退出 App。继续吗？'))return;
        window.DSHBridge.epBuild();
      },s.busy));
      btns.appendChild(btn('重新检查','ghost',function(){window.DSHBridge.epCheck();},s.busy));
    }else if(canBuild){
      btns.appendChild(btn(s.busy?'处理中…':'从 npm 构建并更新到 '+npmN,'primary',function(){
        if(!confirm('在手机里 npm 安装依赖 + 覆盖补丁 + 冒烟，约几分钟，期间别退出 App。继续吗？'))return;
        window.DSHBridge.epBuild();
      },s.busy));
      btns.appendChild(btn('重新检查','ghost',function(){window.DSHBridge.epCheck();},s.busy));
    }else{
      btns.appendChild(btn(s.busy?'处理中…':'检查更新','primary',function(){window.DSHBridge.epCheck();},s.busy));
    }
    btns.appendChild(btn('重启引擎','ghost',function(){
      if(!confirm('重启引擎会中断当前对话几秒，继续吗？'))return;
      window.DSHBridge.restartEngine();
    },s.busy));
    var msg=document.getElementById('dshb-ep-msg');
    msg.textContent=s.msg||'';
    var bar=document.getElementById('dshb-ep-bar'),fill=document.getElementById('dshb-ep-fill'),stg=document.getElementById('dshb-ep-stage');
    if(s.pct!==null&&s.pct!==undefined&&s.pct>=0){
      bar.style.display='block';
      fill.style.width=Math.max(0,Math.min(100,s.pct))+'%';
      stg.style.display='block';
      stg.textContent=(s.stage||'')+(s.pct>0?' · '+Math.round(s.pct)+'%':'');
    }else{
      bar.style.display='none';stg.style.display='none';
    }
    var lg=document.getElementById('dshb-ep-log'),wrap=document.getElementById('dshb-ep-logwrap');
    wrap.style.display='block';
    lg.textContent=(s.log&&s.log.length)?s.log.join('\n'):'暂无事件记录';
    lg.scrollTop=lg.scrollHeight;
  };
  var engineBusy=false,engineHasUpdate=false,engineState='unknown',shellNewer=false;
  var dl={name:'',done:0,total:0,state:''};
  function shellBusy(){return dl.state==='start'||dl.state==='downloading'||dl.state==='ready'||dl.state==='done';}
  function fmtB(n){n=Number(n)||0;if(n<1024)return n+' B';if(n<1048576)return (n/1024).toFixed(1)+' KB';return (n/1048576).toFixed(1)+' MB';}
  function renderProg(){
    var p=document.getElementById('dshb-shellprog');if(!p)return;
    if(!dl.state){p.style.display='none';return;}
    p.style.display='block';
    var pct=dl.total>0?Math.max(0,Math.min(100,Math.round(dl.done*100/dl.total))):((dl.state==='done'||dl.state==='ready')?100:0);
    p.className=dl.state==='error'?'err':((dl.state==='done'||dl.state==='ready')?'done':'run');
    var f=p.querySelector('.fi'),pc=p.querySelector('.pct'),lbl=p.querySelector('.lbl'),mt=p.querySelector('.mt');
    if(f)f.style.width=pct+'%';
    if(pc)pc.textContent=pct+'%';
    var L={start:'连接下载源…',downloading:'正在下载外壳包',done:'资源已下载完成',ready:'下载完成 · 等待你确认',error:'下载失败'};
    if(lbl)lbl.textContent=L[dl.state]||'';
    if(mt)mt.textContent=dl.total>0?(fmtB(dl.done)+' / '+fmtB(dl.total)):(dl.state==='error'?'请检查网络后重试':'');
  }
  function syncChip(){
    var st=(engineBusy||shellBusy())?'busy':((engineHasUpdate||shellNewer)?'update':engineState);
    try{if(window.__dshbChipSet)window.__dshbChipSet(st);}catch(e){}
  }
  var lastShell=null, hasPending=false;
  function renderShellActs(){
    var acts=document.getElementById('dshb-ep-shellacts');
    if(!acts)return;
    acts.innerHTML='';
    // 下载完成、等用户确认：是 / 稍后
    if(dl.state==='ready'){
      var go=document.createElement('button');
      go.className='primary';
      go.innerHTML='<span class="ar">⇧</span><span class="tx">现在更新</span>';
      go.onclick=function(){
        go.disabled=true;go.style.opacity='.85';
        go.querySelector('.tx').textContent='正在调起安装器…';
        try{window.DSHBridge.installPending();}catch(e){go.disabled=false;go.querySelector('.tx').textContent='现在更新';}
      };
      acts.appendChild(go);
      var later=document.createElement('button');
      later.className='link';later.textContent='稍后再说，先不打断';
      later.onclick=function(){dl.state='';renderProg();renderShellActs();syncChip();};
      acts.appendChild(later);
      return;
    }
    if(shellNewer&&lastShell&&lastShell.url){
      var b1=document.createElement('button');
      b1.className='primary';b1.id='dshb-shell-go';
      b1.innerHTML='<span class="ar">↓</span><span class="tx">下载并安装 '+(lastShell.latest||'')+'</span>';
      var tx=b1.querySelector('.tx');
      function idle(){b1.disabled=false;b1.style.opacity='';tx.textContent='下载并安装 '+(lastShell.latest||'');}
      function applyState(){
        if(dl.state==='start'||dl.state==='downloading'){
          b1.disabled=true;b1.style.opacity='.85';
          tx.textContent=dl.total>0?('正在下载 '+Math.round(dl.done*100/dl.total)+'%'):'正在下载…';
        }else if(dl.state==='error'){idle();tx.textContent='重试下载并安装 '+(lastShell.latest||'');}
        else idle();
      }
      b1.onclick=function(){
        dl={name:(lastShell.latest||''),done:0,total:0,state:'start'};
        renderProg();applyState();syncChip();
        try{window.DSHBridge.download(lastShell.url);}catch(e){dl={name:'',done:0,total:0,state:''};renderProg();idle();}
      };
      b1.__apply=applyState;
      acts.appendChild(b1);
      setTimeout(applyState,0);
    }
    if(hasPending){
      var ins=document.createElement('button');
      ins.className='link';ins.id='dshb-shell-install';
      ins.textContent='立即安装已下载的更新';
      ins.onclick=function(){try{window.DSHBridge.installPending();}catch(e){}};
      acts.appendChild(ins);
    }
    var re=document.createElement('button');
    re.className='link';re.textContent='重新检查';
    re.onclick=function(){
      re.disabled=true;re.textContent='检查中…';
      try{window.DSHBridge.shellCheck();}catch(e){}
      setTimeout(function(){re.disabled=false;re.textContent='重新检查';},2500);
    };
    acts.appendChild(re);
  }
  window.__dshbShellSet=function(x){
    if(!x)return;
    lastShell=x;
    shellNewer=!!x.newer;
    hasPending=!!x.pending;
    if(hasPending&&dl.state!=='ready'){
      dl={name:dl.name||'',done:dl.total||0,total:dl.total||0,state:'ready'};
      renderProg();
    }
    var tb=document.querySelector('#dshb-ep-tabs button[data-tab=shell]');
    if(tb)tb.classList.toggle('has',shellNewer||hasPending);
    var sub=document.getElementById('dshb-ep-shellsub');
    if(sub)sub.textContent=hasPending?'资源已下载完成 · 待确认':
      (!x.latest?('暂时取不到最新版本 · 当前 '+(x.cur||'')):
      (shellNewer?('发现新版本 '+(x.latest||'')):('已是最新 · 当前 '+(x.cur||''))));
    var body=document.getElementById('dshb-ep-shellbody');
    if(body){
      body.innerHTML='';
      body.appendChild(row('当前外壳',x.cur||'未知'));
      body.appendChild(row('最新版本',x.latest||'—'));
      if(hasPending)body.appendChild(row('更新包','已下载，可直接安装'));
      if(x.note)body.appendChild(row('说明',x.note));
    }
    renderShellActs();
    syncChip();
  };

  window.__dshbDl=function(name,done,total,state){
    name=name||dl.name;done=Number(done)||0;total=Number(total)||0;
    if(state==='ready'||state==='done'){
      // 终态：下载完成，等确认。total 未知时用已下载量兜底
      dl={name:name,done:total||done,total:total||done,state:'ready'};
      hasPending=true;
    }else if(state==='error'){
      if(dl.state==='ready')return;          // 已经下好了，迟到的错误不覆盖
      dl={name:name,done:0,total:0,state:'error'};
    }else if(state==='start'){
      if(dl.state==='ready'||dl.state==='start'||dl.state==='downloading')return;
      dl={name:name,done:0,total:total,state:'start'};
    }else if(state==='downloading'){
      if(dl.state!=='start'&&dl.state!=='downloading')return;
      dl={name:name,done:done,total:total||dl.total,state:'downloading'};
    }else{
      dl={name:'',done:0,total:0,state:''};
    }
    renderProg();
    renderShellActs();
    syncChip();
  };
  window.__dshbEPOpen=function(){
    box.setAttribute('data-open','1');
    try{
      box.style.position='fixed';box.style.left='0';box.style.top='0';box.style.right='0';box.style.bottom='0';
      box.style.zIndex='2147483000';box.style.display='flex';
      box.style.alignItems='center';box.style.justifyContent='center';
      box.style.background='rgba(15,23,42,.42)';
    }catch(e){}
    try{document.querySelector('#dshb-ep-tabs button[data-tab=engine]').click();}catch(e){}
    window.DSHBridge.epOpen();
  };
})()
