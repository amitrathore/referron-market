/* Private seller commercial terms. Identity and authorization are verified by Referron Market/MoM. */
const commercialFields=[['offerings','Offerings and billing'],['territories','Territories'],['fees','Fees and responsibilities'],['settlement','Settlement'],['holds','Holds'],['reversals','Returns and disputes'],['termination-subscriptions','Subscriptions after termination'],['additional-terms','Additional terms']];
async function commercialRequest(url,data){
  const response=await fetch(url,{method:data?'POST':'GET',credentials:'include',cache:'no-store',headers:{...auth(),...(data?{'Content-Type':'application/json'}:{})},...(data?{body:JSON.stringify(data)}:{})});
  const body=await response.json().catch(()=>({}));
  if(!response.ok) throw new Error(body.error?.message||body.error?.code||'Commercial terms unavailable.');
  return body;
}
async function loadCommercialSchedule(){
  S.commercial=null;S.commercialError=null;
  if(!seated()||!S.sellerAcceptance) return;
  try{S.commercial=await commercialRequest('/api/seller/commercial-schedule');}
  catch(error){S.commercialError=error.message;}
}
function commercialReceipt(receipt){
  return `<p class="commercial-receipt" role="status">Accepted by <b>${esc(receipt['signer-name'])}</b> for ${esc(receipt['seller-legal-name'])} on ${esc(new Date(receipt['accepted-at']).toLocaleString())}. Revision ${esc(receipt.version)}. Receipt <code>${esc(receipt.id)}</code> · SHA-256 <code>${esc(receipt.sha256)}</code>.</p>`;
}
function sellerCommercialStep(){
  if(!S.sellerAcceptance) return '';
  const schedule=S.commercial?.schedule,receipt=S.commercial?.acceptance;
  return `<section id="commercial-terms" class="commercial-panel"><p class="eyebrow">Step 2</p><h2>Commercial terms</h2>
    ${S.commercialError?`<p role="alert">${esc(S.commercialError)}</p><button class="btn" onclick="loadSellerAgreement().then(render)">Try again</button>`:
      !schedule?`<p>${S.sellerAcceptance.status==='activated'?'Your existing seller activation is unchanged. Contact Referron Market about your commercial terms.':S.sellerAcceptance.status==='rejected'?'Your application was not approved. Contact Referron Market for the next step.':'Waiting for Referron Market to prepare your terms.'}</p>`:
      `<div class="commercial-print-document">${schedule.html}${receipt?commercialReceipt(receipt):''}</div>
       <button class="btn" onclick="printCommercial(this)">Print / Save copy</button>
       ${receipt?`<p>${S.sellerAcceptance.status==='activated'?'Seller activated.':'Waiting for Referron Market activation.'}</p>`:
         `<form class="commercial-accept-form" onsubmit="acceptCommercial(event)">
          <label>Your full legal name<input name="signer-name" maxlength="200" value="${esc(S.sellerAcceptance['signer-name'])}" required></label>
          <label>Your title or authority<input name="signer-title" maxlength="120" value="${esc(S.sellerAcceptance['signer-title'])}" required></label>
          <label class="seller-accept-check"><input type="checkbox" name="authorized" required> I am authorized to bind ${esc(schedule['seller-legal-name'])}. I have read these exact commercial terms and intend to accept them electronically.</label>
          <button class="btn fill" type="submit">I Accept these commercial terms</button><p class="commercial-error" role="alert"></p></form>`}`}
  </section>`;
}
async function acceptCommercial(event){
  event.preventDefault();const form=event.currentTarget;
  if(!form.reportValidity()||!S.commercial?.schedule)return;
  const button=event.submitter,schedule=S.commercial.schedule;
  button.disabled=true;
  try{await commercialRequest('/api/seller/commercial-schedule/accept',{...Object.fromEntries(new FormData(form)),accepted:true,authorized:true,'schedule-id':schedule.id,sha256:schedule.sha256});
    await loadSellerAgreement();render();toast('Commercial terms accepted');}
  catch(error){form.querySelector('.commercial-error').textContent=error.message;button.disabled=false;
    if(error.message.includes('latest commercial terms')){await loadCommercialSchedule();render();toast('Terms changed. Review the latest revision.');}}
}
function operatorCommercial(application,latest){
  const c=application.commercial||{},draft=c.draft,schedule=c.schedule;
  const editable=latest&&['pending-operator-activation','approved'].includes(application.status);
  const terms=draft?.terms||schedule?.terms||{};
  return `<section class="commercial-panel"><h3>Commercial Schedule</h3>
    ${schedule?`<p>Published revision ${esc(schedule.version)} · ${c.acceptance?'Accepted':'Awaiting seller acceptance'}</p><details><summary>Published terms and receipt</summary><div class="commercial-print-document">${schedule.html}${c.acceptance?commercialReceipt(c.acceptance):''}</div><button class="btn" onclick="printCommercial(this)">Print / Save copy</button></details>
    <button class="btn" onclick="copyCommercialLink()">Copy seller acceptance link</button>`:'<p>No published commercial terms.</p>'}
    ${editable?`<details ${draft?'open':''}><summary>${draft?'Review commercial draft':'Prepare commercial terms'}</summary>
    ${draft?.suggested?'<p>Prepared 40% draft for Force Equals Inc. Review all terms and save before publishing.</p>':''}
    <form onsubmit="saveCommercialDraft(event)"><input type="hidden" name="acceptance-id" value="${esc(application.id)}">
    <label>Commission (%)<input type="number" name="commission-percent" min="0" max="100" step="0.01" required value="${terms['commission-bps']==null?'':terms['commission-bps']/100}"></label>
    <p>Product subtotal after discounts, excluding separately stated tax and shipping. This is the seller’s total Referron Market network commission contribution.</p>
    <label>Currency<input name="currency" pattern="[A-Z]{3}" maxlength="3" value="${esc(terms.currency||'USD')}" required></label>
    <label>Payment route<select name="payment-route" required><option value="" disabled ${terms['payment-route']?'':'selected'}>Choose a route</option><option value="seller-direct" ${terms['payment-route']==='seller-direct'?'selected':''}>Seller-direct</option><option value="platform-collected" ${terms['payment-route']==='platform-collected'?'selected':''}>Platform-collected</option></select></label>
    ${commercialFields.map(([key,label])=>`<label>${label}<textarea name="${key}" rows="3" maxlength="6000" ${key==='additional-terms'?'':'required'}>${esc(terms[key]||'')}</textarea></label>`).join('')}
    <button class="btn" type="submit">Save commercial draft</button><p class="commercial-error" role="alert"></p></form>
    ${draft?.html?`<details><summary>Draft document preview</summary>${draft.html}</details>`:''}
    ${draft&&!draft.suggested?`<form onsubmit="publishCommercial(event)"><input type="hidden" name="acceptance-id" value="${esc(application.id)}"><input type="hidden" name="draft-id" value="${esc(draft.id)}">
    <p>Publishing offers the saved draft shown above. Unsaved editor changes are not included.</p>
    <label class="seller-accept-check"><input type="checkbox" name="authorized" required> I am authorized to offer this exact saved schedule on behalf of Referron.</label>
    <button class="btn fill" type="submit" ${application.status==='approved'?'':'disabled'}>Publish for acceptance</button>${application.status==='approved'?'':'<p>Approve the application before publishing.</p>'}<p class="commercial-error" role="alert"></p></form>`:''}</details>`:''}
    ${(c.history||[]).length>1?`<details><summary>Schedule history</summary>${c.history.map(revision=>`<details><summary>Revision ${esc(revision.version)} · ${revision.acceptance?'accepted':'not accepted'}</summary><div class="commercial-print-document">${revision.html}${revision.acceptance?commercialReceipt(revision.acceptance):''}</div><button class="btn" onclick="printCommercial(this)">Print / Save copy</button></details>`).join('')}</details>`:''}
  </section>`;
}
async function saveCommercialDraft(event){
  event.preventDefault();const form=event.currentTarget;if(!form.reportValidity())return;
  const raw=Object.fromEntries(new FormData(form)),id=raw['acceptance-id'];
  const terms={...raw,'commission-bps':Math.round(Number(raw['commission-percent'])*100)};
  delete terms['acceptance-id'];delete terms['commission-percent'];
  await sendOperatorCommercial(form,'/api/operator/sellers/commercial-schedule',{'acceptance-id':id,terms});
}
async function publishCommercial(event){
  event.preventDefault();const form=event.currentTarget;if(!form.reportValidity())return;
  await sendOperatorCommercial(form,'/api/operator/sellers/commercial-schedule/publish',{...Object.fromEntries(new FormData(form)),authorized:true});
}
async function sendOperatorCommercial(form,url,data){
  const button=form.querySelector('button[type=submit]');button.disabled=true;
  try{await commercialRequest(url,data);await loadOperatorApplications();render();toast('Commercial schedule saved');}
  catch(error){form.querySelector('.commercial-error').textContent=error.message;button.disabled=false;}
}
async function copyCommercialLink(){
  try{await navigator.clipboard.writeText(location.origin+'/seller-agreement#commercial-terms');toast('Private acceptance link copied');}
  catch(_){toast('Could not copy. Share /seller-agreement#commercial-terms with the seller.');}
}
function printCommercial(button){
  const document=button.parentElement.querySelector('.commercial-print-document');
  if(!document)return;
  const frame=window.document.createElement('iframe');frame.className='commercial-print-frame';frame.title='Commercial Schedule print copy';window.document.body.appendChild(frame);
  const target=frame.contentDocument;target.open();target.write('<!doctype html><html><head><title>Referron Market Commercial Schedule</title><style>body{font:15px/1.6 sans-serif;max-width:800px;margin:30px}code{overflow-wrap:anywhere}h2,h3{break-after:avoid}</style></head><body>'+document.innerHTML+'</body></html>');target.close();
  frame.contentWindow.focus();frame.contentWindow.print();setTimeout(()=>frame.remove(),60000);
}
