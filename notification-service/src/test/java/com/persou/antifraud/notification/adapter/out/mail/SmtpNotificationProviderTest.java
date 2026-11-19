package com.persou.antifraud.notification.adapter.out.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Properties;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import com.persou.antifraud.notification.application.port.out.NotificationMessage;
import com.persou.antifraud.notification.application.port.out.NotificationProviderException;

class SmtpNotificationProviderTest {

    private static final NotificationMessage MESSAGE = new NotificationMessage(
            "evt-1", "EMAIL", "ops-demo@example.invalid",
            "Alerta antifraude: risco MEDIUM",
            "ALERTA DE POSSÍVEL FRAUDE\n\nTransação t-1",
            "<html><body>Transação t-1</body></html>");

    private static MimeMessage newMimeMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    @Test
    void sendsMultipartMessageWithRecipientSubjectAndFrom() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(newMimeMessage());
        SmtpNotificationProvider provider = new SmtpNotificationProvider(sender, "alertas@antifraude.example.invalid");

        var receipt = provider.send(MESSAGE);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        sent.saveChanges(); // o JavaMailSender faz isso ao enviar; o teste precisa fazer para ver o Content-Type
        assertThat(sent.getAllRecipients()).containsExactly(new InternetAddress("ops-demo@example.invalid"));
        assertThat(sent.getFrom()).containsExactly(new InternetAddress("alertas@antifraude.example.invalid"));
        assertThat(sent.getSubject()).isEqualTo("Alerta antifraude: risco MEDIUM");
        assertThat(sent.getContentType()).startsWith("multipart/");
        assertThat(receipt.reference()).isEqualTo("smtp-evt-1");
    }

    @Test
    void smtpFailureBecomesRetryableProviderExceptionWithoutServerDetails() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(newMimeMessage());
        doThrow(new MailSendException("smtp.internal.example:1025 connection refused"))
                .when(sender).send(any(MimeMessage.class));
        SmtpNotificationProvider provider = new SmtpNotificationProvider(sender, "alertas@antifraude.example.invalid");

        assertThatThrownBy(() -> provider.send(MESSAGE))
                .isInstanceOf(NotificationProviderException.class)
                .hasMessageNotContaining("smtp.internal")
                .hasMessageNotContaining("1025");
    }
}
