package com.persou.antifraud.notification.adapter.out.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.persou.antifraud.notification.application.port.out.NotificationMessage;
import com.persou.antifraud.notification.application.port.out.NotificationProvider;
import com.persou.antifraud.notification.application.port.out.NotificationProviderException;

/**
 * Provedor de e-mail por SMTP. Na demonstração, o servidor é o Mailpit (docker-compose), que captura
 * as mensagens sem entregá-las. Ativado com {@code notification.provider=smtp}.
 *
 * <p>Envia multipart/alternative: versão HTML e texto puro de fallback para clientes sem HTML.
 *
 * <p>Falhas do servidor viram {@link NotificationProviderException}, para que o consumidor retente.
 */
@Component
@ConditionalOnProperty(name = "notification.provider", havingValue = "smtp")
class SmtpNotificationProvider implements NotificationProvider {

    private final JavaMailSender mailSender;
    private final String from;

    SmtpNotificationProvider(JavaMailSender mailSender,
                             @Value("${notification.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public ProviderReceipt send(NotificationMessage message) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(message.recipient());
            helper.setSubject(message.subject());
            helper.setText(message.body(), message.htmlBody());
            mailSender.send(mime);
        } catch (MailException | MessagingException e) {
            // Mensagem curta: o servidor e o destinatário não aparecem no erro propagado.
            throw new NotificationProviderException("Falha ao enviar e-mail via SMTP");
        }
        return new ProviderReceipt("smtp-" + message.eventId());
    }
}
