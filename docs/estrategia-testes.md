# Estratégia de Testes

Este documento explica **como** o sistema é testado, **por que** cada camada existe e **o que ainda não está provado**. Os comandos de execução ficam no README ("Como rodar os testes"). Os cenários individuais (F-01, I-07, O-02 e outros) ficam em `cenarios-teste.md`, e o mapa de cenário para classe de teste está na seção 10 desse documento.

---

## 1. Princípios

1. **Teste no nível mais baixo que prova o comportamento.** Regra de domínio é testada sem Spring e sem Docker. Só o que depende de banco ou Kafka sobe a infraestrutura real.
2. **Infraestrutura real, não simulada, nos testes de integração.** MySQL e Kafka rodam em containers (Testcontainers) com as mesmas versões do `docker-compose`: `mysql:8.4` e `apache/kafka:3.9.1`. Isso evita falsos positivos de H2 ou de mocks de Kafka, principalmente em idempotência, `SKIP LOCKED` e offsets.
3. **Cada garantia da arquitetura tem um teste nomeado.** Atomicidade do outbox, idempotência, inbox, DLQ e retry aparecem como cenários com ID, não como cobertura genérica.
4. **Desempenho é medido separadamente.** Spike e carga não entram no build padrão, porque dependem de hardware documentado e não de um agente de CI.

---

## 2. Pirâmide adotada

| Camada | Quantidade | Onde | Docker | O que prova |
|---|---|---|---|---|
| Unitários | 35 | `DomainModelTest`, `ReceiveTransactionServiceTest`, `AnalyzeTransactionServiceTest`, `FraudRuleEvaluatorTest`, `SmtpNotificationProviderTest`, `ProcessFraudAlertServiceTest` | Não | Regras de domínio, política de idempotência, classificação de risco, reentrega de alerta |
| Integração | 21 | Módulos `*-service` | Sim | Persistência real, atomicidade, publicação e consumo Kafka, retry, DLT, retenção |
| Smoke | 4 | `*SmokeTest` em cada serviço | Sim | Contexto Spring sobe e `/actuator/health` responde `UP` |
| Ponta a ponta | 8 | `e2e-tests/AntifraudEndToEndTest` | Sim | Fluxo completo com os três serviços na mesma JVM |
| Spike (fora do build) | 1 | `FrequencyRuleSpikeTest`, tag `spike` | Sim | Latência p95/p99 da regra de frequência em MySQL (R1) |
| Carga (fora do build) | — | `load-tests/transactions.js` | Depende do ambiente | Throughput até 25.000 TPS (L-06). **Não executado.** A execução foi iniciada em uma máquina pessoal e a máquina travou antes de concluir. Não há resultado a reportar |

Os números são os informados no README e no roadmap. Eles não foram reexecutados ao escrever este documento.

### Por que essa distribuição

- **Muitos unitários**: são rápidos e permitem testar os casos de borda da política (chave em andamento, payload divergente, chave expirada) sem subir nada.
- **Integração no lugar onde o risco está**: a maior parte do risco do sistema está na fronteira com banco e Kafka. Por isso os testes de integração cobrem concorrência (I-07), rollback do outbox (O-01), reentrega com o mesmo `eventId` (O-03) e falha transitória com DLT (C-04).
- **Poucos E2E**: o E2E prova que as peças se encaixam e que o contrato dos eventos funciona. Ele não substitui os testes de camada, por isso cada cenário também tem teste de integração ou unitário quando é possível.

---

## 3. Ferramentas

- **JUnit 5** e **Maven Surefire** para execução. O projeto não usa Failsafe: testes de integração rodam na mesma fase dos unitários.
- **Testcontainers** para MySQL e Kafka, por meio do módulo `test-support`. A classe `SharedInfrastructure` inicia os containers uma vez por JVM, não a cada teste. Os containers usam portas aleatórias, então podem coexistir com o `docker compose` local.
- **Awaitility** para aguardar efeitos assíncronos (consumo Kafka, publicação do outbox) até uma condição ser verdadeira, com tempo limite.
- **Spring Boot Test** nos smoke e nos testes de integração.

