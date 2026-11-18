# Roadmap de Execução

Este documento organiza a resolução das pendências e a execução das fases do projeto. Ele complementa `implementation-plan.md` com decisões tomadas, critérios de conclusão e o registro de riscos.

**Princípio:** o projeto não resolve todos os problemas de escala antecipadamente. Ele identifica riscos, define como medi-los e documenta quando e por que a solução deve evoluir.

---

## 1. Pendências resolvidas

| Pendência | Decisão | Documento | Evolução prevista |
|---|---|---|---|
| Notificação intermediária (`NotificationRequested`) | Não criar na v1 | ADR 0002 | Quando houver roteamento por canal |
| Publicação do outbox | Polling com `SKIP LOCKED` | ADR 0003 | CDC (Debezium) por gatilho de lag ou carga |
| Idempotência da API | Hash do payload, retenção 24 h, `422` para payload divergente | ADR 0004 | Redis se a tabela virar gargalo |
| Consumidores (grupos, DLQ, retry, inbox) | Parâmetros definidos, 12 partições como hipótese | ADR 0005 | Mais partições por evidência de spike |
| Moeda e precisão | Somente BRL na v1, expoente ISO 4217 | ADR 0006 | Novas moedas mediante validação |
| Stack e ferramentas | Java 21, Spring Boot 4.1.2, Maven, Kafka KRaft, MySQL 8.4 | ADR 0007 | Atualizações em PRs dedicados |
| Arquitetura alvo na AWS | Justificativa das decisões de arquitetura (EKS, RDS for MySQL, Confluent Cloud, IAM, região `sa-east-1`). A execução do projeto é local; sem CI ativa nesta versão | ADR 0008 | Justificativa completa; capacidade medida com hardware documentado |

### Ainda dependem de validação de negócio

Estas pendências **não** são resolvidas por decisão técnica:

- Limite de valor (BR-02).
- Janela e limite de frequência (BR-01).
- Distância e intervalo para localização (BR-03).
- Pesos e combinação de sinais (BR-04).
- Provedor e SLA de notificação externa.
- Requisitos de LGPD e segurança.

Os valores ficam em configuração com placeholders. Nenhum valor é inventado (`implementation-plan.md`, uso de IA).

---

## 2. Fase 1 — Fundação

**Objetivo:** ambiente local reproduzível, build automatizado e serviços de pé com health check.

### Tarefas

- [x] Instalar ferramentas: JDK 21 (Temurin) e Maven 3.9.11 (ambiente local).
- [x] Monorepo Maven multi-módulo com `event-contracts`, `transaction-service`, `fraud-service`, `notification-service`.
- [x] Aplicação Spring Boot mínima em cada serviço (Spring Boot 4.1.2), com Actuator e porta própria.
- [x] Teste de smoke por serviço: contexto sobe e `/actuator/health` responde `UP`.
- [x] Docker Compose com Kafka (KRaft 3.9.1), MySQL 8.4 e criação de tópicos e DLQs com 12 partições.
- [x] Script de inicialização do MySQL com um banco e usuário por serviço.
- [~] CI com GitHub Actions executando `mvn verify`: workflow criado e depois removido nesta versão. A execução remota nunca foi verificada.
- [x] `.gitignore` e README com instruções de execução.

### Critérios de conclusão

1. [x] `mvn verify` passa na raiz: 5 módulos, 3 testes de smoke, `BUILD SUCCESS`.
2. [x] `docker compose up` sobe Kafka e MySQL saudáveis.
3. [x] Os tópicos existem com 12 partições, conforme ADR 0005 (verificado com `kafka-topics --describe`).
4. [x] Os três serviços iniciam como processos e respondem `UP` em `/actuator/health` (Transaction 8081, Fraud 8082 e Notification 8083). Na primeira execução a 8081 estava ocupada por outro stack local; após a remoção desses containers, o Transaction voltou à porta padrão.
5. [ ] A pipeline de CI executa o mesmo build no GitHub. Pendente: o workflow foi removido nesta versão e precisa de decisão para ser reativado.

