package com.persou.antifraud.notification.adapter.out.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.persou.antifraud.notification.application.port.out.NotificationMessage;
import com.persou.antifraud.notification.application.port.out.NotificationProviderException;

class SmtpNotificationProviderTest {

    private static final NotificationMessage MESSAGE = new NotificationMessage(
            "evt-1", "EMAIL", "ops-demo@example.invalid",
            "Alerta antifraude: risco MEDIUM", "Transação t-1 classificada com risco MEDIUM.");

    @Test
    void sendsOneMessageWithRecipientSubjectAndFrom() {
        JavaMailSender sender = mock(JavaMailSender.class);
        SmtpNotificationProvider provider = new SmtpNotificationProvider(sender, "alertas@antifraude.example.invalid");

        var receipt = provider.send(MESSAGE);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getTo()).containsExactly("ops-demo@example.invalid");
        assertThat(sent.getFrom()).isEqualTo("alertas@antifraude.example.invalid");
        assertThat(sent.getSubject()).isEqualTo("Alerta antifraude: risco MEDIUM");
        assertThat(receipt.reference()).isEqualTo("smtp-evt-1");
    }

    @Test
    void smtpFailureBecomesRetryableProviderExceptionWithoutServerDetails() {
        JavaMailSender sender = mock(JavaMailSender.class);
        doThrow(new MailSendException("smtp.internal.example:1025 connection refused"))
                .when(sender).send(any(SimpleMailMessage.class));
        SmtpNotificationProvider provider = new SmtpNotificationProvider(sender, "alertas@antifraude.example.invalid");

        assertThatThrownBy(() -> provider.send(MESSAGE))
                .isInstanceOf(NotificationProviderException.class)
                .hasMessageNotContaining("smtp.internal")
                .hasMessageNotContaining("1025");
    }
}
