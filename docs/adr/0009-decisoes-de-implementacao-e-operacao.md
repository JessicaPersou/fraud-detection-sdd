# ADR 0009 — Decisões de implementação e operação da v1

**Status:** Aceita  
**Data:** 2026-11-16  
**Relaciona-se com:** ADR 0001 a 0008, `docs/roadmap-execucao.md` (riscos R5 e R6), `docs/cenarios-teste.md`

## 1. Contexto

As Fases 2 a 5 foram implementadas. Algumas pendências técnicas ficaram sem decisão: prazo de retenção do outbox publicado (ADR 0003 não definiu N dias), uso de circuit breaker (ADR 0002 e 0005 o deixaram como opcional), tipo de cluster Confluent para homologação (ADR 0008, pendência 3) e algumas decisões de código que afetam a operação. Este ADR registra essas decisões. Limites de negócio (BR-01, BR-02, BR-03 e BR-04) **não** estão aqui: continuam pendentes de validação da área de negócio.

## 2. Decisões

### 2.1 Retenção de dados (R5)

| Dado | Retenção | Origem | Limpeza |
|---|---|---|---|
| Chave de idempotência | 24 h | ADR 0004 | Job horário (`transaction.retention`) |
| Outbox publicado | 7 dias após `published_at` | Decisão deste ADR | Job horário |
| Outbox pendente | Sem remoção | ADR 0003 | — |
| Inbox do Fraud | 8 dias | ADR 0005, seção 4 | Job horário (`fraud.retention`) |

- O outbox publicado mantém 7 dias para diagnóstico e reprocessamento manual. O valor é configurável por `OUTBOX_PUBLISHED_RETENTION`.
- A inbox de 8 dias fica acima dos 7 dias de retenção do tópico, como o ADR 0005 exige para o replay.
- Registros pendentes nunca são removidos, mesmo antigos. Um pendente antigo é sinal de falha do publicador, não lixo.

**Revisar se:** o volume de outbox publicado ocupar espaço relevante no RDS. Nesse caso, avaliar arquivamento antes de reduzir a retenção.

### 2.2 Circuit breaker (R6)

**Não implementado na v1.** O retry com backoff de 1 s, 2 s e 4 s, somado à DLQ, cobre a indisponibilidade do provedor. O consumidor de alertas é isolado do Fraud Detection: a falha do provedor não bloqueia a análise nem o registro do alerta (teste `notificationProviderOutageDoesNotBlockFraudAnalysisOrAlertRecording`).

**Gatilho para revisar:** taxa de `FAILED` acima do limite que a equipe definir, ou tentativas de retry atrasando o consumo a ponto de estourar o lag aceitável. Nesse caso, implementar circuit breaker na porta `NotificationProvider`, sem alterar o domínio.

### 2.3 Execução e verificação

O projeto é executado localmente com Docker Compose (Kafka em KRaft e MySQL 8.4, um broker, fator de replicação 1) e verificado no GitHub pela CI, que roda `mvn verify` com Testcontainers. Assim a verificação usa MySQL e Kafka reais sem depender de infraestrutura externa.

A arquitetura alvo do ADR 0008 justifica o desenho; ela não é o ambiente de execução do projeto.

**Limite:** a execução local não mede a capacidade de um cluster gerenciado, nem a latência de rede da arquitetura alvo. Os números de carga são de referência e dependem do hardware.

### 2.4 Idempotência em duas etapas (Transaction Service)

A chave é reservada como `IN_PROGRESS` em uma transação própria, e confirmada como `COMPLETED` junto com a transação e o registro do outbox. Motivo: uma reentrega concorrente precisa ver a reserva, e a barreira de chave única do banco fica explícita. Se a segunda etapa falha, a reserva é liberada. Se o processo cai no meio, a reserva expira pelo timeout `IDEMPOTENCY_IN_PROGRESS_TIMEOUT` (padrão PT5M).

### 2.5 Inserções com `Persistable`

Entidades com id atribuído (chave de idempotência, inbox, histórico de transações, notificações) implementam `Persistable` para que `saveAndFlush` use `persist`. O `merge` padrão do JPA sobrescreveria um registro existente sem aviso, o que quebraria a idempotência silenciosamente.

### 2.6 Migrações e configuração por serviço

- Migrações Flyway em pastas separadas por serviço (`db/migration/<serviço>`), para que serviços que rodam na mesma JVM (módulo `e2e-tests`) não tenham conflito de versão.
- Os JARs executáveis dos serviços têm o classifier `exec`, para que o JAR comum fique disponível como dependência.
- No módulo `e2e-tests`, cada contexto carrega o `application.yml` do próprio serviço pelo caminho, porque o classpath compartilhado faria o Spring carregar só um deles.

### 2.7 Valores de teste

Os testes usam limites explícitos e marcados como de teste (`threshold-cents=100000`, `max-transactions=5`, `window=PT60S`). Esses valores não são de negócio e não aparecem no código de produção. O spike test é marcado com a tag `spike` e excluído do build padrão.

### 2.8 Observabilidade de lag e latência

- Consumer lag: métricas do cliente Kafka expostas pelo Micrometer no Actuator (`kafka.consumer.fetch.manager.records.lag`). Verificado pelo teste `exposesKafkaConsumerLagMetrics`.
- Latência ponta a ponta de todas as transações: `fraud.alert.latency` para as que geram alerta (término: publicação do alerta) e `fraud.analysis.latency` para as LOW (término: gravação da análise). O SLA vale para as duas.
- Outbox (R3): `outbox.publish.latency`, o tempo de envio de cada evento até o ack do Kafka, e `outbox.pending.oldest.age.seconds`, a idade do pendente mais antigo. A segunda é o sinal de atraso do publicador. Foi implementada porque o atraso de publicação é um risco registrado (R3), e sem ela o atraso só apareceria olhando a tabela.
- Limites de alerta operacional e destinatários continuam pendentes de definição pela equipe.

### 2.9 Dados pessoais

- O destinatário da notificação é gravado para auditoria, mas não vai para log.
- Respostas de erro são genéricas, sem stack trace, SQL ou nomes de tabela (teste `invalidBodyReturns400WithoutInternalDetails`).

## 3. Consequências

- Não há circuit breaker na v1. Se o provedor real ficar instável, o trabalho de retry pode acumular. O gatilho da seção 2.2 define quando isso vira prioridade.
- A verificação local não substitui medição de capacidade em cluster gerenciado. Essa medição fica como pendência de análise (ADR 0008, seção 8).
- A retenção do outbox publicado passa a ser operação: sem o job, a tabela cresce sem limite.

## 4. Pendências que este ADR não resolve

- Limites de BR-01, BR-02 e política de combinação de sinais (BR-04): validação de negócio.
- BR-03 (localização): depende de dados que o contrato ainda não tem.
- Provedor real de notificação e seu SLA.
- Limites e destinatários de alerta operacional.
