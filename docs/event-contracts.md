# Event Contracts

## 1. Convenções

- Eventos possuem `eventId` único, gerado uma única vez e persistido no outbox. Todas as tentativas de publicação reutilizam o mesmo `eventId` (ADR 0001, decisão 2).
- Eventos possuem `eventType` e `eventVersion`.
- Timestamps devem usar ISO 8601 com timezone, preferencialmente UTC.
- Valores monetários são representados em menor unidade monetária (`amountInCents`, inteiro), com moeda explícita (ADR 0001, decisão 3).
- Consumidores devem tolerar duplicidade.
- Contratos devem evoluir de forma compatível sempre que possível.
- A chave Kafka inicial será `customerId`, sujeita à validação de distribuição e ordenação.

## 2. TransactionCreated

**Topic:** `transaction-events`  
**Producer:** Transaction Service  
**Consumer:** Fraud Detection Service

```json
{
  "eventId": "uuid",
  "eventType": "TRANSACTION_CREATED",
  "eventVersion": 1,
  "transactionId": "456",
  "customerId": "123",
  "amountInCents": 150000,
  "currency": "BRL",
  "occurredAt": "2026-11-15T13:00:00Z"
}
```

`transactionId` é gerado pelo Transaction Service (ADR 0001, decisão 1).

## 3. FraudDetected

**Topic:** `fraud-alerts`  
**Producer:** Fraud Detection Service  
**Consumer:** Notification Service

```json
{
  "eventId": "uuid",
  "eventType": "FRAUD_DETECTED",
  "eventVersion": 1,
  "transactionId": "456",
  "customerId": "123",
  "riskLevel": "HIGH",
  "triggeredRules": [
    {
      "ruleCode": "TRANSACTION_FREQUENCY",
      "description": "Volume de transações do cliente acima do limite na janela configurada",
      "reason": "6 transações em 60 segundos; limite configurado: 5"
    },
    {
      "ruleCode": "HIGH_TRANSACTION_AMOUNT",
      "description": "Valor acima do limite configurado",
      "reason": "Valor de 150000 centavos acima do limite configurado"
    }
  ],
  "analyzedAt": "2026-11-15T13:00:01Z"
}
```

Cada regra acionada explica o motivo do acionamento (`business-rules.md` §5; ADR 0001, decisão 7). Os valores de exemplo não são limites de negócio.

## 4. Chaves e idempotência

- Usar `customerId` como chave inicial para eventos de transação, buscando preservar ordem por cliente dentro de uma partição.
- Consumidores deduplicam por `eventId` e registram o processamento em tabela inbox (ADR 0001, decisão 6).
- A retenção da tabela inbox deve cobrir o período de replay do Kafka (pendência, ver ADR 0001 §5).
- Aumentar partições pode alterar o mapeamento de chaves; avaliar impacto na ordenação.

## 5. Evolução dos contratos

Mudanças devem considerar compatibilidade dos consumidores. Campos novos opcionais são preferíveis a alterações incompatíveis. Mudanças breaking devem ser versionadas e coordenadas.

## 6. Semântica implementada (Fase 2 e 3)

- **`occurredAt` em `TransactionCreated`:** é o instante em que o Transaction Service recebeu e registrou a transação. É a origem da medição do SLA de 500 ms (ADR 0008, seção 2.8).
- **`FraudDetected` é publicado somente para MEDIUM e HIGH.** Transações LOW são analisadas e persistidas, sem alerta.
- **`alertEventId`:** o `eventId` do `FraudDetected` é gerado uma vez, na análise, e persistido. Reenvios do alerta usam o mesmo `eventId`.
- **Chave Kafka:** `customerId` nos dois tópicos.
- **`riskLevel`:** string `LOW`, `MEDIUM` ou `HIGH`, conforme `business-rules.md` §7.
- **Deduplicação:** o consumidor de `transaction-events` deduplica por `eventId` na inbox. O consumidor de `fraud-alerts` deduplica pelo `eventId` na tabela de notificações.
