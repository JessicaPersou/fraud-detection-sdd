# ADR 0002 — Fluxo de notificação (sem `NotificationRequested` na v1)

**Status:** Aceita  
**Data:** 2026-11-15  
**Resolve:** ADR 0001 §5, item 1

## Contexto

O resumo de decisões citava o evento `NotificationRequested` no módulo `event-contracts`. O fluxo documentado, porém, é `FraudDetected` (tópico `fraud-alerts`) consumido diretamente pelo Notification Service. Não há caso de uso que exija um evento intermediário.

## Decisão

- Na v1, o Notification Service consome `FraudDetected` diretamente.
- `NotificationRequested` **não** é criado. Não há contrato, tópico nem DTO para ele.
- O Notification Service decide canal e destinatário a partir de configuração.

## Justificativa

- Um evento intermediário sem consumidor adicional aumenta acoplamento e custo operacional sem benefício.
- O Fraud Detection Service não precisa conhecer canais de notificação.

## Consequências

- Acoplamento entre `fraud-alerts` e o formato de notificação. Aceitável enquanto há um único consumidor.
- O provedor de notificação fica atrás de uma porta (`NotificationProvider`), o que mantém o domínio independente do fornecedor.

## Evolução

Introduzir um evento de roteamento (ex.: `NotificationRequested`) quando **qualquer** um destes critérios ocorrer:

1. Dois ou mais consumidores precisarem decidir canais de forma diferente.
2. Houver necessidade de retry por canal independente (ex.: e-mail falha, SMS funciona).
3. Notificações precisarem ser geradas por outros domínios além da fraude.
