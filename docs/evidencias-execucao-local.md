# Evidências de Execução Local

Este documento registra o que foi executado de fato na máquina local: a subida da stack, os exemplos `curl` do README, as verificações nos bancos e no Mailpit e a alteração do e-mail de alerta.

Ele não substitui os testes automatizados (`mvn verify`) nem as medições de capacidade, que continuam pendentes (`roadmap-execucao.md`, seção 3.4).

---

## 1. Ambiente

| Item | Versão ou valor |
|---|---|
| Sistema | Windows 11 Home (10.0.26200) |
| Docker | Docker Desktop, Docker Engine 27.4.0, Compose v2.31.0 |
| Kafka | `apache/kafka:3.9.1` (KRaft, um broker, um controller) |
| MySQL | 8.4.13 (`mysql:8.4`) |
| Mailpit | `axllent/mailpit:latest` |
| JDK | Temurin 21.0.13 |
| Maven | 3.9.11 (usado com `mvn -DskipTests package` para gerar os JARs) |
| Shell | Git Bash para os `curl`, PowerShell para o Docker e o Maven |

Observações de ambiente:
- `mvn` não estava no PATH. A execução usou a instalação em cache do Maven Wrapper (`~\.m2\wrapper\dists`).
- `uuidgen` não existe nesse Git Bash. Os `Idempotency-Key` foram gerados com `python -c 'import uuid;print(uuid.uuid4())'`.

---

## 2. Subida da stack

1. `docker compose -f infrastructure/docker/docker-compose.yml up -d --wait`: Kafka, MySQL e Mailpit ficaram `healthy`. Os tópicos foram criados pelo `kafka-init`.
2. `mvn -DskipTests package`: os três JARs executáveis foram gerados.
3. `java -jar` para cada serviço, sem variáveis: o perfil `local` foi aplicado.
4. Health checks:

| Serviço | Porta | Resultado |
|---|---|---|
| Transaction Service | 8081 | `{"groups":["liveness","readiness"],"status":"UP"}` |
| Fraud Detection Service | 8082 | `{"groups":["liveness","readiness"],"status":"UP"}` |
| Notification Service | 8083 | `{"groups":["liveness","readiness"],"status":"UP"}` |

Os três serviços ficaram `UP` cerca de 25 s após o início.

---

## 3. Exemplos `curl` da seção "Demonstração do caminho feliz e das fraudes"

| # | Comando | Esperado | Obtido | Resultado |
|---|---|---|---|---|
| 1 | `POST /transactions`, `c-1`, 15000 BRL | 201, análise LOW, sem alerta | 201, `transactionId 1d49f540…`; análise `LOW`; `alert_published = 0` | OK |
| 2 | `POST /transactions`, `c-2`, 250000 BRL | 201, análise MEDIUM, alerta | 201, `transactionId 93db4def…`; análise `MEDIUM`; `alert_published = 1`; e-mail recebido no Mailpit | OK |
| 3 | Mesma `Idempotency-Key` e mesmo corpo, duas vezes | 201 e depois 200, mesma resposta | 201, depois 200, mesmo `transactionId e7032289…` | OK |
| 4 | Mesma `Idempotency-Key` com outro corpo | 422 | 422, `"Idempotency-Key reutilizada com payload diferente"` | OK |
| extra | Seis transações do mesmo cliente em 60 s (descrito no README, sem curl) | a sexta é MEDIUM por `TRANSACTION_FREQUENCY` | cinco análises LOW e a sexta MEDIUM, com a razão `6 transações em 60 segundos; limite configurado: 5` | OK |

**Verificações nos bancos após os exemplos:**
- `transaction_db.transaction_record`: uma linha por transação aceita.
- `fraud_db.fraud_analysis`: uma análise por transação, com `alert_published = 1` apenas para MEDIUM.
- `notification_db.notification_record`: registros em `ACCEPTED_BY_PROVIDER`, com referência `smtp-<eventId>`.

---

## 4. Alteração do e-mail de alerta

**Antes:** texto puro de uma linha, sem formatação:

```
Transação <id> classificada com risco MEDIUM (1 regra(s) acionada(s)).
```

**Depois:** multipart com texto puro e HTML. O assunto (`Alerta antifraude: risco <nível>`) foi mantido.

Texto puro enviado:

```
ALERTA DE POSSÍVEL FRAUDE

Identificamos uma transação que precisa de atenção.

Transação: 89f52978-212c-47be-a338-2a0956580c4d
Nível de risco: Médio
Analisada em: 18/11/2026 às 22:36

Regras acionadas:
- Valor acima do limite configurado: Valor de 480000 centavos acima do limite configurado de 100000 centavos

Esta é uma mensagem automática do sistema de detecção de fraudes. Não responda a este e-mail.
```

O HTML tem cabeçalho, selo colorido de risco (Alto, Médio, Baixo), tabela com transação, nível e data, lista de regras e rodapé. O HTML de exemplo foi salvo em `scratchpad` durante a execução, não no repositório.

**Garantias do template (`AlertEmailTemplate`):**
- O `customerId` não aparece no e-mail, em nenhuma das duas versões. Isso é verificado em `AlertEmailTemplateTest`.
- Valores vindos do evento são escapados antes de entrar no HTML (`<`, `>`, `&`, `"`, `'`), também verificado no teste.
- A data é exibida no fuso `America/Sao_Paulo`.

**Testes do módulo `notification-service`:** 15 testes executados com `mvn -pl notification-service -am test`, todos passando. Inclui os 5 de `NotificationPipelineIntegrationTest`, que usa Testcontainers.

---

## 5. Itens de README que não funcionam como escritos (Windows)

- `uuidgen` não está disponível no Git Bash do Windows. Alternativas: `python -c 'import uuid;print(uuid.uuid4())'` ou `[guid]::NewGuid()` no PowerShell.
- `mvn` precisa estar no PATH. O projeto não tem Maven Wrapper versionado.
- Os comandos com `$(...)` e aspas simples funcionam no Git Bash, mas não no PowerShell.

---

## 6. Pendências relacionadas

- Medição de capacidade (spike BR-01 e carga até 25.000 TPS): continua pendente, como em `roadmap-execucao.md`.
