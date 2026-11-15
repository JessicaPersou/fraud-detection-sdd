# Implementation Plan

## Fase 0 — Fundamentos

- Registrar decisões arquiteturais em `docs/adr/` (ADR 0001 concluído).
- Resolver as pendências listadas no ADR 0001 §5.

## Fase 1 — Fundação

- Criar monorepo Maven multi-módulo (`transaction-service`, `fraud-service`, `notification-service`, `event-contracts`, `infrastructure`, `docs`).
- Configurar Java 21 e Spring Boot.
- Preparar Docker Compose para Kafka e MySQL.
- Definir convenções de código e testes.
- Criar health checks e configuração de observabilidade básica.

## Fase 2 — Transaction Service

- Implementar endpoint `POST /transactions` com `Idempotency-Key`.
- Implementar validações.
- Implementar persistência de transação, idempotência e outbox na mesma transação de banco.
- Implementar publicador do outbox para `TransactionCreated`, reutilizando `eventId`.
- Criar testes unitários e de integração.

## Fase 3 — Fraud Detection Service

- 3a: Kafka Consumer, casos de uso e regra BR-02 (valor). Validar o fluxo ponta a ponta.
- 3b: Regra BR-01 (frequência) em MySQL e **spike test de carga** para validar latência antes da Fase 5.
- 3c: Classificação de risco, publicação de `FraudDetected` com explicabilidade, inbox, retry, DLQ e observabilidade.
- Criar testes em cada subfase.

## Fase 4 — Notification Service

- Implementar Kafka Consumer.
- Implementar adaptador de notificação, inicialmente com provedor simulado (*fake provider*).
- Registrar status.
- Implementar retry e DLQ.
- Criar testes.

## Fase 5 — Integração e validação

- Validar fluxo completo.
- Testar eventos duplicados.
- Testar indisponibilidade de serviços e provedores.
- Medir throughput e latência.
- Executar testes de carga em níveis progressivos até o pico previsto.
- Avaliar consumer lag e distribuição entre partições.

## Critérios de conclusão

O fluxo deverá receber uma transação, analisar suas características, identificar condições suspeitas, persistir o resultado e disponibilizar o alerta ao serviço de notificações.

A meta de 25.000 TPS e latência de 500 ms só será considerada validada após testes em ambiente representativo, com recursos e configuração documentados.

## Uso de IA no desenvolvimento

A IA será utilizada para apoiar geração de código, testes e revisão, sempre com base nos documentos aprovados.

Para cada etapa:

1. Fornecer a especificação do serviço.
2. Solicitar proposta de implementação.
3. Revisar dependências e decisões.
4. Gerar testes.
5. Executar testes e revisar resultados.
6. Atualizar a documentação quando houver mudança aprovada.

A IA não deve inventar regras de negócio ou resolver pendências sem validação humana.
