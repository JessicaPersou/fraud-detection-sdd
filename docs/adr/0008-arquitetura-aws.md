# ADR 0008 — Arquitetura alvo na AWS: decisões e justificativas

**Status:** Aceita  
**Data:** 2026-11-15 (revisado em 2026-11-16)  
**Relaciona-se com:** ADR 0001, ADR 0003, ADR 0004, ADR 0005, ADR 0007, ADR 0009, `docs/architecture.md`

## 1. Contexto e escopo

O sistema precisa atender a requisitos de volume e latência, e a arquitetura alvo foi desenhada para isso na AWS. Este ADR justifica cada escolha: o que foi escolhido, o que foi descartado e por quê, e qual é o custo de cada decisão.

**Escopo deste documento:** arquitetura alvo e raciocínio de decisão. A execução e a verificação do projeto acontecem localmente (Docker Compose) e no GitHub (CI com Testcontainers). Este ADR não descreve passos de implantação.

### 1.1 Requisitos que orientam as decisões

| Requisito | Valor | Origem |
|---|---|---|
| Volume médio | 8.000 transações por segundo | `project-overview.md` |
| Pico | 25.000 transações por segundo | `project-overview.md` |
| Latência | Término do processamento em no máximo 500 ms após o recebimento, para todas as transações (máximo absoluto). Para transações com alerta, o término é a publicação do alerta; para as LOW, a gravação da análise | `project-overview.md` §3 |
| Autenticação do cliente final | Já feita pelo sistema chamador antes do envio | Premissa do projeto |
| Autenticação entre serviços AWS | IAM, sem chaves de longa duração | Premissa do projeto |
| Região | `sa-east-1` (São Paulo), por residência de dados e latência para o público-alvo | Decisão do time |

## 2. Topologia alvo

```text
Sistemas internos (já autenticados)
   │  HTTPS, mTLS, Idempotency-Key
   ▼
AWS WAF ──► Application Load Balancer (3 AZs)
                │
   ┌────────────┴────────── VPC (3 AZs) ───────────────────────────────┐
   │  Subnets privadas: EKS                                             │
   │    • transaction-service  (API + publicador do outbox)             │
   │    • fraud-service        (consumidor transaction-events)          │
   │    • notification-service (consumidor fraud-alerts)                │
   │    • KEDA (autoscaling pelo lag do consumer group)                 │
   │                                                                    │
   │  Subnets isoladas: RDS for MySQL 8.4 Multi-AZ + RDS Proxy          │
   │    • transaction_db, fraud_db, notification_db                     │
   │                                                                    │
   │  VPC PrivateLink ──► Confluent Cloud (Kafka)                       │
   │  NAT Gateway ──► provedor de notificação (saída controlada)        │
   └────────────────────────────────────────────────────────────────────┘
```

## 3. Decisões e justificativas

Cada linha responde três perguntas: o que foi escolhido, o que foi descartado e por quê, e o custo aceito.

### 3.1 Entrada HTTP

| Decisão | Alternativas | Justificativa | Custo aceito |
|---|---|---|---|
| **ALB + AWS WAF** | API Gateway; NLB | O API Gateway tem cota de requisições por região que exigiria aumento para 25.000 TPS. O ALB não tem essa limitação e suporta mTLS nativamente. O WAF filtra tráfego malicioso antes do serviço. | Sem recursos de gestão de API (planos de uso, chaves) que o API Gateway traria. Não são necessários, porque o cliente já está autenticado. |

### 3.2 Autenticação do sistema chamador

| Decisão | Alternativas | Justificativa | Custo aceito |
|---|---|---|---|
| **mTLS no ALB** como único mecanismo | JWT assinado pelo upstream; chave de API no API Gateway; mTLS + JWT | O cliente final já está autenticado. O que falta provar é que a chamada vem de um sistema autorizado, e o certificado de cliente faz isso. JWT por requisição adicionaria verificação de assinatura e gestão de chaves sem ganho enquanto a auditoria não exigir prova da identidade do cliente em cada requisição. | A identidade do cliente final é confiada ao sistema chamador. Se a auditoria exigir prova por requisição, adicionar JWT na camada de aplicação. |

### 3.3 Computação

| Decisão | Alternativas | Justificativa | Custo aceito |
|---|---|---|---|
| **EKS** | ECS Fargate | EKS oferece KEDA para escalar pelo lag do Kafka, que é o sinal real de capacidade dos consumidores. Também oferece o Pod Identity para permissões por serviço. ECS também funcionaria, mas exigiria mecanismo próprio para escalar pelo lag. | Mais operação: atualização de versão do cluster, add-ons e políticas de rede. Para um time pequeno, é o custo principal desta decisão. |
| **KEDA** para consumidores; HPA para a API | Escala fixa; HPA só por CPU | O HPA por CPU não reage ao acúmulo de mensagens. O KEDA escala pelo lag do consumer group. | Número máximo útil de réplicas consumidoras = número de partições (ver 3.5). |

