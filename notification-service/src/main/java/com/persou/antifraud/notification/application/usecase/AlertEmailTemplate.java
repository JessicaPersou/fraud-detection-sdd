package com.persou.antifraud.notification.application.usecase;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

import com.persou.antifraud.contracts.FraudDetectedEvent;

/**
 * Monta o conteúdo do e-mail de alerta: texto puro (fallback) e HTML.
 *
 * <p>Não inclui o {@code customerId} nem o destinatário: o e-mail traz só o identificador da transação,
 * o nível de risco, o instante da análise e as regras acionadas.
 */
final class AlertEmailTemplate {

    static final String SUBJECT_PREFIX = "Alerta antifraude: risco ";

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm", Locale.of("pt", "BR"));

    /** Rótulo em português e cores (fundo e texto) do selo de risco. */
    private static final Map<String, Badge> BADGES = Map.of(
            "HIGH", new Badge("Alto", "#fee2e2", "#991b1b"),
            "MEDIUM", new Badge("Médio", "#fef3c7", "#92400e"),
            "LOW", new Badge("Baixo", "#dcfce7", "#166534"));

    private record Badge(String label, String background, String text) {
    }

    private AlertEmailTemplate() {
    }

    static String subject(FraudDetectedEvent event) {
        return SUBJECT_PREFIX + event.riskLevel();
    }

    static String text(FraudDetectedEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("ALERTA DE POSSÍVEL FRAUDE\n\n");
        sb.append("Identificamos uma transação que precisa de atenção.\n\n");
        sb.append("Transação: ").append(event.transactionId()).append('\n');
        sb.append("Nível de risco: ").append(label(event.riskLevel())).append('\n');
        sb.append("Analisada em: ").append(formatInstant(event.analyzedAt())).append("\n\n");
        sb.append("Regras acionadas:\n");
        for (FraudDetectedEvent.TriggeredRule rule : event.triggeredRules()) {
            sb.append("- ").append(rule.description()).append(": ").append(rule.reason()).append('\n');
        }
        sb.append("\nEsta é uma mensagem automática do sistema de detecção de fraudes. Não responda a este e-mail.\n");
        return sb.toString();
    }

    static String html(FraudDetectedEvent event) {
        Badge badge = BADGES.getOrDefault(event.riskLevel(),
                new Badge(event.riskLevel(), "#e5e7eb", "#374151"));

        StringBuilder rules = new StringBuilder();
        for (FraudDetectedEvent.TriggeredRule rule : event.triggeredRules()) {
            rules.append("""
                    <tr>
                      <td style="padding:12px 16px;border-top:1px solid #e5e7eb;">
                        <div style="font-weight:600;color:#111827;">%s</div>
                        <div style="margin-top:4px;color:#6b7280;font-size:14px;">%s</div>
                      </td>
                    </tr>
                    """.formatted(escape(rule.description()), escape(rule.reason())));
        }

        return """
                <!doctype html>
                <html lang="pt-BR">
                <head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background:#f3f4f6;font-family:Segoe UI,Helvetica,Arial,sans-serif;">
                  <span style="display:none;max-height:0;overflow:hidden;">Uma transação foi classificada com risco %s.</span>
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="padding:24px 12px;">
                    <tr><td align="center">
                      <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"
                             style="max-width:560px;background:#ffffff;border-radius:12px;overflow:hidden;
                                    box-shadow:0 1px 3px rgba(0,0,0,0.08);">
                        <tr>
                          <td style="background:#1e3a8a;padding:24px 28px;color:#ffffff;">
                            <div style="font-size:13px;letter-spacing:0.08em;text-transform:uppercase;opacity:0.85;">
                              Detecção de fraudes
                            </div>
                            <div style="font-size:22px;font-weight:700;margin-top:6px;">Alerta de possível fraude</div>
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:24px 28px 8px;color:#374151;font-size:16px;line-height:1.5;">
                            Identificamos uma transação que precisa de atenção.
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:8px 28px;">
                            <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"
                                   style="font-size:15px;color:#374151;">
                              <tr>
                                <td style="padding:8px 0;color:#6b7280;width:150px;">Transação</td>
                                <td style="padding:8px 0;font-family:Consolas,monospace;font-size:13px;">%s</td>
                              </tr>
                              <tr>
                                <td style="padding:8px 0;color:#6b7280;">Nível de risco</td>
                                <td style="padding:8px 0;">
                                  <span style="display:inline-block;padding:4px 12px;border-radius:999px;
                                               background:%s;color:%s;font-weight:600;font-size:13px;">%s</span>
                                </td>
                              </tr>
                              <tr>
                                <td style="padding:8px 0;color:#6b7280;">Analisada em</td>
                                <td style="padding:8px 0;">%s</td>
                              </tr>
                            </table>
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:16px 28px 8px;font-size:14px;font-weight:600;color:#111827;">
                            Regras acionadas
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:0 28px 24px;">
                            <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"
                                   style="border:1px solid #e5e7eb;border-radius:8px;">
                %s                </table>
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:16px 28px;background:#f9fafb;color:#9ca3af;font-size:12px;line-height:1.5;">
                            Esta é uma mensagem automática do sistema de detecção de fraudes. Não responda a este e-mail.
                          </td>
                        </tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(badge.label(), escape(event.transactionId()), badge.background(), badge.text(),
                badge.label(), formatInstant(event.analyzedAt()), rules);
    }

    private static String label(String riskLevel) {
        return BADGES.containsKey(riskLevel) ? BADGES.get(riskLevel).label() : riskLevel;
    }

    private static String formatInstant(Instant instant) {
        return instant == null ? "-" : DATE_TIME.format(instant.atZone(SAO_PAULO));
    }

    /** Escapa os caracteres que mudam o significado do HTML. Os valores vêm do sistema, mas são escapados mesmo assim. */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
