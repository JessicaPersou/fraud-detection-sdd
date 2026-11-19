# Limitações e Possíveis Evoluções

Este documento reúne, em um só lugar, o que a solução **não** faz ainda, o que **não foi medido**, e quais evoluções já têm um gatilho definido. As decisões que originam cada item estão nos ADRs (`docs/adr/`), no roadmap (`roadmap-execucao.md`) e no escopo (`project-overview.md`). Quando houver conflito entre este documento e um ADR, vale o ADR.

Estado geral: a v1 implementa o fluxo completo com testes automatizados. Ela **não** está validada para carga de produção nem para um ambiente regulado.

---

## 1. Limitações técnicas

| # | Limitação | Impacto | Referência |
|---|---|---|---|
| T-01 | A regra de frequência (BR-01) roda em MySQL e não foi medida com hardware documentado | Pode não atingir o orçamento de 500 ms sob carga. O risco é R1 | R1; ADR 0001 (dec. 4) |
| T-02 | Não há teste de carga concluído até 25.000 TPS. A execução foi iniciada em uma máquina pessoal e a máquina travou, sem resultado válido | A capacidade real é desconhecida | L-06; `roadmap-execucao.md` §3.4 |
| T-03 | Capacidade por partição não medida. Há 12 partições como hipótese | Pode haver gargalo de consumo no pico | R2; ADR 0005 |
| T-04 | Atraso do outbox não medido. O publicador usa polling de 200 ms | O atraso pode consumir parte relevante do orçamento de 500 ms | R3; ADR 0003 |
| T-05 | Partição quente não medida. A chave é `customerId`, necessária para a regra de frequência | Um cliente com volume alto concentra carga em uma partição | R4; `kafka-partitioning.md` §5 |
| T-06 | Tabelas de idempotência e inbox têm limpeza, mas o crescimento sob carga não foi medido | Pode haver crescimento acima do previsto | R5; ADR 0009 §2.1 |
| T-07 | Não há circuit breaker no Notification Service | Falhas prolongadas do provedor geram retry e DLQ, sem proteção ativa | R6; ADR 0009 §2.2 |
| T-08 | Validação de configuração na inicialização não é formal | Divergência entre ambientes é possível. Fora do perfil `local`, as variáveis obrigatórias impedem a subida sem valor, mas isso não é verificado por teste | R7; ADR 0011 |
| T-15 | O perfil `local` (valores de demonstração) é ativado por padrão | Um ambiente que não defina `SPRING_PROFILES_ACTIVE=prod` sobe com valores de demonstração. A proteção depende da implantação | ADR 0011 (seção 4) |
| T-09 | Regra de localização (BR-03) não implementada. O contrato `TransactionCreated` não traz localização | Não há detecção por localização. Ausência de dados nunca vira fraude, então isso não gera falso positivo | `roadmap-execucao.md` §3.2 |
| T-10 | A latência de consumo isolada (L-07) não é medida | Só a latência de publicação e o lag são observados | ADR 0008 §2.8 |
| T-11 | `DELIVERED` no Notification Service está reservado e não é usado | A entrega ao cliente final não é confirmada. O sistema registra `ACCEPTED_BY_PROVIDER` | `roadmap-execucao.md` §3.3 |
| T-12 | O provedor de notificação é simulado ou SMTP para o Mailpit | Não há integração com provedor real nem SLA | Pendência de negócio |
| T-13 | Não há autenticação nem autorização na API nem nos consumidores | Qualquer cliente de rede alcançável pode criar transações. Requisito de produção, ainda não implementado | `architecture.md` §9; ADR 0008 |
| T-14 | Não há validação formal de LGPD. Não há teste que prove a minimização de dados nos logs | O destinatário é gravado para auditoria e não vai para log, por decisão de projeto. A conformidade não foi validada | `roadmap-execucao.md` §1 e §3.3 |

---

## 2. Limitações de negócio

Estas pendências não se resolvem por decisão técnica. Os valores ficam em configuração, com valores de demonstração no README.

- **Limite de valor (BR-02)**, **janela e limite de frequência (BR-01)**: aguardam validação.
- **Distância e intervalo de localização (BR-03)**: aguardam validação e dependem de dados geográficos que hoje não existem no contrato.
- **Pesos e combinação de sinais (BR-04)**: a política de classificação é provisória. Nenhum sinal é LOW, um sinal é MEDIUM e dois ou mais é HIGH (`business-rules.md` §7).
- **Código de erro para corpo inválido** no `POST /transactions`: a especificação não define.
- **Provedor e SLA de notificação externa**.
- **Requisitos de LGPD e segurança**.