### 3.4 Mensageria

| Decisão | Alternativas | Justificativa | Custo aceito |
|---|---|---|---|
| **Confluent Cloud Dedicated**, região `sa-east-1` | Amazon MSK provisionado; MSK Serverless; Confluent Standard | O Dedicated reserva capacidade para os picos de 25.000 TPS, sem o limite de throughput do Standard. Oferece conectividade privada por PrivateLink. Kafka gerenciado remove a operação de brokers, ZooKeeper e atualizações. | Custo de capacidade reservada, maior que MSK provisionado para o mesmo volume. Dependência de fornecedor fora da AWS. |
| **Kafka KRaft na versão 3.9** no ambiente local | Kafka com ZooKeeper | KRaft elimina o ZooKeeper (ADR 0007). A versão local e a de referência são a mesma linha. | — |

**Por que não MSK:** MSK também atenderia, e a integração com IAM é nativa. A escolha pelo Confluent foi feita pela capacidade de conectividade privada e pelo ecossistema, não por um requisito técnico que o MSK não cumpra. Esta é a decisão mais discutível do desenho, e o argumento é de conveniência operacional e de fornecedor.

### 3.5 Partições e paralelismo

| Decisão | Alternativas | Justificativa | Custo aceito |
|---|---|---|---|
| **12 partições** por tópico, como hipótese inicial | Mais partições desde o início; número calculado por TPS total | 12 partições dão 12 consumidores ativos. Com 25.000 TPS de pico, cada partição recebe cerca de 2.100 eventos por segundo. Esse valor deve ser medido e revisado (ADR 0005). | Aumentar partições depois altera o mapeamento de chaves por cliente (`kafka-partitioning.md` §6). |
| **Chave `customerId`** | Chave por `transactionId`; sem chave | Garante ordem por cliente dentro da partição, o que a regra de frequência precisa. | Cliente com volume alto concentra carga em uma partição (partição quente, risco R4). |

### 3.6 Banco de dados

| Decisão | Alternativas | Justificativa | Custo aceito |
|---|---|---|---|
| **RDS for MySQL 8.4 Multi-AZ**, um banco por serviço | Aurora MySQL; DynamoDB; PostgreSQL | MySQL 8.4 é a versão já definida no ADR 0007, e o RDS mantém essa versão sem migração. Multi-AZ dá alta disponibilidade sem reescrita. Um banco por serviço preserva a autonomia de dados (`architecture.md` §5). | Aurora teria mais throughput de escrita e réplicas de leitura mais baratas. Fica como evolução se o cálculo de frequência for o gargalo (R1). |
| **RDS Proxy** | Conexão direta | O autoscaling multiplica conexões. O proxy as agrupa. | Custo adicional e um salto de rede. |
| **IAM database authentication** | Senha no Secrets Manager | Não há senha armazenada. O token expira em 15 minutos e é renovado pelo driver. | Geração de token no pool de conexões. |

### 3.7 Identidade e segredos

| Decisão | Alternativas | Justificativa | Custo aceito |
|---|---|---|---|
| **EKS Pod Identity**, uma role por serviço | IRSA; chaves de acesso no pod | Permissões mínimas por serviço. Pod Identity simplifica a associação, sem anotação de ServiceAccount por cluster. IRSA atenderia igualmente. | Dependência de recurso recente do EKS. |
| **OIDC do EKS para autenticar no Kafka** | API keys do Confluent no Secrets Manager | Não há segredo de Kafka armazenado. A identidade do pod é o token do ServiceAccount, verificado pelo Confluent. | Exige cliente Kafka com suporte a OAuth/OIDC e validação do fluxo. API keys são o plano de contingência. |
| **Secrets Manager** para o restante | Variáveis de ambiente | Rotação e auditoria de acesso. | Custo por segredo. |

### 3.8 Conectividade e rede

| Decisão | Alternativas | Justificativa | Custo aceito |
|---|---|---|---|
| **PrivateLink** para o Kafka | Acesso público por NAT | O tráfego de mensagens não passa pela internet nem pelo NAT. Isso reduz a superfície de ataque e o custo de processamento de NAT. | Cobrança própria por dados processados pelo PrivateLink. |
| **Subnets isoladas** para os bancos | Subnets privadas | Os bancos não têm rota para internet. | — |
| **VPC endpoints** para ECR, STS, Secrets Manager, KMS e CloudWatch | Tráfego pelo NAT | Reduz custo e mantém o tráfego interno à AWS. | Custo por endpoint. |

### 3.9 Observabilidade

