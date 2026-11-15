# Notification Service — Specification

## 1. Responsabilidade

Consumir alertas antifraude e encaminhar notificações aos canais internos ou externos configurados.

## 2. Entrada

Evento `FraudDetected` do tópico `fraud-alerts`.

## 3. Casos de uso

- ProcessFraudAlert
- SendNotification
- RegisterNotificationStatus

## 4. Ports

### Input Ports

- `ProcessFraudAlertUseCase`

### Output Ports

- `NotificationRepository`
- `NotificationProvider`

## 5. Adapters

### Entrada

- Kafka Consumer.

### Saída

- MySQL Repository.
- Notification Provider Adapter.

O provedor externo específico será escolhido posteriormente. O domínio não deve depender diretamente do SDK de um fornecedor.

## 6. Fluxo esperado

1. Consumir alerta.
2. Validar contrato.
3. Verificar idempotência.
4. Definir canal e destinatário conforme configuração.
5. Enviar notificação.
6. Registrar resultado.
7. Confirmar processamento conforme política de entrega.

## 7. Resiliência

- Retry com backoff e limite de tentativas.
- DLQ para falhas persistentes.
- Circuit Breaker poderá proteger chamadas a provedores externos.
- Falha do provedor não deve interromper o Fraud Detection Service.
- Distinguir evento publicado, notificação aceita pelo provedor e entrega efetiva ao destinatário.

## 8. Critérios de aceite

- Consumir alertas válidos.
- Encaminhar notificações ao adaptador configurado.
- Registrar status e falhas.
- Evitar efeitos duplicados quando houver reentrega.
- Tratar indisponibilidade do provedor.
- Possuir testes unitários e de integração.
