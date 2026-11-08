(() => {
 const form=document.querySelector('[data-guest-preferences]');
 if(form){
  const f=form.elements,save=form.querySelector('[data-save-preferences]'),result=form.querySelector('[data-addon-result]');
  let serial=0;
  const invalidate=()=>{++serial;f.token.value='';save.disabled=true;result.replaceChildren();};
  function select(){
   invalidate();const o=f.line.selectedOptions[0];if(!o)return;
   f.revision.value=o.dataset.revision;f.beds.value=o.dataset.beds;f.guests.value=o.dataset.guests;f.lineNotes.value=o.dataset.notes;
   for(const c of form.querySelectorAll('[name=tags]'))c.checked=o.dataset.tags.split(',').includes(c.value);
  }
  form.addEventListener('input',invalidate);f.line.addEventListener('change',select);select();
  form.querySelector('[data-preview-addons]').addEventListener('click',async()=>{
   const current=++serial;save.disabled=true;
   try{
    const data=await window.BookingDesk.request(form.dataset.preview+'?'+new URLSearchParams({line:f.line.value,beds:f.beds.value,guests:f.guests.value}));
    if(current!==serial)return;
    if(Number(data.revision)!==Number(f.revision.value)){result.textContent=form.dataset.stale;return;}
    result.replaceChildren(...data.addons.map(a=>window.BookingDesk.line(a.name+' × '+a.quantity,window.BookingDesk.money(Number(a.amount)+Number(a.tax)))));
    result.append(window.BookingDesk.line('VND',window.BookingDesk.money(data.total)));
    f.token.value=data.token;f.revision.value=data.revision;save.disabled=false;
   }catch(error){if(current===serial)result.textContent=error.message;}
  });
  form.addEventListener('submit',e=>{if(!f.token.value){e.preventDefault();return;}save.disabled=true;});
 }
 const modal=document.getElementById('roomQuickModal');
 modal?.addEventListener('show.bs.modal',e=>{
  const id=e.relatedTarget?.dataset.roomId,target=document.getElementById('room-care-actions'),template=document.getElementById('room-care-'+id);
  target?.replaceChildren(...(template?[template.content.cloneNode(true)]:[]));
  const key=target?.querySelector('[data-care-fault-key]');
  if(key)key.value=crypto.randomUUID().replaceAll('-','').slice(0,24).toUpperCase();
 });
 document.addEventListener('click',e=>{
  if(e.target.closest('#roomQuickModal [data-open-cashier],#roomQuickModal [data-stay-action]'))bootstrap.Modal.getInstance(modal)?.hide();
 });
})();