| Decisão | Alternativas | Justificativa | Custo aceito |
|---|---|---|---|
| **Micrometer + Actuator**, com métricas do cliente Kafka para lag | Prometheus e Grafana próprios; CloudWatch apenas | Lag é exposto pelo próprio cliente Kafka e verificado no projeto. A latência do alerta é medida por `fraud.alert.latency`. | Exporter para CloudWatch ou Prometheus precisa ser escolhido na implantação. |
| **Micrometer Tracing** (ponte OpenTelemetry), spans no console | OTLP para um coletor; Zipkin | Mesma fachada das métricas. O console dispensa infraestrutura extra na demonstração (ADR 0010). | Exportação para um coletor é evolução. |

### 3.10 CI/CD

| Decisão | Alternativas | Justificativa | Custo aceito |
|---|---|---|---|
| **GitHub Actions** com `mvn verify` e Testcontainers | Jenkins; pipeline sem testes de integração | O pipeline roda os testes com MySQL e Kafka reais, o que pega erros que testes unitários não pegam. O GitHub é onde o projeto vive. | Depende de runners com Docker. |
| **OIDC do GitHub** para assumir role na AWS | Chaves de acesso estáticas | Sem credenciais de longa duração. | Configuração de confiança por repositório. |

## 4. Como a arquitetura atende o requisito de 500 ms

O requisito é um **máximo absoluto**. Por isso o desenho evita filas e esperas longas no caminho crítico.

| Etapa | Contribuição típica | Como é controlada |
|---|---|---|
| Recebimento e gravação da transação com outbox | Uma transação de banco | Índices e transação curta |
| Espera pelo publicador do outbox | Até o intervalo de polling (200 ms) | **Maior componente controlável.** Pode ser reduzido por CDC, decisão adiada pelo ADR 0003 |
| Publicação no Kafka e replicação | Milissegundos | `acks=all` com replicação; o Dedicated tem capacidade reservada |
| Consumo, análise e publicação do alerta | Uma consulta de frequência e a publicação | Índice por cliente e instante; consumo em lote |

**Medição local (execução de referência do projeto):** o fluxo HTTP até a notificação aceita teve p50 de 257 ms e p95 de 469 ms. Esse tempo inclui a espera pelo polling do outbox e a entrega da notificação. A latência de geração do alerta, sem a notificação, é medida pela métrica `fraud.alert.latency`.

**Leitura honesta:** os números locais dependem da máquina e não são garantia de comportamento na arquitetura alvo. Servem para mostrar que o caminho crítico funciona e onde estão os componentes de espera.

## 5. Capacidade (análise)

- **Partições:** 25.000 TPS / 12 partições ≈ 2.100 eventos/s por partição. Esse valor indica onde a capacidade de um consumidor deve ser medida.
- **Consumidores:** com KEDA, o número máximo útil é igual ao número de partições.
- **Tráfego para o Kafka:** com mensagens de 1 KB, o pico de 25.000 TPS é ≈ 25 MB/s, ou cerca de 2,2 TB por dia. A média de 8.000 TPS é ≈ 0,7 TB por dia.
- **Custo de transferência:** o custo depende da tabela de preços vigente e do caminho de rede. Por isso o PrivateLink foi escolhido: o tráfego não passa pelo processamento de NAT.

## 6. Alternativas descartadas (resumo)

- **API Gateway:** cota de requisições insuficiente para o pico sem aumento.
- **ECS Fargate:** menos operação, mas sem KEDA nativo para escalar pelo lag.
- **MSK Serverless:** throughput previsível para o pico favorece o provisionado.
- **Aurora MySQL:** evolução possível; não é necessária antes de medir gargalo de I/O.
- **Redis para frequência:** adiado; só entra se o cálculo em MySQL não atingir a latência (R1, ADR 0001).
- **CDC com Debezium:** adiado; o polling atende e CDC exige gatilho medido (ADR 0003).

## 7. Riscos e limitações

- **Latência da frequência em MySQL (R1):** pode não atingir o orçamento sob carga. A evolução é Redis ou janela pré-agregada.
- **Partição quente (R4):** um cliente com volume alto concentra carga. A chave por `customerId` é necessária para a regra, então o risco é aceito e monitorado.
- **Dependência do Confluent:** fornecedor fora da AWS. A aplicação usa o protocolo Kafka padrão, então a troca afeta só a configuração de conexão.
- **Operação do EKS:** atualizações e add-ons são responsabilidade do time.
- **Sem recuperação de desastre entre regiões:** fora do escopo do estudo; a arquitetura é uma região com três AZs.

## 8. Pendências de análise

1. Validar o fluxo OIDC do EKS com o Confluent (decisão tomada, falta validação técnica).
2. Revisar o número de partições com base em medição de capacidade por consumidor.
3. Avaliar CDC se o intervalo de polling consumir parte relevante do orçamento de 500 ms.
4. Confirmar a tabela de preços para o cálculo de custo.
