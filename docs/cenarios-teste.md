# Cenários de Teste — Caminho Feliz e Bordas

Este documento reúne os cenários de teste do sistema antifraude. Cada cenário é derivado dos documentos aprovados (ADRs, especificações e regras de negócio), e a fonte está indicada na coluna **Fonte**.

**Escopo:** cenários que devem ser verdadeiros ao final das Fases 2 a 5 (`implementation-plan.md`). Cenários que dependem de valores de negócio ainda não validados estão marcados com **Bloqueio**. Nesses casos, o teste usa um valor placeholder definido na configuração de teste, sem assumir o valor final.

---

## 1. Ambiente para execução

```bash
docker compose -f infrastructure/docker/docker-compose.yml up -d --wait
java -jar transaction-service/target/transaction-service-0.1.0-SNAPSHOT.jar
java -jar fraud-service/target/fraud-service-0.1.0-SNAPSHOT.jar
java -jar notification-service/target/notification-service-0.1.0-SNAPSHOT.jar
```

Portas: Transaction 8081, Fraud 8082, Notification 8083 (`SERVER_PORT` para alterar).

Fraud e Notification sobem sem variáveis de ambiente, porque o perfil `local` está ativo por padrão (ADR 0011). Em outro ambiente, use `SPRING_PROFILES_ACTIVE=prod` e defina as variáveis obrigatórias.

---

## 2. Caminho feliz (ponta a ponta)

Fase de referência: **Fase 5** (integração). Parte do fluxo pode ser validada antes, nas Fases 2 e 3a.

| ID | Cenário | Ação | Resultado esperado | Fonte |
|---|---|---|---|---|
| F-01 | Transação válida sem sinal de risco | `POST /transactions` com `Idempotency-Key` nova e valor abaixo do limite | `201` com `transactionId`. Transação, chave de idempotência e outbox gravados na mesma transação de banco. Evento `TRANSACTION_CREATED` publicado em `transaction-events`, com chave `customerId`. Análise com `riskLevel = LOW` persistida. **Nenhum** `FRAUD_DETECTED` publicado. | ADR 0001 (dec. 1, 2, 5); ADR 0003; `architecture.md` §1; `specs/transaction-service` §6; `specs/fraud-detection-service` §7 |
| F-02 | Transação com regra de valor acionada | `POST /transactions` com valor acima do limite configurado | Análise com `riskLevel = MEDIUM`. `FRAUD_DETECTED` publicado em `fraud-alerts`, com `triggeredRules` contendo `ruleCode`, `description` e `reason`. Notificação registrada como enviada pelo provedor *fake*. | `business-rules.md` BR-02; ADR 0001 (dec. 7); `event-contracts.md` §3 |
| F-03 | Transação com múltiplas regras | Mais de 5 transações do mesmo cliente em 60 s, com um valor acima do limite | Análise com `riskLevel = HIGH` (conforme política provisória). `triggeredRules` com `TRANSACTION_FREQUENCY` e `HIGH_TRANSACTION_AMOUNT`. | `business-rules.md` BR-01, BR-04; `event-contracts.md` §3 |
| F-04 | Alerta entregue ao Notification | Consumir `FRAUD_DETECTED` | Notificação enviada pelo adaptador configurado. Status registrado. Offset confirmado somente após persistência. | `specs/notification-service` §6; ADR 0001 (dec. 8) |

**Bloqueio:** F-02 e F-03 dependem do limite de valor (BR-02) e da janela e limite de frequência (BR-01). Os testes usam valores de configuração de teste até validação de negócio.

---

## 3. Idempotência da API (`POST /transactions`)

Referência: ADR 0004.