### Achados da execução

- A porta 8081 estava ocupada por `schema-registry` de outro stack local. A configuração por `SERVER_PORT` resolveu sem alterar o outro projeto. Documentado no README.
- O MySQL não aceita índice parcial. Isso já foi corrigido no ADR 0003.
- Um smoke test estava com campo injetado inútil; foi removido antes do build.

Fora do escopo da Fase 1: entidades, endpoints de negócio, integração com Kafka nos serviços. Isso começa na Fase 2.

---

## 3. Fases 2 a 5 — implementadas

**Status:** implementadas e cobertas por testes automatizados (`mvn verify`: 64 testes no build padrão, 31 unitários, 21 de integração, 8 ponta a ponta e 4 smoke; o spike test da BR-01 fica fora do build). Os critérios de capacidade (spike test, carga até 25.000 TPS e SLA de 500 ms) **não foram validados** com hardware documentado e permanecem pendentes (seção 4).

### 3.1 Fase 2 — Transaction Service

**Entregue**

- [x] `POST /transactions` com `Idempotency-Key`, validações de entrada e de domínio (ADR 0004).
- [x] Idempotência em duas etapas: a chave é reservada como `IN_PROGRESS` em transação própria; a transação, o registro de outbox e a chave como `COMPLETED` são gravados juntos.
- [x] Outbox com publicador por polling, `SKIP LOCKED`, lote de 500 e intervalo de 200 ms (ADR 0003). Produtor com `acks=all` e `enable.idempotence=true`. Chave Kafka `customerId`.
- [x] Erros sem detalhes internos (spec §7).
- [x] Testes: 14 unitários e 10 de integração com MySQL 8.4 e Kafka reais (Testcontainers), incluindo concorrência (I-07), atomicidade (O-01), falhas do Kafka (O-02, O-03) e retenção (R5), mais o smoke test.

**Achados**

- A chave `IN_PROGRESS` precisa ficar comprometida antes da transação de negócio. Caso contrário, uma reentrega concorrente não veria a reserva. Por isso são duas transações.
- Com id atribuído, `merge` do JPA sobrescreveria uma chave existente. As entidades de inserção implementam `Persistable`.
- Se a segunda etapa falha, a reserva é liberada para o cliente repetir. Se o processo cai no meio, a reserva fica `IN_PROGRESS` e expira pelo timeout configurado.

**Pendente**

- [ ] Comportamento com Kafka indisponível (O-02) e reentrega do publicador (O-03): sem teste automatizado.
- [x] Concorrência de requisições idênticas (I-07): teste com 10 requisições simultâneas, uma transação criada.
- [x] Job de retenção: chaves expiradas e outbox publicado há mais de 7 dias (R5, ADR 0009). Pendentes nunca são removidos.

### 3.2 Fase 3 — Fraud Detection Service

**Entregue**

- [x] 3a — BR-02 (valor) com fluxo ponta a ponta.
- [x] 3b — BR-01 (frequência) em MySQL, com janela deslizante que inclui a transação atual (histórico em `transaction_history`, índice por cliente e instante).
- [x] 3c — explicabilidade por regra (`ruleCode`, `description`, `reason`), inbox na mesma transação da análise, retry de 1 s, 2 s e 4 s, DLQ `transaction-events.DLT` e publicação de `FraudDetected` com `alertEventId` gerado uma vez.
- [x] Classificação: nenhum sinal é LOW, um sinal é MEDIUM, dois ou mais é HIGH. Política provisória (`business-rules.md` §7).
- [x] Testes: 11 unitários, 6 de integração com Kafka e MySQL reais (incluindo retry e DLT) e o smoke test.
- [x] Spike test da BR-01 escrito (`FrequencyRuleSpikeTest`, tag `spike`, fora do build padrão).

