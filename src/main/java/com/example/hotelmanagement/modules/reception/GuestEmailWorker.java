package com.example.hotelmanagement.modules.reception;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GuestEmailWorker {
    private final GuestEmailQueue queue;
    private final GuestEmailTransport transport;

    @Value("${app.guest-mail.worker-enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${app.guest-mail.poll-ms:10000}")
    public void dispatch() {
        if (enabled) run();
    }

    public void run() {
        for (int i = 0; i < 10; i++) {
            var job = queue.claim();
            if (job == null) return;
            boolean sent = false;
            try {
                transport.send(job.recipient(), job.subject(), job.body(), job.qr());
                sent = true;
            } catch (Exception ignored) {
            }
            queue.finish(job, sent);
        }
    }
}
