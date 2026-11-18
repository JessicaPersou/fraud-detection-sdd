package com.persou.antifraud.notification.adapter.out.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.persou.antifraud.notification.application.port.out.NotificationMessage;
import com.persou.antifraud.notification.application.port.out.NotificationProvider;
import com.persou.antifraud.notification.application.port.out.NotificationProviderException;

/**
 * Provedor de e-mail por SMTP. Na demonstração, o servidor é o Mailpit (docker-compose), que captura
 * as mensagens sem entregá-las. Ativado com {@code notification.provider=smtp}.
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
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(message.recipient());
        mail.setSubject(message.subject());
        mail.setText(message.body());
        try {
            mailSender.send(mail);
        } catch (MailException e) {
            // Mensagem curta: o servidor e o destinatário não aparecem no erro propagado.
            throw new NotificationProviderException("Falha ao enviar e-mail via SMTP");
        }
        return new ProviderReceipt("smtp-" + message.eventId());
    }
}