| ID | Cenário | Ação | Resultado esperado | Fonte |
|---|---|---|---|---|
| I-01 | Chave nova | Requisição com `Idempotency-Key` inédita | `201`. Uma transação criada. | ADR 0004 |
| I-02 | Reenvio concluído | Mesma chave e mesmo corpo, após `COMPLETED` | `200` com a resposta guardada. Nenhuma transação nem evento novos. | ADR 0004 |
| I-03 | Reenvio em andamento | Mesma chave e mesmo corpo, enquanto o estado é `IN_PROGRESS` | `409 Conflict`, com indicação de tentar novamente. | ADR 0004 |
| I-04 | Payload divergente | Mesma chave e corpo diferente | `422 Unprocessable Content`. Nenhuma alteração no banco. | ADR 0004 |
| I-05 | Chave ausente | Requisição sem o header | `400 Bad Request`. | ADR 0004; `specs/transaction-service` §2 |
| I-06 | Chave expirada | Mesma chave após 24 h | Tratada como chave nova (`201`). | ADR 0004 (retenção) |
| I-07 | Corrida entre requisições idênticas | Duas requisições simultâneas com a mesma chave | Apenas uma transação é criada. A segunda recebe `409` ou replay. A restrição de unicidade no banco é a barreira final. | ADR 0004 (concorrência) |

**Não especificado:** o código de erro para dados inválidos no corpo (ex.: campo obrigatório ausente) não está definido nos documentos. Definir antes da Fase 2.

---

## 4. Validação de entrada

| ID | Cenário | Resultado esperado | Fonte |
|---|---|---|---|
| V-01 | Moeda diferente de BRL | `422`. | ADR 0006 |
| V-02 | Valor não inteiro ou não positivo em `amountInCents` | Rejeitado como dado inválido. | ADR 0006; `event-contracts.md` §1 |
| V-03 | Campo obrigatório ausente (`customerId`, `amountInCents` ou `currency`) | Rejeitado como dado inválido. Código a definir (ver I-05 e seção 3). | `specs/transaction-service` §6 |
| V-04 | Mensagem de erro não expõe detalhes internos | Resposta sem stack trace, nomes de tabela ou SQL. | `specs/transaction-service` §7 |

---

## 5. Outbox e publicação (Transaction Service)

Referência: ADR 0003.

| ID | Cenário | Resultado esperado | Fonte |
|---|---|---|---|
| O-01 | Atomicidade | Falha ao gravar o outbox provoca rollback da transação. Não existe transação sem evento nem evento sem transação. | ADR 0001 (dec. 5) |
| O-02 | Kafka indisponível | Registro permanece com `published_at` nulo. Publicação é retentada com o mesmo `eventId`. | ADR 0003; `specs/transaction-service` §6 |
| O-03 | Reentrega do publicador | `eventId` idêntico em todas as tentativas. | ADR 0001 (dec. 2) |
| O-04 | Múltiplas instâncias do publicador | Nenhum evento publicado duas vezes ao mesmo tempo (`SKIP LOCKED`). | ADR 0003 |
| O-05 | Chave de partição | Eventos do mesmo `customerId` vão para a mesma partição. | ADR 0003; `kafka-partitioning.md` §2 |

---

## 6. Consumo e duplicidade (Fraud Detection)

Referência: `specs/fraud-detection-service` §7; ADR 0005.

| ID | Cenário | Resultado esperado | Fonte |
|---|---|---|---|
| C-01 | Evento duplicado (mesmo `eventId`) | Inbox detecta a duplicidade. Análise não é refeita. Nenhum `FRAUD_DETECTED` duplicado. | ADR 0005 §4; `business-rules.md` §5 |
| C-02 | Falha após persistência e antes do envio do alerta | Alerta é retentado com o mesmo `eventId`. Nenhuma nova análise. | `specs/fraud-detection-service` §7 (passo 8) |
| C-03 | Payload inválido | Vai direto para `transaction-events.DLT`, sem retry. | ADR 0005 §3 |
| C-04 | Falha transitória de banco | 3 tentativas com intervalos de 1 s, 2 s e 4 s. Depois, DLQ com cabeçalhos de origem e motivo. | ADR 0005 §3 |
| C-05 | Offset | Offset confirmado somente após persistência e publicação. Falha no meio não perde análise. | ADR 0001 (dec. 8) |
| C-06 | Evento de transação sem dados de localização | Não gera alerta de localização. Ausência de dados nunca é tratada como fraude. | `business-rules.md` BR-03 e §5 |

