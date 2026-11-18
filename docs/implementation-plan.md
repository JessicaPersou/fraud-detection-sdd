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
- Canal de demonstração por SMTP com Mailpit, ativado por configuração (ADR 0010).
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

A meta de 25.000 TPS e a latência de 500 ms só são afirmadas após testes de carga com recursos de hardware e configuração documentados (ver ADR 0008, seção 5).

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

## Status final das fases

| Fase | Estado | Evidência |
|---|---|---|
| 1 — Fundação | Concluída | Monorepo, Compose, workflow de CI criado (execução remota não verificada e removido nesta versão) |
| 2 — Transaction Service | Concluída | 27 testes; idempotência, outbox, atomicidade, Kafka indisponível |
| 3 — Fraud Detection | Concluída | BR-02, BR-01, inbox, retry, DLQ, offset; spike escrito e fora do build padrão |
| 4 — Notification | Concluída | Provedor simulado e Mailpit (ADR 0010); estados e reentrega |
| 5 — Integração | Concluída | e2e com 8 cenários; carga como estratégia documentada (roteiro, seção 14) |

**Escopo de demonstração.** Os limites de regra são valores de demonstração, aprovados para o case. A meta de capacidade (25.000 TPS) e o SLA de 500 ms como máximo absoluto para todas as transações são definidos e justificados (ADR 0008), com medição local como referência.

**Fora do escopo do case:** BR-03 (localização), provedor de notificação real, canal de alerta operacional, `DELIVERED`, circuit breaker, CDC e Redis (gatilhos documentados no ADR 0009 e no ADR 0003).
