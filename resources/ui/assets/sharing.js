/* The panel lives outside #app so sharing never discards an editor draft. */
const ShareWidget=(()=>{
  const key='referron.share-intent.v1';
  let opened=false, target=null, busy=false, message='', generation=0;
  const panel=()=>document.getElementById('share-panel');
  function publicPath(path){
    const url=new URL(path,location.origin), p=url.pathname;
    if(url.origin!==location.origin) return '/';
    return ['/', '/essays', '/market', '/manifesto', '/coselling', '/privacy', '/terms'].includes(p)
      || /^\/(essays|topics|guides)\/[^/]+$/.test(p)
      || /^\/@[^/]+$/.test(p)
      || (p!=='/market/cart' && /^\/market\/[^/]+(?:\/products\/[^/]+)?$/.test(p)) ? p : '/';
  }
  function url(path,token){
    const result=new URL(publicPath(path),location.origin);
    if(token) result.searchParams.set('ref',token);
    return result.href;
  }
  function token(){
    return S.session?.authenticated && seated() && hasRole('coseller') && S.coseller?.status==='active'
      && S.coseller['player-name']===S.session.selectedSeat.playerName ? S.coseller['ref-token']||null : null;
  }
  function currentTarget(){
    const path=publicPath(location.pathname), fallback=path!==location.pathname;
    const title=fallback?'Referron Market':document.querySelector('main h1')?.textContent.trim()||'Referron Market';
    return {path,title,fallback};
  }
  function clearIntent(){try{sessionStorage.removeItem(key);}catch(_){}}
  function close(clear=true){
    const wasOpen=opened;opened=false;busy=false;generation++;message='';
    if(clear) clearIntent();
    panel()?.close();
    document.getElementById('share-trigger')?.setAttribute('aria-expanded','false');
    if(wasOpen)document.getElementById('share-trigger')?.focus();
  }
  function draw(){
    if(!opened) return;
    const t=token(), selected=S.session?.selectedSeat?.playerName;
    let content;
    if(busy) content='<p role="status">Checking your sharing account…</p>';
    else if(!S.session?.authenticated) content='<p>Sign in to get your own sharing link for this page. Useful engagement can contribute to eligible earnings.</p><button class="btn fill" onclick="ShareWidget.setup()">Sign in to Share-to-Earn</button>';
    else if(!seated()) content=`<p>${seatChoiceRequired()?'Choose the Referron Market account to use for sharing.':'Choose your public Referron Market handle before activating your sharing link.'}</p><button class="btn fill" onclick="ShareWidget.setup()">${seatChoiceRequired()?'Choose your account':'Choose your handle'}</button>`;
    else if(S.cosellerError) content=`<p role="alert">We couldn’t verify your sharing account. ${esc(S.cosellerError)}</p><button class="btn" onclick="ShareWidget.retry()">Retry</button><button class="btn" onclick="ShareWidget.setup(true)">Refresh sign-in</button>`;
    else if(!t) content=`<p>Activate sharing as <b>@${esc(selected)}</b>. Your personal links record your contribution to eligible purchases. Earnings depend on qualifying activity and settlement.</p><p><a href="/coselling" onclick="ShareWidget.close()" data-nav>Read how Coselling works</a></p><button class="btn fill" onclick="ShareWidget.activate()">Activate Share-to-Earn</button>`;
    else content=`<p>Sharing as <b>@${esc(selected)}</b>. This link includes your attribution.</p><label for="share-link">Your personal link</label><input id="share-link" readonly value="${esc(url(target.path,t))}" onclick="this.select()"><div class="share-panel-actions"><button class="btn fill" onclick="ShareWidget.copy()">Copy link</button>${typeof navigator.share==='function'?'<button class="btn" onclick="ShareWidget.nativeShare()">Share</button>':''}</div>`;
    panel().innerHTML=`<div class="share-panel-heading"><h2 id="share-panel-title">Share-to-Earn</h2><button class="btn" aria-label="Close share panel" onclick="ShareWidget.close()">×</button></div><p class="share-page-title">${esc(target.title)}</p>${target.fallback?'<p class="share-panel-note">This page is private or part of account setup or checkout. Share the Referron Market homepage instead.</p>':''}${content}<p role="status" aria-live="polite" class="share-panel-message">${esc(message)}</p>`;
    if(!panel().open) panel().showModal();
    document.getElementById('share-trigger')?.setAttribute('aria-expanded','true');
    if(!panel().contains(document.activeElement))panel().querySelector('button')?.focus();
  }
  async function verify(){
    const version=++generation;busy=true;draw();
    await loadCoseller();
    if(!opened||version!==generation) return;
    busy=false;draw();
  }
  async function open(saved){
    target=saved||currentTarget();opened=true;message='';
    await verify();
  }
  function setup(forceSignIn=false){
    try{sessionStorage.setItem(key,JSON.stringify(target));}catch(_){message='Could not save your return page. Enable session storage and try again.';draw();return;}
    close(false);
    if(forceSignIn||!S.session?.authenticated){
      const login=signInUrl();
      if(!login){opened=true;message='Sign-in is temporarily unavailable. Please try again later.';draw();return;}
      rememberReturn(target.path);location.assign(login);
    }else go('/join');
  }
  async function resume(){
    if(!S.session?.authenticated) return;
    let saved;
    try{saved=JSON.parse(sessionStorage.getItem(key)||'null');}catch(_){clearIntent();return;}
    if(!saved||typeof saved.path!=='string') return;
    clearIntent();
    const path=publicPath(saved.path);
    history.replaceState({},'',path);render();
    await open({path,title:typeof saved.title==='string'?saved.title:'Referron Market',fallback:!!saved.fallback});
  }
  async function activate(){
    const version=++generation;busy=true;message='';draw();
    await activateCoseller({renderPage:false});
    if(!opened||version!==generation) return;
    busy=false;draw();
  }
  function ready(){
    const t=token();
    if(!opened||busy||!t){message='Your sharing account changed. Reopen Share-to-Earn to verify it.';draw();return null;}
    return url(target.path,t);
  }
  async function copy(){
    const link=ready();if(!link)return;
    try{await navigator.clipboard.writeText(link);message='Personal link copied.';}
    catch(_){message='Copy is unavailable. Select the personal link above and copy it manually.';}
    // Preserve focus when the input is needed for manual copying.
    panel().querySelector('.share-panel-message').textContent=message;
  }
  async function nativeShare(){
    const link=ready();if(!link)return;
    try{await navigator.share({title:target.title,url:link});message='Share request completed.';}
    catch(error){message=error.name==='AbortError'?'':'Sharing is unavailable. You can copy the personal link instead.';}
    if(opened) panel().querySelector('.share-panel-message').textContent=message;
  }
  return {open,close,setup,resume,activate,copy,nativeShare,retry:verify,url,token};
})();