**Achados**

- Reentrega de evento já analisado não repete a análise, mas reenvia o alerta se a publicação anterior falhou (cenário C-02). Por isso o estado `alert_published` fica na análise.
- O alerta carrega `transactionId`, não o `eventId` de origem. Testes que buscam uma análise devem usar o `transactionId`.
- Latência do alerta é medida em `fraud.alert.latency` (Micrometer), do `occurredAt` até a publicação.

**Pendente**

- [ ] **Spike test da BR-01 com hardware documentado (R1).** O teste foi escrito e não foi executado. A decisão de manter MySQL ou migrar para Redis depende desse resultado.
- [ ] BR-03 (localização) não implementada: o contrato `TransactionCreated` não traz localização. Ausência de dados nunca vira fraude.
- [ ] Limites de BR-01 e BR-02 e a política de combinação de sinais aguardam validação de negócio.

### 3.3 Fase 4 — Notification Service

**Entregue**

- [x] Consumidor de `fraud-alerts` com validação de contrato e DLQ `fraud-alerts.DLT`.
- [x] Provedor simulado atrás da porta `NotificationProvider`, com falha controlável para testes.
- [x] Estados distintos (N-03): `PENDING`, `ACCEPTED_BY_PROVIDER`, `FAILED`. `DELIVERED` está reservado.
- [x] Reentrega de alerta já aceito não gera novo envio (N-04).
- [x] Canal de e-mail por SMTP com Mailpit, ativado por `NOTIFICATION_PROVIDER=smtp` (ADR 0010).
- [x] Testes: 6 unitários (incluindo o adaptador SMTP), 5 de integração e o smoke test.

**Achados**

- `FAILED` aparece entre as tentativas de retry. Testes que aguardam o resultado final precisam esperar o status pedido, não o primeiro `FAILED`.
- O destinatário é gravado para auditoria, mas não vai para log.

**Pendente**

- [ ] Provedor real e SLA de notificação externa (pendência de negócio).
- `DELIVERED` fora do escopo: exige confirmação do provedor. O canal de demonstração é o Mailpit (ADR 0010).
- [x] Circuit breaker (R6): decidido não entrar na v1, com gatilho de revisão no ADR 0009, seção 2.2.

### 3.4 Fase 5 — Integração e validação

**Entregue**

- [x] Módulo `e2e-tests` (8 cenários): os três serviços sobem na mesma JVM, cada um com seu contexto e banco. Cenários cobertos: F-01, F-02, F-03, F-04, I-02, evento duplicado e indisponibilidade do provedor com retry (N-01 e N-04 em nível de fluxo).
- [x] Latência HTTP até notificação aceita é reportada como informativa.
- [x] Observabilidade: spans no console de cada serviço com o mesmo traceId desde a requisição até os consumidores. O traceparent é gravado no outbox para atravessar a publicação assíncrona (ADR 0010).

**Achados**

- Na mesma JVM, os três `application.yml` se sobrepõem no classpath. Cada contexto carrega o próprio arquivo pelo caminho.
- As migrações Flyway ficam em pastas separadas por serviço, para evitar conflito de versão `V1`.
- Os JARs dos serviços são empacotados com o classifier `exec`, para que o módulo e2e enxergue as classes.
- O contexto de trace precisa ser gravado no outbox. O publicador roda em tarefa agendada e abriria um trace novo, perdendo a ligação com a requisição HTTP.

**Pendente**

- [~] Carga progressiva até 25.000 TPS (L-06). Script versionado em `load-tests/transactions.js`; execução com hardware documentado pendente.
- [~] Lag por consumer group exposto e testado (`exposesKafkaConsumerLagMetrics`). Medição sob carga, por partição, pendente (L-07).
- [ ] SLA de 500 ms como máximo absoluto para todas as transações, medido com hardware documentado (L-05). A latência do e2e é só informativa.
- [ ] Atraso do outbox (L-03) não medido.