---

## 7. Notificação e resiliência

Referência: `specs/notification-service`; ADR 0002.

| ID | Cenário | Resultado esperado | Fonte |
|---|---|---|---|
| N-01 | Provedor indisponível | Retry com backoff e limite. Após o limite, DLQ (`fraud-alerts.DLT`). | `specs/notification-service` §7 |
| N-02 | Falha do provedor não afeta o Fraud | Fraud Detection continua processando e publicando alertas. | `specs/notification-service` §7 |
| N-03 | Estados distintos | Registro diferencia evento publicado, notificação aceita pelo provedor e entrega confirmada. | `specs/notification-service` §7 |
| N-04 | Reentrega do alerta | Nenhum efeito duplicado (notificação não enviada duas vezes). | `specs/notification-service` §8 |

---

## 8. Carga e latência (Fase 3b e Fase 5)

| ID | Cenário | Critério | Fonte |
|---|---|---|---|
| L-01 | Spike test da regra de frequência (Fase 3b) | p95 e p99 medidos com hardware documentado. Se p99 ultrapassar o limite, avaliar Redis ou janela pré-agregada. | Risco R1; ADR 0001 (dec. 4) |
| L-02 | Capacidade por partição | Throughput por instância medido. Lag não cresce no pico. | Risco R2; `kafka-partitioning.md` §4 |
| L-03 | Atraso do outbox | Idade do registro pendente mais antigo dentro do limite definido. | Risco R3; ADR 0003 |
| L-04 | Partição quente | Mensagens e lag por partição sem desvio relevante. | Risco R4; `kafka-partitioning.md` §5 |
| L-05 | Meta de latência | 500 ms, em máximo absoluto, do recebimento da transação pelo Transaction Service até o término: publicação do alerta em `fraud-alerts` (MEDIUM ou HIGH) ou gravação da análise (LOW). Vale para todas as transações. | `project-overview.md` §3; ADR 0008 §4 |
| L-07 | Monitoramento de lag e latência | Consumer lag por partição, latência de publicação e de consumo, e latência ponta a ponta disponíveis e com alertas configurados antes da Fase 5. | ADR 0008 §2.8; `architecture.md` §8 |
| L-06 | Carga até o pico | Progressão até 25.000 TPS, com recursos de hardware documentados. Script em `load-tests/transactions.js`. | `implementation-plan.md` (critérios de conclusão) |

Os critérios numéricos de L-01 a L-06 são definidos após o spike test. Não usar valores antecipados.

---

## 9. Pendências para fechar os cenários

1. Código de erro para dados inválidos no `POST /transactions` (seção 3 e V-03).
2. Valores de negócio: limite de valor (BR-02), janela e limite de frequência (BR-01), distância e tempo (BR-03), pesos (BR-04).
3. Provedor de notificação para o teste de integração real (a Fase 4 usa *fake*).
4. Critérios numéricos de carga (seção 8), definidos após o spike test.

---

## 10. Cobertura automatizada atual

Esta seção registra o que já tem teste automatizado. Os itens sem teste estão marcados como pendentes.

