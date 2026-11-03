(() => {
 'use strict';
 document.querySelectorAll('form[data-pricing-confirm]').forEach(form=>form.addEventListener('submit',event=>{if(!window.confirm(form.dataset.confirmMessage))event.preventDefault();}));
 document.querySelectorAll('[data-weekday-mask]').forEach(button=>button.addEventListener('click',()=>{const mask=Number(button.dataset.weekdayMask);document.querySelectorAll('.pricing-weekday').forEach(input=>{input.checked=(mask&(1<<(Number(input.value)-1)))!==0;});}));
 document.querySelectorAll('[data-all-types]').forEach(button=>button.addEventListener('click',()=>{const inputs=[...document.querySelectorAll('.pricing-type-checkbox:not(:disabled)')];const state=!inputs.every(i=>i.checked);inputs.forEach(i=>i.checked=state);}));
 document.querySelectorAll('[data-calendar-days]').forEach(button=>button.addEventListener('click',()=>{const from=document.getElementById('from');if(!from || !from.value)return;const date=new Date(from.value+'T12:00:00Z');date.setUTCDate(date.getUTCDate()+Number(button.dataset.calendarDays));document.getElementById('until').value=date.toISOString().slice(0,10);from.form.requestSubmit();}));
 const kind=document.getElementById('kind'),valueType=document.getElementById('valueType');
 if(kind&&valueType){const sync=()=>{const fixed=kind.value==='NIGHT_OVERRIDE';const percent=[...valueType.options].find(o=>o.value==='PERCENT');if(percent)percent.disabled=fixed;if(fixed)valueType.value='FIXED';};kind.addEventListener('change',sync);sync();}
 function syncFee(select){let input;if(select.id==='noShowFeeType')input=document.getElementById('noShowFeeValue');else if(select.id==='amountType')input=document.getElementById('amountValue');else input=select.closest('.pricing-tier')?.querySelector('[data-tier-field="feeValue"]');if(!input)return;const zero=['NONE','FIRST_NIGHT'].includes(select.value);input.readOnly=zero;if(zero)input.value='0';}
 document.querySelectorAll('[data-tier-field="feeType"],#noShowFeeType,#amountType').forEach(syncFee);
 document.addEventListener('change',event=>{if(event.target.matches('[data-tier-field="feeType"],#noShowFeeType,#amountType'))syncFee(event.target);});
 function dynamicRows(containerId,templateId,rowClass,fieldAttribute,prefix,addSelector,removeSelector,limit){
  const container=document.getElementById(containerId),template=document.getElementById(templateId);if(!container||!template)return;
  function reindex(){container.querySelectorAll(rowClass).forEach((row,index)=>{row.querySelectorAll('['+fieldAttribute+']').forEach(input=>{const field=input.getAttribute(fieldAttribute);input.name=prefix+'['+index+'].'+field;input.id=prefix+'-'+index+'-'+field;const label=row.querySelector('[data-label="'+field+'"]');if(label)label.htmlFor=input.id;});});}
  document.querySelector(addSelector)?.addEventListener('click',()=>{if(container.querySelectorAll(rowClass).length>=limit)return;container.appendChild(template.content.cloneNode(true));reindex();container.lastElementChild.querySelectorAll('[data-tier-field="feeType"]').forEach(syncFee);});
  container.addEventListener('click',event=>{const button=event.target.closest(removeSelector);if(button&&container.querySelectorAll(rowClass).length>1){button.closest(rowClass).remove();reindex();}});
  reindex();
 }
 dynamicRows('cancellation-tiers','cancellation-tier-template','.pricing-tier','data-tier-field','rules','[data-add-tier]','[data-remove-tier]',30);
 dynamicRows('quote-rooms','quote-room-template','.pricing-room-line','data-room-field','rooms','[data-add-quote-room]','[data-remove-quote-room]',20);
})();
