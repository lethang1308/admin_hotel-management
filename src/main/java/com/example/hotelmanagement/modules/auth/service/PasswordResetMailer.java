package com.example.hotelmanagement.modules.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.*;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.javamail.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;

@Component
@RequiredArgsConstructor
@Slf4j
public class PasswordResetMailer {
    private final ObjectProvider<JavaMailSender> mail;

    @Qualifier("passwordResetExecutor")
    @Autowired
    private TaskExecutor executor;

    @Value("${app.guest-mail.enabled:false}")
    private boolean enabled;

    @Value("${app.guest-mail.from:}")
    private String from;

    @Value("${app.frontend-url:http://localhost:5173}")
    private String frontend;

    @jakarta.annotation.PostConstruct
    void validateUrl() {
        URI uri = URI.create(frontend);
        if (!java.util.Set.of("http", "https").contains(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getUserInfo() != null)
            throw new IllegalStateException("Invalid app.frontend-url");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void requested(PasswordResetService.Requested request) {
        try {
            executor.execute(() -> deliver(request));
        } catch (RuntimeException rejected) {
            log.warn("Password reset delivery is temporarily unavailable");
        }
    }

    private void deliver(PasswordResetService.Requested request) {
        try {
            var sender = mail.getIfAvailable();
            if (!enabled || sender == null || from.isBlank()) {
                log.warn("Password reset mail is disabled or not configured");
                return;
            }
            String link =
                    frontend.replaceAll("/+$", "") + "/reset-password?token=" + request.token();
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(request.recipient());
            helper.setFrom(from);
            helper.setSubject("StayNest - Yêu cầu khôi phục mật khẩu");
            String text =
                    "Bạn đã yêu cầu đặt lại mật khẩu StayNest. Liên kết có hiệu lực 30 phút và chỉ"
                        + " dùng một lần: "
                            + link
                            + "\nNếu bạn không yêu cầu, hãy bỏ qua email này.";
            String html =
                    "<html><body"
                        + " style=\"background:#f7f5ef;font-family:Arial,sans-serif;padding:32px;color:#1a2e20\"><div"
                        + " style=\"max-width:520px;margin:auto;background:white;padding:32px;border-radius:16px\"><h1>StayNest</h1><h2>Khôi"
                        + " phục mật khẩu</h2><p>Liên kết có hiệu lực trong 30 phút và chỉ dùng một"
                        + " lần.</p><p style=\"margin:28px 0\"><a"
                        + " style=\"background:#1a2e20;color:white;padding:14px"
                        + " 22px;border-radius:8px;text-decoration:none\" href=\""
                            + HtmlUtils.htmlEscape(link)
                            + "\">Đặt lại mật khẩu ngay</a></p><p>Nếu bạn không yêu cầu, hãy bỏ qua"
                            + " email này. Mật khẩu của bạn vẫn giữ"
                            + " nguyên.</p></div></body></html>";
            helper.setText(text, html);
            sender.send(message);
        } catch (Exception failed) {
            log.warn("Password reset mail could not be delivered");
        }
    }
}