## 4. Próximas ações

1. **Spike test da BR-01** com hardware documentado (R1). Decide manter MySQL ou evoluir para Redis ou janela pré-agregada.
2. **OIDC do EKS com o Confluent**: somente análise (ADR 0008, seção 2.3).
3. **Orçamento de 500 ms por etapa**: análise, incluindo o intervalo de polling do outbox de 200 ms, que pode consumir até 200 ms (ADR 0008, seção 2.8).
4. **Latência de consumo isolada** (publicação até início do processamento) do consumidor (L-07). Latência de publicação do outbox (`outbox.publish.latency`) já está implementada e exposta; o lag já está exposto e verificado pelo teste `exposesKafkaConsumerLagMetrics`.
5. **Carga progressiva** até 25.000 TPS com hardware documentado (L-06).
6. ~~Jobs de limpeza de idempotência e inbox (R5)~~: feito (ADR 0009, seção 2.1).
7. **Decidir sobre a CI remota** (critério 5 da Fase 1): reativar o workflow do GitHub Actions ou manter a verificação só local.
8. **Validação de negócio** de BR-01, BR-02, BR-03, BR-04 e da política de classificação.

## 5. Registro de riscos (estado atual)

| Risco | Estado |
|---|---|
| R1 | Aberto. Depende do spike test com hardware documentado. |
| R2 | Aberto. 12 partições; o limite de réplicas úteis é o número de partições (ADR 0008, seção 2.7). |
| R3 | Aberto. Intervalo de 200 ms; atraso não medido. |
| R4 | Aberto. Não medido. |
| R5 | Mitigado. Limpeza implementada (ADR 0009). Falta medir o crescimento sob carga. |
| R6 | Aceito na v1 (ADR 0009, seção 2.2). Gatilho de revisão definido. |
| R7 | Parcial. Limites vêm de configuração e a aplicação não sobe sem eles. Validação de configuração na inicialização não é formal. |

## 6. Registro de riscos (definição original)


Cada risco tem uma forma de medir e um gatilho de evolução. O objetivo é mostrar que o risco é conhecido e que a decisão tem critério.

| # | Risco | Como medir | Gatilho de evolução | Ação |
|---|---|---|---|---|
| R1 | Regra de frequência (BR-01) não atinge 500 ms em MySQL | p95/p99 do spike test na Fase 3b | p99 acima do limite | Avaliar Redis ou janela pré-agregada |
| R2 | Capacidade por partição abaixo de 2.100 eventos/s no pico | Throughput por instância em teste de carga | Lag crescendo no pico | Aumentar partições e instâncias (planejar mudança de chave) |
| R3 | Atraso de publicação do outbox | Idade do registro pendente mais antigo | Lag acima do limite definido | CDC (ADR 0003) |
| R4 | Partição quente (cliente com volume alto) | Mensagens e lag por partição | Uma partição muito acima da média | Estratégia alternativa de chave (`kafka-partitioning.md` §5) |
| R5 | Crescimento das tabelas de idempotência e inbox | Número de linhas e tamanho | Crescimento sem limpeza | Jobs de limpeza por expiração |
| R6 | Falha do provedor de notificação | Taxa de erro e DLQ do Notification | Erros acima do limite | Circuit breaker e retry (já previstos) |
| R7 | Consistência de regras entre ambientes | Testes de regra com configuração versionada | Divergência entre ambientes | Configuração centralizada e validada na inicialização |

---

## 7. O que não resolvemos agora

Deliberadamente fora do escopo inicial, com justificativa:

- **Escala além do spike test:** sem evidência de carga, sharding ou múltiplas regiões seriam otimização prematura.
- **CDC e Redis:** só entram com gatilho medido (ADR 0003, ADR 0004).
- **Machine Learning e risk scoring avançado:** fora do escopo da v1 (`project-overview.md`).
- **Bloqueio automático de transações:** fora do escopo da v1.
