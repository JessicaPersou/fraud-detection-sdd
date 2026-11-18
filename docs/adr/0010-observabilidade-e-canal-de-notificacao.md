# ADR 0010 — Observabilidade com trace no console e canal de e-mail com Mailpit

**Status:** Aceita  
**Data:** 2026-11-16  
**Relaciona-se com:** ADR 0003 (outbox), ADR 0002 (notificação), ADR 0009 (métricas e operação)

## 1. Contexto

O sistema precisa mostrar o caminho de uma transação do início ao fim: a requisição HTTP, a gravação no outbox, a publicação no Kafka, a análise no Fraud Detection e a notificação. Também precisa enviar um alerta por e-mail real para demonstração, sem entregar mensagens a terceiros.

## 2. Decisões

### 2.1 Tracing com Micrometer Tracing e exportação para o console

- **O que:** Micrometer Tracing (via `spring-boot-starter-opentelemetry`) com exportação dos spans para o log do próprio serviço, pelo `LoggingSpanExporter` do OpenTelemetry. Amostragem em 100%.
- **Por que Micrometer:** o projeto já usa Micrometer para métricas (`fraud.alert.latency`, lag do Kafka). Usar a mesma fachada evita uma segunda biblioteca de observabilidade.
- **Por que console:** o objetivo é demonstrar o fluxo sem depender de infraestrutura externa. Um coletor como Zipkin ou Jaeger exigiria mais um serviço no Compose e não muda a decisão arquitetural.
- **Alternativas descartadas:** Zipkin no Compose (mais uma peça a manter para a demo); OpenTelemetry Collector (útil com OTLP, mas exige configuração extra); apenas `traceId` nos logs (não mostra os spans).
- **Trade-off:** amostragem em 100% e exportação para o log não servem para produção. Em um ambiente real, a amostragem seria reduzida e o exportador trocado por OTLP, sem mudar o código de negócio.

### 2.2 Trace atravessando o outbox

- **Problema:** o publicador do outbox roda em uma tarefa agendada. Quando a mensagem é publicada, o trace da requisição HTTP já terminou, e o consumidor abriria um trace novo.
- **Decisão:** na gravação do evento, o contexto de trace atual (W3C `traceparent`) é salvo na coluna `trace_parent` da tabela `outbox_event` (migração `V2`). Na publicação, o envio acontece dentro de um span filho desse contexto.
- **Resultado:** o mesmo `traceId` aparece na requisição HTTP, no publicador, no Fraud Detection e no Notification.
- **Por que isso importa:** o outbox desacopla a escrita da publicação. Sem essa decisão, a observabilidade quebraria exatamente na fronteira que o outbox criou.

### 2.3 Canal de e-mail com Mailpit

- **O que:** o Notification envia e-mail por SMTP. Em demonstração, o servidor é o Mailpit, que roda no docker-compose e captura as mensagens sem entregá-las. A interface web fica em `http://localhost:8025`.
- **Por que Mailpit:** não entrega nada a terceiros, não exige conta nem remetente verificado, e mostra o e-mail completo na interface. Isso permite demonstrar o alerta de ponta a ponta sem risco.
- **Como ativar:** `NOTIFICATION_PROVIDER=smtp`. O padrão é `simulated`, usado pelos testes.
- **Alternativas descartadas:** SendGrid (entrega real, mas exige conta, chave de API, remetente verificado e internet na apresentação; fica como evolução); MailHog (projeto menos mantido que o Mailpit).
- **Arquitetura:** o adaptador `SmtpNotificationProvider` implementa a porta `NotificationProvider`. O domínio não muda, e trocar o servidor é uma questão de configuração.
- **Dados pessoais:** o destinatário vem de variável de ambiente e não é gravado em log. O erro propagado não mostra o servidor SMTP.

## 3. Consequências

- Os logs ficam mais verbosos, porque cada span gera uma linha. Aceito para a demonstração.
- O e-mail só é enviado quando o provedor é `smtp`. Os testes automatizados continuam usando o provedor simulado, sem dependência de servidor.
- SendGrid, OTLP e Zipkin ficam como evolução documentada, sem implementação.