**Regra de configuração dos testes.** O `application.yml` de Fraud e Notification ativa o perfil `local` (ADR 0011), e os testes carregam esse arquivo. Por isso, todo teste que depende de um valor de configuração deve fixá-lo explicitamente com `@DynamicPropertySource`. Nos testes atuais: os de fraud fixam os limites de negócio, e os de notificação e o e2e fixam `notification.provider=simulated`. Um teste novo que dependa do provedor ou dos limites e não os fixe pode passar ou falhar conforme o perfil, sem que nada no teste explique o motivo.

---

## 4. Cobertura por garantia

| Garantia | Cenários | Teste principal |
|---|---|---|
| Idempotência da API | I-01 a I-07 | `TransactionApiIntegrationTest`, `ReceiveTransactionServiceTest`; concorrência em `concurrentIdenticalRequestsCreateExactlyOneTransaction` |
| Validação de entrada | V-01 a V-04 | `TransactionApiIntegrationTest` (moeda, corpo inválido, erro sem detalhes internos) |
| Atomicidade do outbox | O-01 | `AtomicityIntegrationTest.outboxFailureRollsBackTransactionAndReleasesKey` |
| Publicação e reentrega | O-02, O-03, O-05 | `OutboxPublishingResilienceIntegrationTest`, `TransactionApiIntegrationTest.publishesTransactionCreatedWithCustomerIdAsKey` |
| Duplicidade no consumo | C-01, C-02 | `AnalyzeTransactionServiceTest` (duplicata e reentrega do alerta) |
| Payload inválido e DLT | C-03 | `FraudPipelineIntegrationTest.invalidPayloadGoesDirectlyToDeadLetterTopic` |
| Retry e offset | C-04, C-05 | `ConsumerRetryIntegrationTest` |
| Notificação e provedor | N-01 a N-04 | `NotificationPipelineIntegrationTest`, `ProcessFraudAlertServiceTest` |
| Fluxo completo | F-01 a F-04 | `AntifraudEndToEndTest` |
| Isolamento de falha do provedor | N-02 | e2e `notificationProviderOutageDoesNotBlockFraudAnalysisOrAlertRecording` |

---

## 5. O que não está provado

Esta seção existe para que a cobertura não seja lida além do que ela é.

- **Desempenho não medido.** L-01 (spike da regra de frequência com hardware documentado), L-02 (capacidade por partição), L-03 (atraso do outbox), L-04 (partição quente), L-05 (SLA de 500 ms) e L-06 (carga até 25.000 TPS) estão pendentes. O spike existe no código, mas não foi executado com hardware documentado.
- **Teste de 25.000 TPS não concluído.** A execução de carga foi iniciada em uma máquina pessoal, que travou durante o teste. Não houve resultado válido, então nenhum número de vazão deve ser citado como medido. A execução precisa ser refeita em um ambiente com hardware documentado.
- **Latência de consumo isolada.** L-07 está parcial: a latência de publicação e o lag são expostos e testados, mas a latência de consumo isolada ainda não.
- **Regra de localização.** BR-03 não está implementada, então o cenário C-06 não se aplica ainda.
- **Valores de negócio.** Os testes usam valores de configuração de teste. Os limites de BR-01 e BR-02 e a política de combinação de sinais (BR-04) aguardam validação, por isso não há teste que prove o valor final.
- **Código de erro para corpo inválido.** A especificação não define o código. O teste verifica `400` sem detalhes internos, mas essa escolha ainda não está fechada (cenarios-teste.md, seção 9).
- **CI remota.** O workflow do GitHub Actions foi removido. A verificação é local, com `mvn verify` e Docker ativo.
- **Segurança e LGPD.** Não há testes de autenticação, autorização nem de minimização de dados, porque esses requisitos ainda não foram implementados (ver `limitacoes-e-evolucoes.md`).

---

## 6. Próximos passos de teste

1. Executar o spike da BR-01 com hardware documentado e registrar p95 e p99 (R1).
2. Executar a carga progressiva com `load-tests/transactions.js` e registrar throughput por instância e lag por partição (L-02, L-06).
3. Medir o atraso do outbox sob carga (L-03).
4. Adicionar a medição de latência de consumo isolada (L-07).
5. Reativar a CI quando houver decisão sobre o GitHub Actions, para que `mvn verify` rode em cada alteração.

Os critérios numéricos desses passos devem ser definidos depois do spike, não antes (cenarios-teste.md, seção 8).
