# Transaction Service — Specification

## 1. Responsabilidade

Receber transações dos sistemas internos, validar os dados de entrada e publicar eventos para o motor de detecção.

## 2. Entrada

Endpoint: `POST /transactions`.

Header obrigatório: `Idempotency-Key` (ADR 0001, decisão 1). O servidor gera o `transactionId` e o associa à chave de idempotência, para que reenvios com a mesma chave não dupliquem a transação.

Exemplo:

```http
POST /transactions
Idempotency-Key: 7f9c2e1a-5b3d-4c8e-9a10-2d6f4b8e1c57
```

```json
{
  "customerId": "123",
  "amountInCents": 150000,
  "currency": "BRL"
}
```

Regras de idempotência (pendências, ver ADR 0001 §5): comportamento para mesma chave com payload diferente e prazo de retenção ainda não definidos.

## 3. Casos de uso

- ReceiveTransaction
- ValidateTransaction
- RegisterTransaction
- PublishTransactionEvent

## 4. Ports

### Input Ports

- `ReceiveTransactionUseCase`

### Output Ports

- `TransactionRepository`
- `IdempotencyRepository`
- `OutboxRepository`

## 5. Adapters

### Entrada

- REST Controller.

### Saída

- MySQL Repository (transações, idempotência e outbox).
- Outbox publisher para Kafka.

## 6. Fluxo esperado

1. Receber a requisição com `Idempotency-Key`.
2. Validar cabeçalho, campos obrigatórios e formato.
3. Verificar a tabela de idempotência; se a chave já existir, retornar o resultado anterior.
4. Em uma única transação de banco: gravar a transação, a associação com a chave de idempotência e o evento `TransactionCreated` na tabela outbox, com `eventId` gerado uma única vez.
5. Retornar resposta ao sistema chamador.
6. O publicador do outbox envia o evento ao Kafka e marca o registro como publicado. Em falha, tenta novamente reutilizando o mesmo `eventId`.

Consistência: transação e evento são gravados atomicamente (Transactional Outbox, ADR 0001, decisão 5). A publicação no Kafka é assíncrona e pode atrasar, mas não perde eventos.

## 7. Critérios de aceite

- Rejeitar dados inválidos.
- Rejeitar requisições sem `Idempotency-Key`.
- Não duplicar transação em reenvio com a mesma `Idempotency-Key`.
- Persistir transação e evento na mesma transação de banco.
- Publicar evento com contrato válido, reutilizando o mesmo `eventId` em retries.
- Tratar falhas de persistência e publicação.
- Não expor detalhes internos em respostas de erro.
- Possuir testes unitários e de integração.