A consequência prática é que os testes de regra de negócio provam o **mecanismo** de classificação, não o **valor** de negócio.

---

## 3. Limitações operacionais

- **Execução local.** A v1 é executada com Docker Compose. A arquitetura alvo na AWS está justificada no ADR 0008, mas não foi implementada.
- **Sem CI ativa.** O workflow do GitHub Actions foi removido. A verificação é `mvn verify` local, com Docker ativo. A decisão de reativar a CI está pendente (`roadmap-execucao.md`, critério 5 da Fase 1).
- **Sem recuperação de desastre entre regiões.** A arquitetura alvo é uma região com três AZs. Isso está fora do escopo do estudo (ADR 0008 §7).
- **Operação do EKS** (atualizações e add-ons) fica sob responsabilidade do time, quando a arquitetura alvo for adotada (ADR 0008 §7).
- **Dependência do Confluent Cloud** na arquitetura alvo. A aplicação usa o protocolo Kafka padrão, então a troca afeta apenas a configuração de conexão (ADR 0008 §7).
- **OIDC do EKS com o Confluent** ainda não foi validado tecnicamente. Está decidido, mas falta validação (ADR 0008 §2.3).

---

## 4. Evoluções com gatilho definido

Cada evolução abaixo só deve ser feita quando o gatilho acontecer. Isso evita otimização antecipada.

| Evolução | Gatilho | Documento |
|---|---|---|
| Regra de frequência em Redis, ou janela pré-agregada | p99 do spike acima do limite (R1) | ADR 0001; roadmap §6 |
| CDC com Debezium no lugar do polling do outbox | Lag p99 do outbox acima do limite mesmo com polling ajustado; ou polling afetando a escrita; ou volume exigindo leitura do log | ADR 0003 |
| Verificação rápida de idempotência em Redis (`SET NX` com TTL) | Tabela de idempotência virar gargalo medido | ADR 0004 |
| Mais partições e instâncias de consumidor | Limite por partição atingido no spike. Exige planejar a mudança de chave antes | ADR 0005 |
| Inbox substituída por controle em Redis | Tabela de inbox virar gargalo medido | ADR 0005 |
| Evento `NotificationRequested` e roteamento por canal | Dois ou mais consumidores decidirem canais de forma diferente; retry independente por canal; notificações de outros domínios | ADR 0002 |
| Circuit breaker no Notification | Taxa de erro e DLQ acima do limite (R6) | ADR 0009 §2.2 |
| Novas moedas | Validação de negócio e teste de configuração. Mudar a representação do valor exige nova ADR e nova versão do contrato | ADR 0006 |
| Atualização de versões de Spring Boot, Kafka ou MySQL | Dedicada, com a suíte passando. Major version de Spring Boot ou Kafka exige nota neste ADR | ADR 0007 |
| Mudanças no contrato de eventos | Compatibilidade com consumidores existentes; evolução por versão | `event-contracts.md` §5 |
| Aurora MySQL | Gargalo de I/O medido. Não é necessário antes disso | ADR 0008 §6 |
| MSK Serverless | Não se aplica: o throughput previsível do pico favorece o provisionado | ADR 0008 §6 |

---

## 5. Deliberadamente fora do escopo

Estes itens foram excluídos da v1 com justificativa, e não por esquecimento.

- **Escala além do spike**: sem evidência de carga, sharding ou múltiplas regiões seriam otimização prematura (`roadmap-execucao.md` §7).
- **Bloqueio automático de transações**: fora do escopo da v1. O sistema só classifica e alerta (`project-overview.md` §7).
- **Machine Learning, modelos preditivos e risk scoring avançado**: fora do escopo da v1 (`project-overview.md` §7).
- **Painel administrativo** (`project-overview.md` §7).
- **Múltiplos provedores de notificação** (`project-overview.md` §7).
- **Redis** para contadores: não incluído na v1, avaliado só se houver necessidade medida (`project-overview.md` §4).

---

## 6. Ordem sugerida para fechar as lacunas

1. **Definir os valores de negócio** (BR-01, BR-02, BR-04). Sem eles, a classificação é mecanismo, não decisão.
2. **Executar o spike da BR-01** com hardware documentado (T-01). A decisão de Redis depende do resultado.
3. **Executar a carga progressiva** (T-02, T-03, T-04, T-05) e fixar os critérios numéricos depois do spike.
4. **Implementar autenticação e autorização** (T-13) e fechar a validação de LGPD (T-14) antes de qualquer ambiente real.
5. **Decidir sobre a CI remota** para que a verificação rode a cada alteração.
6. **Implementar BR-03** somente quando o contrato trouxer dados de localização (T-09).
