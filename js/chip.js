(function(V){
  function railW(){
    try{
      var els=document.querySelectorAll('[class*=sidebar]');
      for(var i=0;i<els.length;i++){
        var w=els[i].getBoundingClientRect().width;
        if(w>30&&w<140)return Math.round(w);
      }
    }catch(e){}
    return 56;
  }
  function place(el){
    try{
      var w=railW();
      el.style.left=(w<=80?8:w+10)+'px';
      var fa=document.querySelector('[class*=footArea]');
      var b=10;
      if(fa&&fa.getBoundingClientRect){
        var r=fa.getBoundingClientRect();
        if(r.height>0)b=Math.max(10,Math.round(window.innerHeight-r.top)+6);
      }
      el.style.bottom=b+'px';
    }catch(e){}
  }
  function injectCss(){
    if(document.getElementById('dshb-chip-css'))return;
    var st=document.createElement('style');
    st.id='dshb-chip-css';
    st.textContent=
      '#dshb-engine{position:fixed;z-index:2147482500;display:inline-flex;align-items:center;gap:7px;'+
      'padding:4px 10px 4px 8px;border-radius:999px;box-sizing:border-box;'+
      'font:500 11px/14px -apple-system,system-ui,"Segoe UI",Roboto,sans-serif;letter-spacing:.01em;'+
      'color:var(--dsw-alias-label-secondary,#5f6b7a);'+
      'background:rgba(255,255,255,.78);'+
      'background:color-mix(in srgb,var(--dsw-alias-bg-layer-1,#fff) 80%,transparent);'+
      'border:1px solid var(--dsw-alias-border-1,rgba(15,23,42,.10));'+
      'box-shadow:0 1px 3px rgba(15,23,42,.07),inset 0 1px 0 rgba(255,255,255,.55);'+
      'backdrop-filter:blur(10px) saturate(1.25);-webkit-backdrop-filter:blur(10px) saturate(1.25);'+
      'cursor:pointer;user-select:none;-webkit-user-select:none;white-space:nowrap;opacity:.92;'+
      'transition:opacity .15s ease,box-shadow .15s ease,transform .08s ease}'+
      '#dshb-engine:hover{opacity:1;box-shadow:0 2px 9px rgba(15,23,42,.11),inset 0 1px 0 rgba(255,255,255,.6)}'+
      '#dshb-engine:active{transform:scale(.975)}'+
      '#dshb-engine .d{width:6px;height:6px;border-radius:50%;flex:none;background:#94a3b8;'+
      'box-shadow:0 0 0 3px rgba(148,163,184,.14);transition:background .2s ease,box-shadow .2s ease}'+
      '#dshb-engine[data-s=ok] .d{background:#22c55e;box-shadow:0 0 0 3px rgba(34,197,94,.16)}'+
      '#dshb-engine[data-s=update] .d{background:#f59e0b;box-shadow:0 0 0 3px rgba(245,158,11,.18);'+
      'animation:dshbChipPulse 1.8s ease-in-out infinite}'+
      '#dshb-engine[data-s=busy] .d{background:#3b82f6;box-shadow:0 0 0 3px rgba(59,130,246,.18);'+
      'animation:dshbChipPulse 1.2s ease-in-out infinite}'+
      '#dshb-engine .s{font-weight:600;color:#b45309;font-size:10px;'+
      'padding:0 5px;border-radius:99px;background:rgba(245,158,11,.13);display:none}'+
      '#dshb-engine[data-s=update] .s{display:inline-block}'+
      '@keyframes dshbChipPulse{0%,100%{opacity:1}50%{opacity:.45}}';
    document.head.appendChild(st);
  }
  injectCss();
  var el=document.getElementById('dshb-engine');
  if(!el){
    el=document.createElement('div');
    el.id='dshb-engine';
    el.setAttribute('data-s','unknown');
    var d=document.createElement('span');d.className='d';
    var t=document.createElement('span');t.className='t';
    var s=document.createElement('span');s.className='s';s.textContent='可更新';
    el.appendChild(d);el.appendChild(t);el.appendChild(s);
    document.body.appendChild(el);
    window.addEventListener('resize',function(){place(el);});
    try{
      new MutationObserver(function(){place(el);}).observe(document.body,{childList:true,subtree:true});
    }catch(e){}
    el.addEventListener('click',function(e){try{e.preventDefault();e.stopPropagation();}catch(_){}
      try{window.__dshbEPOpen&&window.__dshbEPOpen();}catch(_){}
    },true);
  }
  var tv=el.querySelector('.t');
  if(tv)tv.textContent=V?('引擎 '+V):'引擎';
  el.setAttribute('title',V?('引擎 '+V+' · 点击查看引擎更新'):'引擎未运行 · 点击查看引擎更新');
  if(!window.__dshbChipSet){
    window.__dshbChipSet=function(state){
      try{
        el.setAttribute('data-s',state==='ok'||state==='update'||state==='busy'?state:'unknown');
        if(state==='update')el.setAttribute('title','有新版本可更新 · 点击查看');
        else if(state==='ok')el.setAttribute('title','已是最新 · 点击查看引擎详情');
      }catch(e){}
    };
  }
  place(el);
  return tv?tv.textContent:'';
})(__V__)
