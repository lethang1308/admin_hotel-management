package com.example.hotelmanagement.modules.reception;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.*;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class GuestEmailTransport {
    private final ObjectProvider<JavaMailSender> mail;
    private final QrCodeSupport qr;

    @Value("${app.guest-mail.enabled:false}")
    private boolean enabled;

    @Value("${app.guest-mail.from:}")
    private String from;

    public void send(String recipient, String subject, String text, String payload)
            throws Exception {
        var sender = mail.getIfAvailable();
        if (!enabled || sender == null || from.isBlank())
            throw new IllegalStateException("SMTP_NOT_CONFIGURED");
        var message = sender.createMimeMessage();
        var helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(from);
        helper.setTo(recipient);
        helper.setSubject(subject);
        helper.setText(text);
        if (payload != null)
            helper.addAttachment(
                    "booking-checkin-qr.png",
                    new ByteArrayResource(
                            Base64.getDecoder().decode(qr.image(payload).split(",", 2)[1])),
                    "image/png");
        sender.send(message);
    }
}
