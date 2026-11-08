(() => {
    const root=document.getElementById('stay-workflow');
    if(!root) return;
    const cfg=root.dataset, {request,money,line:metric}=window.BookingDesk;
    const labels=Object.fromEntries([...root.querySelectorAll('[data-label]')].map(n=>[n.dataset.label,n.textContent]));
    const statusText=status=>root.querySelector('[data-stay-status="'+status+'"]')?.textContent || status;
    const lookup=document.getElementById('stay-lookup'), lookupForm=document.getElementById('stay-lookup-form');
    const modal=document.getElementById('stay-procedure'), form=document.getElementById('stay-procedure-form');
    const service=document.getElementById('stay-quick-service'), selectRoom=root.querySelector('[data-clean-room]');
    const content=root.querySelector('[data-procedure-content]'), state=root.querySelector('[data-procedure-state]');
    let current,bill,serial=0,lookupSerial=0,busy=false,serviceBusy=false;
    let serviceKey;
    const key=()=>crypto.randomUUID().replaceAll('-','').slice(0,24).toUpperCase();
    const json=(url,data)=>request(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)});
    const elem=(tag,text,cls)=>{const n=document.createElement(tag);n.textContent=text || '';if(cls)n.className=cls;return n;};
    const button=(text,action)=>{const n=elem('button',text,'btn btn-sm btn-outline-primary');n.type='button';n.addEventListener('click',action);return n;};
    function showLookup() {lookup.showModal();lookupForm.elements.q.focus();}
    async function showResults(results) {
        const target=root.querySelector('[data-lookup-results]');target.replaceChildren();
        root.querySelector('[data-lookup-state]').textContent=results.length?'':labels.empty;
        for(const booking of results) {
            const card=elem('article',null,'stay-lookup-card');
            card.append(elem('h6',booking.code+' · '+booking.name),elem('p',booking.phone || '','small text-muted'));
            const link=elem('a',labels.details,'btn btn-sm btn-light');link.href=cfg.bookings+booking.id;card.append(link);
            if(cfg.qr==='true' && booking.rooms.some(r=>['PENDING','CONFIRMED','CHECKED_IN'].includes(r.status))) {
                card.append(button(labels.qr,async()=>{
                    try {
                        const result=await json(cfg.api+booking.id+'/qr',{});
                        root.querySelector('[data-qr-preview]').src=result.image;
                        root.querySelector('[data-qr-payload]').textContent=result.payload;
                        root.querySelector('[data-qr-expiry]').textContent=labels.qrExpiry+' '+new Date(result.expiresAt).toLocaleString(document.documentElement.lang);
                        root.querySelector('[data-issued-qr]').hidden=false;
                    } catch(e) {root.querySelector('[data-lookup-state]').textContent=e.message;}
                }));
            }
            const cashier=elem('button',labels.cashier,'btn btn-sm btn-outline-primary');cashier.type='button';cashier.dataset.openCashier='';cashier.dataset.booking=booking.id;card.append(cashier);
            const occupied=booking.rooms.filter(r=>r.status==='CHECKED_IN');
            if(cfg.checkout==='true' && occupied.length>1) card.append(button(labels.allRooms,()=>open(booking.id,occupied.map(r=>r.id),'checkout',booking.code)));
            for(const room of booking.rooms) {
                const row=elem('div',null,'stay-lookup-line');
                row.append(elem('span',(room.number || '—')+' · '+room.type+' · '+statusText(room.status)+' · '+room.from+' → '+room.until));
                if(cfg.checkin==='true' && room.status==='CONFIRMED' && room.from===cfg.today)
                    row.append(button(labels.checkin,()=>open(booking.id,[room.id],'checkin',booking.code)));
                if(cfg.checkout==='true' && room.status==='CHECKED_IN')
                    row.append(button(labels.checkout,()=>open(booking.id,[room.id],'checkout',booking.code)));
                card.append(row);
            }
            target.append(card);
        }
    }
    lookupForm.addEventListener('submit',async e=>{
        e.preventDefault();const revision=++lookupSerial;
        const state=root.querySelector('[data-lookup-state]');state.textContent='…';
        root.querySelector('[data-issued-qr]').hidden=true;
        try {
            const result=await json(cfg.api+'lookup',{q:lookupForm.elements.q.value,mode:lookupForm.elements.mode.value});
            if(revision===lookupSerial) {showResults(result);if(lookupForm.elements.mode.value!=='CONTACT')lookupForm.elements.q.value='';}
        } catch(error) {if(revision===lookupSerial)state.textContent=error.message;}
    });
    root.querySelector('#stay-qr-image')?.addEventListener('change',async e=>{
        const file=e.target.files[0];if(!file)return;
        const revision=++lookupSerial;const state=root.querySelector('[data-lookup-state]');state.textContent='…';
        try {
            const data=new FormData();data.set('image',file);
            const result=await request(cfg.api+'qr-image',{method:'POST',body:data});
            if(revision===lookupSerial) showResults(result);
        } catch(error) {if(revision===lookupSerial)state.textContent=error.message;}
        finally {e.target.value='';}
    });
    function summary(data) {
        const node=root.querySelector('[data-procedure-summary]');node.replaceChildren(
            metric(labels.total,money(data.projectedTotal)),metric(labels.paid,money(data.netPaid)),
            metric(labels.debt,money(data.projectedDebt)),metric(labels.refund,money(data.refundDue)));
        if(current.action==='checkin')node.append(metric(labels.deposit,money(data.lines[0].preview.depositShortfall)));
        const lines=root.querySelector('[data-bill-lines]');lines.replaceChildren();
        const surcharges=root.querySelector('[data-surcharge-detail]');surcharges.replaceChildren();
        for(const room of data.lines) {
            const p=room.preview;
            lines.append(elem('p',room.room+' · '+room.type+' · '+room.adults+' / '+room.children,'fw-semibold'));
            if(Number(p.credit))lines.append(metric(labels.credit,money(p.credit)));
            if(Number(p.changeFee))lines.append(metric(labels.fee,money(p.changeFee)));
            if(p.surcharge) {
                const fee=p.surcharge;const box=elem('div');
                box.append(elem('strong',room.room+' · '+fee.name),elem('p',fee.minutes+' '+labels.minutes,'small mb-1'));
                const formula=fee.amountType==='PER_HOUR'?money(fee.amountValue)+' × '+fee.billedHours+' '+labels.hours
                    :fee.amountType==='PERCENT_NIGHT'?money(fee.nightBase)+' × '+fee.amountValue+'%':money(fee.amountValue);
                box.append(elem('p',formula+' = '+money(fee.amount),'small mb-1'),elem('p',labels.tax+' '+fee.taxPercent+'%: '+money(fee.tax),'small mb-1'),elem('b',money(fee.total)));
                surcharges.append(box);
            }
        }
        const charges=root.querySelector('[data-bill-charges]');charges.replaceChildren();
        for(const c of data.charges) {
            const row=elem('tr');
            row.append(elem('td',c.service_date),elem('td',c.description),elem('td',money(c.amount),'text-end text-nowrap'));charges.append(row);
        }
        const registered=root.querySelector('[data-stay-registered]');registered.replaceChildren();
        for(const g of data.guests || []) registered.append(elem('p',[g.name,g.birth,g.nationality,g.document].filter(Boolean).join(' · '),'mb-1'));
    }
    function guests(data) {
        const target=root.querySelector('[data-procedure-guests]');target.replaceChildren();
        if(current.action!=='checkin')return;
        const room=data.lines[0];
        for(let i=0;i<room.adults+room.children;i++) {
            const adult=i<room.adults, fragment=document.getElementById('stay-person-template').content.cloneNode(true);
            fragment.querySelector('[data-person-title]').textContent=(adult?labels.adult:labels.child)+' '+(i+1);
            fragment.querySelector('[name=type]').value=adult?'ADULT':'CHILD';
            if(i===0)fragment.querySelector('[name=name]').value=data.contact?.name || '';
            fragment.querySelector('[name=document]').required=adult;
            fragment.querySelector('[name=dateOfBirth]').required=!adult;
            if(adult)fragment.querySelector('option[value=BIRTH_CERT]').remove();
            target.append(fragment);
        }
    }
    function paymentFields() {
        const action=current.action==='checkin'?(cfg.collect==='true'?'COLLECT':'NONE'):form.elements.paymentAction.value;
        form.elements.reason.minLength=action==='REFUND'?10:1;
        root.querySelector('[data-money-fields]').hidden=action==='NONE';
        form.elements.amount.disabled=action==='NONE';
        form.elements.method.disabled=action==='NONE';
        root.querySelector('[data-refund-parent]').hidden=action!=='REFUND';
        form.elements.parent.required=action==='REFUND';
        form.elements.parent.disabled=action!=='REFUND';
    }
    function readiness() {
        if(!bill)return;
        const checkin=current.action==='checkin';
        const ready=!checkin || bill.lines[0].ready || !!selectRoom.value;
        root.querySelector('[data-readiness]').hidden=!checkin;
        root.querySelector('[data-readiness]').className='alert '+(ready?'alert-light-success':'alert-light-danger');
        root.querySelector('[data-readiness]').textContent=ready?labels.ready:labels.notReady;
        root.querySelector('[data-procedure-submit]').disabled=busy || serviceBusy || !ready;
    }
    async function load(resetGuests=false) {
        const revision=++serial;bill=null;state.textContent='…';
        root.querySelector('[data-procedure-submit]').disabled=true;
        try {
            const params=new URLSearchParams();current.lines.forEach(id=>params.append('line',id));
            const data=await request(cfg.bookings+current.booking+'/'+current.action+'-preview?'+params);
            if(revision!==serial || !modal.open)return;
            bill=data;summary(data);
            root.querySelector('[data-procedure-context]').textContent=[data.contact?.code,data.contact?.name,data.contact?.phone].filter(Boolean).join(' · ');
            if(resetGuests)guests(data);
            const checkin=current.action==='checkin';
            root.querySelector('[data-bill-detail]').hidden=checkin;
            root.querySelector('[data-settle-action]').hidden=checkin;
            root.querySelector('[data-clean-choice]').hidden=!checkin || data.lines[0].ready;
            selectRoom.replaceChildren(new Option('—',''));
            if(checkin && cfg.assign==='true') for(const room of data.alternatives)selectRoom.append(new Option(room.number,room.id));
            selectRoom.disabled=cfg.assign!=='true';
            const choices=form.elements.paymentAction;
            choices.value=Number(data.projectedDebt)>0 && cfg.collect==='true'?'COLLECT':Number(data.refundDue)>0 && cfg.refund==='true'?'REFUND':'NONE';
            form.elements.amount.value=checkin?(cfg.collect==='true'?Number(data.lines[0].preview.depositShortfall):0)
                :Math.max(0,choices.value==='REFUND'?Number(data.refundDue):Number(data.projectedDebt));
            form.elements.parent.replaceChildren(...data.refundable.map(p=>new Option(p.transaction_code+' · '+money(p.remaining),p.id)));
            const invoice=root.querySelector('[data-invoice-options]');
            if(invoice) {
                invoice.hidden=checkin;
                form.elements.issueInvoice.disabled=!data.finalRooms;
                if(!data.finalRooms)form.elements.issueInvoice.checked=false;
            }
            if(service) {
                service.hidden=checkin || !data.services.length;
                service.elements.service.replaceChildren(...data.services.map(s=>new Option(s.name+' · '+money(s.unit_price)+' / '+s.unit,s.id)));
                service.elements.line.replaceChildren(...data.lines.map(r=>new Option(r.room,r.id)));
                serviceKey=key();
            }
            state.textContent=Number(data.refundDue)>0 && cfg.refund!=='true'?labels.refundApproval:'';
            paymentFields();readiness();content.hidden=false;
        } catch(error) {if(revision===serial)state.textContent=error.message;}
    }
    async function open(booking,lines,action,code='') {
        if(busy || serviceBusy)return;
        lookup.close();current={booking:Number(booking),lines:lines.map(Number),action};bill=null;
        form.reset();content.hidden=true;root.querySelector('[data-procedure-receipt]').hidden=true;
        root.querySelector('[data-procedure-title]').textContent=labels[action];
        root.querySelector('[data-procedure-context]').textContent=code || '#'+booking;
        modal.showModal();await load(true);
    }
    form.elements.paymentAction.addEventListener('change',()=>{
        form.elements.amount.value=form.elements.paymentAction.value==='REFUND'?bill.refundDue:Math.max(0,Number(bill.projectedDebt));paymentFields();
    });
    selectRoom.addEventListener('change',readiness);
    root.querySelector('[data-reload-procedure]').addEventListener('click',()=>{if(!busy && !serviceBusy)load();});
    root.querySelector('[data-priority]')?.addEventListener('click',async()=>{
        if(!form.elements.reason.reportValidity())return;
        try {await json(cfg.api+'priority',{booking:current.booking,line:current.lines[0],reason:form.elements.reason.value});state.textContent=labels.prioritySent;}
        catch(error){state.textContent=error.message;}
    });
    form.addEventListener('submit',async event=>{
        event.preventDefault();if(busy || serviceBusy || !bill)return;
        busy=true;readiness();state.textContent='…';
        const action=current.action;
        const payload={reason:form.elements.reason.value,key:bill.key,method:form.elements.method.value,amount:form.elements.amount.disabled?0:form.elements.amount.value,
            expectedDebt:bill.projectedDebt,version:bill.version};
        if(action==='checkin') {
            payload.line=current.lines[0];payload.cleanRoom=selectRoom.value?Number(selectRoom.value):null;
            payload.guests=[...root.querySelectorAll('[data-procedure-guests] fieldset')].map(field=>Object.fromEntries([...field.querySelectorAll('input,select')].map(n=>[n.name,n.value || null])));
        } else {
            Object.assign(payload,{lines:current.lines,paymentAction:form.elements.paymentAction.value,parent:form.elements.parent.disabled?null:Number(form.elements.parent.value),
                issueInvoice:!!form.elements.issueInvoice?.checked && !form.elements.issueInvoice?.disabled,
                buyerAddress:form.elements.buyerAddress?.value,taxCode:form.elements.taxCode?.value,buyerCompany:form.elements.buyerCompany?.value});
        }
        try {
            const result=await json(cfg.api+current.booking+'/'+action,payload);
            content.hidden=true;state.textContent='';
            const receipt=root.querySelector('[data-procedure-receipt]');receipt.hidden=false;
            root.querySelector('[data-receipt-booking]').href=cfg.bookings+result.booking;
            const pdf=root.querySelector('[data-receipt-pdf]');pdf.hidden=!result.invoice;
            if(result.invoice) {pdf.href=cfg.invoices+result.invoice+'/pdf';pdf.click();}
            document.dispatchEvent(new CustomEvent('reception:refresh'));
        } catch(error) {state.textContent=error.message+' '+labels.refreshRequired;}
        finally {busy=false;readiness();}
    });
    service?.addEventListener('submit',async event=>{
        event.preventDefault();if(busy || serviceBusy)return;
        serviceBusy=true;readiness();service.querySelector('button').disabled=true;
        try {
            const data=new FormData(service);data.set('booking',current.booking);data.set('key',serviceKey);
            await request(cfg.service,{method:'POST',body:new URLSearchParams(data)});
            await load();document.dispatchEvent(new CustomEvent('reception:refresh'));
        } catch(error){state.textContent=error.message;}
        finally {serviceBusy=false;service.querySelector('button').disabled=false;readiness();}
    });
    document.addEventListener('click',event=>{
        if(event.target.closest('[data-stay-lookup]'))showLookup();
        const close=event.target.closest('[data-stay-close]');if(close && !busy && !serviceBusy)close.closest('dialog').close();
        const all=event.target.closest('[data-stay-all]');
        if(all) {open(all.dataset.booking,all.dataset.stayLines.split(','),'checkout');return;}
        const action=event.target.closest('[data-stay-action],[data-desk-action]');
        if(!action)return;
        const name=action.dataset.stayAction || action.dataset.deskAction;
        if(!['checkin','checkout'].includes(name))return;
        open(action.dataset.booking,[action.dataset.line],name,action.dataset.code);
    });
    modal.addEventListener('cancel',event=>{if(busy || serviceBusy)event.preventDefault();});
    modal.addEventListener('close',()=>{++serial;bill=null;form.reset();root.querySelector('[data-procedure-guests]').replaceChildren();});
    lookup.addEventListener('close',()=>{++lookupSerial;lookupForm.elements.q.value='';});
    const query=new URLSearchParams(window.location.search),action=query.get('action'),booking=query.get('booking'),lineId=query.get('line');
    if(['checkin','checkout'].includes(action) && /^\d+$/.test(booking || '') && /^\d+$/.test(lineId || ''))
        open(booking,[lineId],action);
})();
