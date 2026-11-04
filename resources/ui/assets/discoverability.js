/* Operator-only discovery controls. Public artifacts contain approved copy only. */
const Discovery={data:null,error:'',busy:false,form:null};
async function loadDiscovery(){
  if(!hasRole('operator')) return;
  try{
    const r=await fetch('/api/operator/discoverability',{credentials:'include',headers:auth()});
    const body=await r.json();
    if(!r.ok||!body.success) throw Error(body.error?.message||body.error?.code||'Could not load discoverability');
    Discovery.data=body.data; Discovery.error='';
  }catch(e){Discovery.error=e.message;}
}
async function discoveryAction(operation,data){
  if(Discovery.busy) return;
  Discovery.busy=true;
  render();
  try{
    const r=await fetch('/api/operator/discoverability/'+operation,{method:'POST',credentials:'include',headers:{...auth(),'Content-Type':'application/json'},body:JSON.stringify(data)});
    const body=await r.json();
    if(!r.ok||!body.success) throw Error(body.error?.message||body.error?.code||'Action failed');
    Discovery.form=null; await loadDiscovery();
    toast(operation==='review-narrative'?'Narrative review recorded':operation==='sync-registration'?'Protocol registration synchronized':'Discoverability updated');
  }catch(e){Discovery.error=e.message;}
  finally{Discovery.busy=false;render();}
}
function submitDiscoveryNarrative(event){
  event.preventDefault();
  const fields=Object.fromEntries(new FormData(event.currentTarget));
  Discovery.form=fields; discoveryAction('submit-narrative',fields);
}
function viewDiscoverability(){
  if(!hasRole('operator')) return `<section class="sec"><h1>Discoverability</h1><p>An Operator seat is required.</p></section>`;
  const d=Discovery.data, current=d?.['approved-narrative'], draft=d?.draft;
  const fields=Discovery.form||draft||{name:d?.profile?.name,summary:d?.profile?.summary,text:current?.text};
  return `<section class="sec discovery-page"><h1>Discoverability</h1>
    <p class="lede">Public facts refresh automatically. Review narrative changes before they go live.</p>
    ${Discovery.error?`<p class="form-error" role="alert">${esc(Discovery.error)}</p>`:''}
    <button class="btn" onclick="loadDiscovery().then(render)" ${Discovery.busy?'disabled':''}>Refresh audit</button>
    ${!d?'<p>Loading discovery status…</p>':`
      <h2>Published artifacts</h2><p>${esc(d.audit?.['resource-count'])} public pages · Protocol ${esc(d.version)}</p>
      <p class="mono discovery-revision">Source revision: ${esc(d.audit?.['source-revision'])}</p>
      <div class="discovery-links">${['llms.txt','sitemap.xml','robots.txt'].map(path=>`<a href="/${path}" target="_blank" rel="noopener">${path} ↗</a>`).join('')}</div>
      <details><summary>Preview llms.txt</summary><pre id="discovery-artifact">${esc(d.preview||'Open the published artifact to read its current contents.')}</pre></details>
      <h2>Audit findings</h2>${d.audit?.findings?.length?`<ul>${d.audit.findings.map(f=>`<li><b>${esc(f.severity)} · ${esc(f.code)}</b> ${esc(f.path)} — ${esc(f.message)}</li>`).join('')}</ul>`:'<p>No inventory issues found.</p>'}
      <p>Audits check published sources and generated output. They do not measure search ranking or AI citations.</p>
      <h2>Narrative review</h2><p>Current revision: ${esc(current?.id||'initial')}</p>
      <details><summary>Current approved narrative</summary><pre>${esc(current?.text)}</pre></details>
      ${draft?`<details open><summary>Pending revision ${esc(draft.id.slice(0,12))}</summary><pre>${esc(draft.text)}</pre></details>
        <p>Submitted by ${esc(draft['submitted-by'])}</p>
        <button class="btn fill" onclick="discoveryAction('review-narrative',{revision:'${esc(draft.id)}',decision:'approve'})" ${Discovery.busy?'disabled':''}>Approve this revision</button>
        <button class="btn" onclick="discoveryAction('review-narrative',{revision:'${esc(draft.id)}',decision:'reject'})" ${Discovery.busy?'disabled':''}>Reject</button>`:''}
      <form onsubmit="submitDiscoveryNarrative(event)" class="discovery-form">
        <label>Site name<input name="name" maxlength="150" value="${esc(fields.name||'')}" required></label>
        <label>Short summary<textarea name="summary" maxlength="1000" rows="4" required>${esc(fields.summary||'')}</textarea></label>
        <label>Narrative (Markdown)<textarea name="text" maxlength="30000" rows="10" required>${esc(fields.text||'')}</textarea></label>
        <button class="btn" ${Discovery.busy?'disabled':''}>Submit narrative for review</button>
      </form>
      <h2>Site configuration</h2><form class="discovery-form" onsubmit="event.preventDefault();discoveryAction('update-profile',Object.fromEntries(new FormData(this)))">
        <label>Canonical origin<input type="url" name="origin" value="${esc(d.profile.origin)}" required></label>
        <label>Default social image<input name="image" value="${esc(d.profile.image||'')}"></label>
        <button class="btn" ${Discovery.busy?'disabled':''}>Save configuration</button>
      </form>
      <details><summary>Protocol registration</summary>
        <p>Synchronize this existing game's protocol declarations and protected namespaces with its deployed specification. MoM requires your signed-in session to be a platform administrator. This updates registration metadata; content, commerce records, and memberships are preserved.</p>
        <button class="btn" onclick="discoveryAction('sync-registration',{})" ${Discovery.busy?'disabled':''}>Sync protocol registration</button>
      </details>
      <h2>Review history</h2>${d.history?.length?`<ul>${d.history.map(r=>`<li>${esc(r.decision)} · ${esc(r.id.slice(0,12))} · ${esc(r['reviewed-by'])}</li>`).join('')}</ul>`:'<p>No reviews yet.</p>'}
    `}</section>`;
}
