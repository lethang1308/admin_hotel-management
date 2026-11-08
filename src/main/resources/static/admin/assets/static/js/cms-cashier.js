(() => {
 const root=document.getElementById('cashier-root');if(!root)return;
 const cfg=root.dataset,{request,money,line:metric}=window.BookingDesk;
 const labels=Object.fromEntries([...root.querySelectorAll('[data-label]')].map(n=>[n.dataset.label,n.textContent]));
 const status=k=>root.querySelector('[data-cashier-status="'+k+'"]')?.textContent || k;
 const modal=document.getElementById('cashier-dialog'),content=root.querySelector('[data-cashier-content]'),state=root.querySelector('[data-cashier-state]');
 const service=document.getElementById('cashier-service'),payment=document.getElementById('cashier-payment'),refund=document.getElementById('cashier-refund'),invoice=document.getElementById('cashier-invoice');
 const shift=document.getElementById('cashier-shift'),reasonDialog=document.getElementById('cashier-reason-dialog'),reasonForm=document.getElementById('cashier-reason');
 let booking,snapshot,view='services',busy=false,loading=false,revision=0,qrRevision=0,reasonAction;
 function clearQr(){++qrRevision;root.querySelector('[data-bank-qr-result]').hidden=true;}
 const newKey=()=>crypto.randomUUID().replaceAll('-','').slice(0,24).toUpperCase();
 const json=(path,data)=>request(cfg.api+path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)});
 const elem=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
 const cell=(row,text,cls)=>row.append(elem('td',text??'—',cls));
 function action(text,fn,cls='btn btn-sm btn-outline-primary'){const n=elem('button',text,cls);n.type='button';n.addEventListener('click',()=>{if(!busy)fn();});return n;}
 function time(v) {
  if(!v)return '—';
  const raw=String(v),date=new Date(/Z$|[+-]\d\d:\d\d$/.test(raw)?raw:raw+'Z');
  return date.toLocaleString(document.documentElement.lang,{timeZone:snapshot?.booking.timezone || 'Asia/Ho_Chi_Minh'});
 }
 function selectView(name) {
  const button=root.querySelector('[data-cashier-view="'+name+'"]');view=button?name:'services';
  root.querySelectorAll('[data-cashier-panel]').forEach(n=>n.hidden=n.dataset.cashierPanel!==view);
  root.querySelectorAll('[data-cashier-view]').forEach(n=>{const active=n.dataset.cashierView===view;n.classList.toggle('btn-primary',active);n.classList.toggle('btn-light',!active);n.setAttribute('aria-pressed',active);});
 }
 function keyForms(){for(const f of [service,payment,refund])if(f)f.dataset.key=newKey();}
 function paymentOptions(initial=false) {
  if(!payment || !snapshot)return;
  const f=payment.elements,b=snapshot.balance;
  f.kind.querySelector('option[value=DEPOSIT]').disabled=!b.canDeposit || Number(b.depositShortfall)<=0;
  if(initial || f.kind.selectedOptions[0].disabled)f.kind.value=b.canDeposit && Number(b.depositShortfall)>0?'DEPOSIT':'PAYMENT';
  if(initial)f.amount.value=Math.max(0,Number(f.kind.value==='DEPOSIT'?b.depositShortfall:b.debt));
  const cash=f.method.value==='CASH';
  root.querySelector('[data-bank-fields]').hidden=cash;
  f.reference.disabled=cash;f.proof.disabled=cash;
  const text=root.querySelector('[data-cashier-shift]');
  text.className='small '+(cash && !snapshot.shift.id?'text-danger':'text-muted');
  text.textContent=!snapshot.shift.employee?labels.employeeMissing:snapshot.shift.id?labels.shiftOpen+' #'+snapshot.shift.id+' · '+money(snapshot.shift.expected):labels.shiftMissing;
  const open=root.querySelector('[data-cashier-open-shift]');if(open){open.hidden=!!snapshot.shift.id || !snapshot.shift.employee;if(cash && !snapshot.shift.id)open.open=true;}
  const advance=Number(f.amount.value)>Number(b.debt) && f.kind.value==='PAYMENT';
  f.reason.required=advance;f.reason.placeholder=advance?labels.advance:'';
  payment.querySelector('[data-payment-save]').disabled=busy || (cash && !snapshot.shift.id);
 }
 function pricePreview() {
  if(!service || !snapshot)return;
  const item=snapshot.items.find(s=>String(s.id)===service.elements.service.value);
  const target=root.querySelector('[data-service-preview]');target.replaceChildren();
  const q=Number(service.elements.quantity.value);
  const disabled=!item || !service.elements.line.value || !!snapshot.balance.invoice || !Number.isFinite(q) || q<=0;
  service.querySelector('[data-service-save]').disabled=disabled || busy;
  if(!item)return;
  const raw=Math.round((q*Number(item.unit_price)+Number.EPSILON)*100)/100,net=Math.round(raw),tax=Math.round(net*Number(item.tax_percent)/100);
  target.append(metric(labels.unitPrice,money(item.unit_price)+' / '+item.unit),metric(labels.tax,item.tax_percent+'% · '+money(tax)),metric(labels.totalCost,money(net+tax)));
  if(net!==raw)target.append(elem('small',labels.rounding));
 }
 function filterItems(previous) {
  if(!service || !snapshot)return;
  const category=service.elements.category.value;
  const items=snapshot.items.filter(s=>!category || String(s.category_id)===category);
  service.elements.service.replaceChildren(...items.map(s=>new Option(s.name+' · '+money(s.unit_price)+' / '+s.unit,s.id)));
  if(items.some(s=>String(s.id)===previous))service.elements.service.value=previous;
  pricePreview();
 }
 function render(initial=false) {
  const s=snapshot,b=s.balance;
  root.querySelector('[data-cashier-booking]').textContent=s.booking.booking_code+' · '+s.booking.contact_name+' · '+(s.booking.contact_phone || '');
  const totals=root.querySelector('[data-cashier-balance]');totals.replaceChildren();
  for(const key of ['total','net','debt','refundDue']) {
   const box=elem('div');box.append(elem('small',labels[key]),elem('strong',money(b[key])));
   if(key==='debt')box.dataset.debtState=Number(b.debt)>0?'owed':Number(b.debt)<0?'refund':'settled';
   totals.append(box);
  }
  const progress=root.querySelector('[data-deposit-progress]');progress.max=Math.max(1,Number(b.depositRequired));progress.value=Math.max(0,Math.min(Number(b.net),Number(b.depositRequired)));
  root.querySelector('[data-deposit-caption]').textContent=labels.depositRequired+' '+money(b.depositRequired)+' · '+labels.depositShortfall+' '+money(b.depositShortfall);
  if(Number(b.depositRequired)>0 && Number(b.depositShortfall)===0)root.querySelector('[data-deposit-caption]').append(elem('span',labels.depositSatisfied,'badge bg-light-success ms-2'));
  if(service) {
   const line=service.elements.line.value,category=service.elements.category.value,item=service.elements.service.value;
   service.elements.line.replaceChildren(...s.rooms.filter(r=>r.status==='CHECKED_IN').map(r=>new Option((r.room_number||'—')+' · '+r.room_type_name_snapshot,r.id)));
   if([...service.elements.line.options].some(o=>o.value===line))service.elements.line.value=line;
   service.elements.category.replaceChildren(new Option(labels.all,''),...s.categories.map(c=>new Option(c.name,c.id)));
   if([...service.elements.category.options].some(o=>o.value===category))service.elements.category.value=category;
   filterItems(item);
  }
  const usages=root.querySelector('[data-cashier-usages]');usages.replaceChildren();
  for(const u of s.usages) {
   const row=elem('tr');cell(row,u.room_number);const name=elem('td');name.append(elem('strong',u.service_name_snapshot),elem('small',time(u.used_at)+' · '+(u.recorded_by || ''),'d-block text-muted'));if(u.notes)name.append(elem('small',u.notes,'d-block text-muted'));row.append(name);
   cell(row,u.quantity+' '+u.unit_snapshot);cell(row,money(u.unit_price_snapshot));cell(row,u.tax_percent_snapshot+'%');cell(row,money(u.total),'text-nowrap');
   const controls=elem('td');controls.append(elem('span',status(u.status),'badge bg-light-secondary me-2'));
   if(service && u.status==='ACTIVE' && !b.invoice)controls.append(action(labels.void,()=>ask(labels.void,1,false,async note=>json(booking+'/services/'+u.id+'/void',{reason:note}))));
   if(u.void_reason)controls.append(elem('small',u.void_reason,'d-block text-muted'));row.append(controls);usages.append(row);
  }
  if(!s.usages.length){const row=elem('tr'),c=elem('td',labels.closed,'text-muted');c.colSpan=7;row.append(c);usages.append(row);}
  const transactions=root.querySelector('[data-cashier-payments]');transactions.replaceChildren();
  for(const p of s.transactions) {
   const row=elem('tr');cell(row,p.transaction_code);cell(row,status(p.kind)+' · '+status(p.status));cell(row,status(p.method)+(p.work_session_id?' #'+p.work_session_id:''));cell(row,money(p.amount));
   const ref=elem('td',p.provider_transaction_id || '—');
   if(Number(p.has_proof)){const a=elem('a',labels.proof,'d-block small');a.href=cfg.api+'proofs/'+p.id;a.target='_blank';a.rel='noopener';ref.append(a);}
   if(p.reason)ref.append(elem('small',p.reason,'d-block text-muted'));row.append(ref);cell(row,time(p.paid_at));cell(row,p.recorded_by);transactions.append(row);
  }
  if(refund) {
   const parent=refund.elements.parent.value,parents=s.transactions.filter(p=>p.kind!=='REFUND' && p.status==='SUCCEEDED' && Number(p.remaining)>0);
   refund.elements.parent.replaceChildren(...parents.map(p=>new Option(p.transaction_code+' · '+money(p.remaining),p.id)));
   if(parents.some(p=>String(p.id)===parent))refund.elements.parent.value=parent;
   const chosen=parents.find(p=>String(p.id)===refund.elements.parent.value),max=Math.min(Number(b.refundDue),Number(chosen?.remaining || 0));
   refund.elements.amount.max=Math.max(1,max);if(initial)refund.elements.amount.value=max;
   refund.querySelector('[data-refund-save]').disabled=busy || max<=0;
  }
  const requests=root.querySelector('[data-cashier-requests]');requests.replaceChildren();
  for(const r of s.requests) {
   const card=elem('article',undefined,'cashier-request');card.append(elem('strong','#'+r.id+' · '+status(r.status)+' · '+money(r.amount)),elem('p',r.reason,'mb-1'),elem('small',r.requester+' · '+time(r.created_at),'d-block text-muted'));
   if(r.response_note)card.append(elem('p',r.response_note,'small mt-2 mb-0'));
   if(r.status==='PENDING') {
    if(cfg.refund==='true') {
     card.append(action(labels.approve,()=>ask(labels.approve,10,r.method==='BANK_TRANSFER',async(note,reference)=>json(booking+'/refund-requests/'+r.id+'/decide',{approve:true,note,reference}))));
     card.append(action(labels.reject,()=>ask(labels.reject,10,false,async note=>json(booking+'/refund-requests/'+r.id+'/decide',{approve:false,note}))));
    }
    if(String(r.requested_by)===cfg.user)card.append(action(labels.withdraw,()=>run(async()=>{await json(booking+'/refund-requests/'+r.id+'/withdraw',{});await load();})));
   }
   requests.append(card);
  }
  if(invoice) {
   const blocked=!b.finalRooms || !!b.invoice;
   invoice.querySelector('[data-invoice-save]').disabled=blocked || busy;
   root.querySelector('[data-invoice-caption]').textContent=b.finalRooms?labels.invoiceReady:labels.invoiceFinal;
   const link=root.querySelector('[data-existing-invoice]');link.hidden=!b.invoice;if(b.invoice)link.href=cfg.invoices+b.invoice+'/pdf';
  }
  paymentOptions(initial);countdown();content.hidden=false;
 }
 function countdown() {
  if(!snapshot)return;
  const node=root.querySelector('[data-hold-countdown]'),v=snapshot.booking.hold_expires_at;
  if(snapshot.booking.status!=='PENDING' || !v){node.textContent='';return;}
  const seconds=Math.max(0,Math.ceil((new Date(/Z$/.test(v)?v:v+'Z').getTime()-Date.now())/1000));
  node.textContent=seconds?Math.floor(seconds/60)+':'+String(seconds%60).padStart(2,'0'):labels.expired;
  if(!seconds && payment?.elements.kind.value==='DEPOSIT')payment.querySelector('[data-payment-save]').disabled=true;
 }
 async function load(initial=false) {
  if(loading)return;
  const serial=++revision;loading=true;
  try {const data=await request(cfg.api+booking);if(serial!==revision || !modal.open)return;snapshot=data;render(initial);}
  catch(error){state.textContent=error.message || labels.priceChanged;}
  finally{loading=false;}
 }
 async function run(fn) {
  if(busy)return;busy=true;state.textContent=labels.loading;
  root.querySelectorAll('form button[type=submit],form button:not([type])').forEach(b=>b.disabled=true);
  try{await fn();state.textContent=labels.success;document.dispatchEvent(new CustomEvent('reception:refresh'));}
  catch(error){state.textContent=error.message || labels.priceChanged;}
  finally{busy=false;root.querySelectorAll('form button[type=submit],form button:not([type])').forEach(b=>b.disabled=false);if(snapshot)render();}
 }
 function ask(title,min,reference,fn) {
  reasonAction=fn;reasonForm.reset();reasonForm.elements.reason.minLength=min;
  root.querySelector('[data-reason-heading]').textContent=title;
  root.querySelector('[data-decision-reference]').hidden=!reference;
  root.querySelector('[data-reason-error]').textContent='';reasonDialog.showModal();
 }
 reasonForm.addEventListener('submit',async e=>{
  e.preventDefault();if(busy)return;
  busy=true;reasonForm.querySelector('button').disabled=true;
  try {await reasonAction(reasonForm.elements.reason.value,reasonForm.elements.reference.value);reasonDialog.close();await load();state.textContent=labels.success;document.dispatchEvent(new CustomEvent('reception:refresh'));}
  catch(error){root.querySelector('[data-reason-error]').textContent=error.message;}
  finally{busy=false;reasonForm.querySelector('button').disabled=false;if(snapshot)render();}
 });
 async function open(id,tab='services') {
  if(busy)return;
  ++revision;clearQr();booking=id;snapshot=null;loading=false;content.hidden=true;state.textContent=labels.loading;
  for(const d of ['stay-lookup']){const n=document.getElementById(d);if(n?.open)n.close();}
  for(const f of [service,payment,refund,invoice])f?.reset();keyForms();
  if(!modal.open)modal.showModal();selectView(tab);await load(true);if(snapshot)state.textContent='';
 }
 service?.addEventListener('input',pricePreview);
 service?.elements.category.addEventListener('change',()=>filterItems());
 service?.addEventListener('click',e=>{
  const button=e.target.closest('[data-quantity]');if(!button)return;
  service.elements.quantity.value=Math.max(.001,Number((Number(service.elements.quantity.value)+Number(button.dataset.quantity)).toFixed(3)));pricePreview();
 });
 service?.addEventListener('submit',e=>{
  e.preventDefault();run(async()=>{
   const f=service.elements,item=snapshot.items.find(s=>String(s.id)===f.service.value);
   await json(booking+'/service',{line:Number(f.line.value),service:Number(f.service.value),quantity:f.quantity.value,key:service.dataset.key,notes:f.notes.value,price:item.unit_price,tax:item.tax_percent});
   service.dataset.key=newKey();f.quantity.value=1;f.notes.value='';await load();
  });
 });
 payment?.addEventListener('input',()=>{
  clearQr();paymentOptions();
 });
 payment?.elements.kind.addEventListener('change',()=>{payment.elements.amount.value=payment.elements.kind.value==='DEPOSIT'?snapshot.balance.depositShortfall:Math.max(0,Number(snapshot.balance.debt));paymentOptions();});
 root.querySelector('[data-pay-debt]')?.addEventListener('click',()=>{clearQr();payment.elements.amount.value=Math.max(0,Number(snapshot.balance.debt));paymentOptions();});
 payment?.addEventListener('submit',e=>{
  e.preventDefault();run(async()=>{
   const data=new FormData(payment);data.set('key',payment.dataset.key);data.set('token',snapshot.token);
   await request(cfg.api+booking+'/collect',{method:'POST',body:data});
   payment.reset();clearQr();payment.dataset.key=newKey();await load(true);
  });
 });
 root.querySelector('[data-bank-qr]')?.addEventListener('click',async()=>{
  if(!payment.elements.amount.reportValidity())return;
  const serial=++qrRevision;
  try {
   const data=await request(cfg.api+booking+'/bank-qr?'+new URLSearchParams({amount:payment.elements.amount.value}));
   if(serial!==qrRevision || !modal.open)return;
   const target=root.querySelector('[data-bank-qr-result]');target.replaceChildren();
   const img=elem('img');img.src=data.image;img.alt='VietQR';const info=elem('div');
   info.append(metric(labels.bankName,data.bank.bin),metric(labels.account,data.bank.account),metric(labels.owner,data.bank.name),metric(labels.totalCost,money(data.amount)),metric(labels.memo,data.memo));
   target.append(img,info);target.hidden=false;
  } catch(error){state.textContent=error.message || labels.bankMissing;}
 });
 shift?.addEventListener('submit',e=>{e.preventDefault();run(async()=>{await json('shift',{action:'open',cash:shift.elements.cash.value,notes:shift.elements.notes.value});await load();});});
 refund?.elements.parent.addEventListener('change',()=>{
  const p=snapshot.transactions.find(p=>String(p.id)===refund.elements.parent.value),max=Math.min(Number(snapshot.balance.refundDue),Number(p?.remaining || 0));
  refund.elements.amount.max=Math.max(1,max);refund.elements.amount.value=max;
 });
 refund?.addEventListener('submit',e=>{
  e.preventDefault();run(async()=>{
   const f=refund.elements;
   await json(booking+(cfg.refund==='true'?'/refund':'/refund-request'),{key:refund.dataset.key,parent:Number(f.parent.value),amount:f.amount.value,method:f.method.value,reason:f.reason.value,reference:f.reference?.value || null,token:snapshot.token});
   refund.reset();refund.dataset.key=newKey();await load(true);
  });
 });
 invoice?.addEventListener('submit',e=>{
  e.preventDefault();run(async()=>{
   const f=invoice.elements,result=await json(booking+'/invoice',{company:f.company.value,address:f.address.value,taxCode:f.taxCode.value,token:snapshot.token});
   await load();const link=root.querySelector('[data-existing-invoice]');link.href=cfg.invoices+result.id+'/pdf';link.hidden=false;link.click();
  });
 });
 document.addEventListener('click',e=>{
  const trigger=e.target.closest('[data-open-cashier]');if(trigger){e.preventDefault();open(trigger.dataset.booking,trigger.dataset.cashierTab || 'services');}
  const close=e.target.closest('[data-cashier-close]');if(close && !busy)close.closest('dialog').close();
  const tab=e.target.closest('[data-cashier-view]');if(tab)selectView(tab.dataset.cashierView);
  if(e.target.closest('[data-cashier-refresh]') && !busy)load();
 });
 for(const dialog of [modal,reasonDialog])dialog.addEventListener('cancel',e=>{if(busy)e.preventDefault();});
 modal.addEventListener('close',()=>{++revision;++qrRevision;snapshot=null;for(const f of [service,payment,refund])f?.reset();});
 document.addEventListener('reception:refresh',()=>{if(modal.open && !busy)load();});
 const timer=setInterval(()=>{if(modal.open && !document.hidden && !busy)load();},20000),clock=setInterval(countdown,1000);
 window.addEventListener('pagehide',()=>{clearInterval(timer);clearInterval(clock);});
 const q=new URLSearchParams(window.location.search),id=q.get('booking');
 if(window.location.pathname.endsWith('/receptionist/cashier') && /^\d+$/.test(id || ''))open(id,q.get('tab') || 'services');
})();