| ID | Situação | Onde está coberto |
|---|---|---|
| F-01 | Coberto | e2e `lowValueTransactionIsAnalysedAsLowAndNotAlerted` |
| F-02 | Coberto | e2e `highValueTransactionProducesAlertAcceptedByProvider` |
| F-03 | Coberto | e2e `frequencyPlusAmountEscalatesToHighRisk` |
| F-04 | Coberto | e2e, verificação de `ACCEPTED_BY_PROVIDER` |
| I-01 | Coberto | `TransactionApiIntegrationTest` |
| I-02 | Coberto | `TransactionApiIntegrationTest`, `ReceiveTransactionServiceTest`, e2e |
| I-03 | Coberto (unitário) | `ReceiveTransactionServiceTest.inProgressKeyWithinTimeoutIsRejectedAsConflict` |
| I-04 | Coberto | `TransactionApiIntegrationTest.sameKeyWithDifferentPayloadReturns422` |
| I-05 | Coberto | `TransactionApiIntegrationTest.missingIdempotencyKeyReturns400` |
| I-06 | Coberto (unitário) | `ReceiveTransactionServiceTest.expiredCompletedKeyIsTreatedAsNewRequest` |
| I-07 | Coberto | `TransactionApiIntegrationTest.concurrentIdenticalRequestsCreateExactlyOneTransaction` |
| V-01 | Coberto | `TransactionApiIntegrationTest.unsupportedCurrencyReturns422` |
| V-02 | Coberto | `TransactionApiIntegrationTest.invalidBodyReturns400WithoutInternalDetails` |
| V-03 | Coberto | `TransactionApiIntegrationTest.invalidBodyReturns400WithoutInternalDetails` |
| V-04 | Coberto | `TransactionApiIntegrationTest.invalidBodyReturns400WithoutInternalDetails` |
| O-01 | Coberto | `AtomicityIntegrationTest.outboxFailureRollsBackTransactionAndReleasesKey` |
| O-02 | Coberto | `OutboxPublishingResilienceIntegrationTest.pendingEventIsRetriedWithSameEventIdAndMetricsAreRecorded` |
| O-03 | Coberto | Mesmo teste acima: três envios com o mesmo `eventId` |
| O-05 | Coberto | `TransactionApiIntegrationTest.publishesTransactionCreatedWithCustomerIdAsKey` |
| C-01 | Coberto | `AnalyzeTransactionServiceTest.duplicateEventDoesNotRepeatAnalysisOrAlert`, e2e |
| C-02 | Coberto | `AnalyzeTransactionServiceTest.alertPublishFailureIsRetriedWithSameAlertEventIdWithoutNewAnalysis` |
| C-03 | Coberto | `FraudPipelineIntegrationTest.invalidPayloadGoesDirectlyToDeadLetterTopic` |
| C-04 | Coberto | `ConsumerRetryIntegrationTest` (falha transitória recuperada e falha persistente em DLT) |
| C-05 | Coberto | `ConsumerRetryIntegrationTest.transientDatabaseFailureIsRetriedAndOffsetIsCommittedAfterAnalysis` |
| C-06 | Não aplicável | BR-03 não implementada |
| N-01 | Coberto | `NotificationPipelineIntegrationTest.persistentProviderFailureEndsInFailedStatusAndDeadLetterTopic` |
| N-02 | Coberto | e2e `notificationProviderOutageDoesNotBlockFraudAnalysisOrAlertRecording` |
| N-03 | Coberto | `ProcessFraudAlertServiceTest` e `NotificationPipelineIntegrationTest` |
| N-04 | Coberto | `ProcessFraudAlertServiceTest.redeliveryOfAcceptedAlertDoesNotSendAgain`, `NotificationPipelineIntegrationTest.redeliveredAlertIsNotSentTwice` |
| L-01 | Pendente | Spike test escrito (`FrequencyRuleSpikeTest`), não executado com hardware documentado |
| L-02 | Pendente | Carga por partição não medida |
| L-03 | Pendente | Atraso do outbox não medido |
| L-04 | Pendente | Partição quente não medida |
| L-05 | Pendente | Só há latência informativa no e2e |
| L-06 | Pendente | Carga até 25.000 TPS não concluída. A execução foi iniciada em uma máquina pessoal e a máquina travou, sem resultado válido |
| L-07 | Parcial | Lag exposto e testado (`FraudServiceSmokeTest.exposesKafkaConsumerLagMetrics`); latência de publicação implementada (`outbox.publish.latency`); latência de consumo isolada pendente |
