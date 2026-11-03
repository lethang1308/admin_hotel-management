(() => {
 'use strict';
 document.querySelectorAll('form[data-confirm-delete]').forEach(form => form.addEventListener('submit', event => {if(!window.confirm(form.dataset.confirmMessage)) event.preventDefault();}));
 const modal = document.getElementById('roomQuickModal');
 if (!modal) return;
 const next = document.getElementById('quickNextStatus');
 const until = document.getElementById('quickMaintenanceUntil');
 const transitions = {AVAILABLE:['OCCUPIED','DIRTY','CLEANING','MAINTENANCE'],OCCUPIED:['DIRTY'],DIRTY:['CLEANING','MAINTENANCE'],CLEANING:['AVAILABLE','DIRTY','MAINTENANCE'],MAINTENANCE:[],INACTIVE:['CLEANING']};
 const updateMaintenance = () => {const active=next && next.value==='MAINTENANCE';document.getElementById('quickMaintenanceWrap').hidden=!active;if(until) {until.required=active;until.disabled=!active;}};
 if(next) next.addEventListener('change',updateMaintenance);
 modal.addEventListener('show.bs.modal',event => {
  const button=event.relatedTarget;if(!button)return;const data=button.dataset;const base=document.getElementById('quickDetail').getAttribute('href').replace(/\/rooms\/[^/]+$/, '/rooms/');
  document.getElementById('quickRoomNumber').textContent=data.roomNumber;
  document.getElementById('quickRoomType').textContent=data.roomType;
  const badge=document.getElementById('quickRoomStatus');badge.textContent=data.roomStatusLabel;badge.className='hotel-state mb-4 state-'+data.roomStatus;
  document.getElementById('quickDetail').href=base+encodeURIComponent(data.roomId);
  const block=document.getElementById('quickBlock');if(block)block.href=base.replace(/rooms\/$/,'room-blocks')+'?roomId='+encodeURIComponent(data.roomId);
  const form=document.getElementById('quickStatusForm');
  if(form){form.action=base+encodeURIComponent(data.roomId)+'/status';document.getElementById('quickVersion').value=data.roomVersion;document.getElementById('quickReason').value='';
   const allowed=data.roomStatus==='INACTIVE'&&!document.getElementById('quickCanReactivate')?[]:(transitions[data.roomStatus]||[]);Array.from(next.options).forEach(o=>{o.hidden=!allowed.includes(o.value);o.disabled=o.hidden;});const first=Array.from(next.options).find(o=>!o.disabled);next.value=first?first.value:'';form.hidden=!first;updateMaintenance();}
 });
})();
