# ADR 0008 — Arquitetura de hospedagem na AWS

**Status:** Aceita (2026-11-15). As pendências da seção 5 são resolvidas no spike e na Fase 5.  
**Data:** 2026-11-15  
**Relaciona-se com:** ADR 0001, ADR 0003, ADR 0005, ADR 0007, `docs/architecture.md`, `docs/roadmap-execucao.md` (R1 a R4)

## 1. Contexto

O sistema será hospedado na AWS, com Kafka gerenciado pelo Confluent Cloud. Os documentos atuais definem a topologia lógica (três microsserviços, Kafka e MySQL), mas não a infraestrutura de produção. `architecture.md` §9 exige segurança em trânsito e em repouso, e `project-overview.md` exige escala horizontal até 25.000 TPS.

Premissas desta revisão:

- **Computação:** Amazon EKS.
- **Banco:** Amazon RDS for MySQL 8.4.
- **Kafka:** Confluent Cloud Dedicated, fora da AWS, conectado por AWS PrivateLink.
- **Volume e latência:** volume médio de 8.000 transações por segundo, com picos de até 25.000 TPS. O sistema gera alertas em no máximo 500 ms após o recebimento da transação. A entrega da notificação ao destinatário tem SLA próprio, condicionado aos provedores externos, e não entra nessa meta.
- **Autenticação do cliente:** o cliente final já está autenticado quando envia a transação. O sistema não faz login de usuário final; recebe uma requisição já autenticada upstream (seção 2.3).
- **IAM:** usado para a comunicação entre os serviços da AWS (pods, RDS, Secrets Manager, KMS, ECR, CloudWatch). O Confluent Cloud não aceita autenticação por IAM/SigV4 no protocolo Kafka; ver seção 2.3.

Os números de capacidade não são definidos aqui: são validados pelo spike test (Fase 3b) e pela carga progressiva (Fase 5).

**Escopo:** este é um estudo de caso. O documento discute os principais pontos da arquitetura e as alternativas tecnológicas, sem chegar a uma solução completa pronta para produção. Runbooks, recuperação de desastres entre regiões, políticas detalhadas de backup e hardening completo ficam fora deste ADR.

## 2. Proposta

### 2.1 Topologia

```text
Sistemas internos (já autenticados upstream)
   │  HTTPS + Idempotency-Key
   ▼
AWS WAF ──► Application Load Balancer (subnets públicas, 3 AZs)
                │
   ┌────────────┴──────────── VPC (3 AZs) ──────────────────────────────┐
   │  Subnets privadas: cluster EKS (node groups gerenciados ou Karpenter)│
   │    • transaction-service  (API + publicador do outbox)              │
   │    • fraud-service        (consumidor transaction-events)           │
   │    • notification-service (consumidor fraud-alerts)                 │
   │    • KEDA (autoscaling por lag do consumer group)                   │
   │                                                                     │
   │  Subnets isoladas (sem rota para internet)                          │
   │    • RDS MySQL 8.4 Multi-AZ + RDS Proxy: transaction_db,            │
   │      fraud_db, notification_db                                      │
   │                                                                     │
   │  PrivateLink ──► Confluent Cloud Dedicated (TLS, sem NAT)         │
   │                  seção 2.4)                   │
   │  NAT Gateway ──► provedor de notificação externo (egress)           │
   └─────────────────────────────────────────────────────────────────────┘
                  │
                  ▼
   Confluent Cloud (mesma região AWS): transaction-events, fraud-alerts, *.DLT
```

### 2.2 Componentes

| Camada | Escolha | Alternativa considerada | Motivo |
|---|---|---|---|
| Entrada HTTP | ALB + AWS WAF | API Gateway | API Gateway tem cota de requisições por região que precisaria de aumento para 25.000 TPS. ALB não tem essa limitação. A autenticação já é feita upstream (seção 2.3). |
| Computação | Amazon EKS, um Deployment por microsserviço | ECS Fargate | Decisão do time. Traz ecossistema Kubernetes (HPA, KEDA, Pod Identity) ao custo de mais operação. Exige plano de atualização de versão do cluster e de add-ons. |
| Autoscaling | Transaction: HPA por CPU e requisições. Fraud e Notification: KEDA com o scaler de Kafka (lag do consumer group). | Escala fixa | Fraud escala pelo lag, que é o sinal real de capacidade (R2). **Limite:** o número máximo útil de réplicas de um consumer group é o número de partições do tópico. |
| Mensageria | Confluent Cloud Dedicated, região sa-east-1, conectado por AWS PrivateLink (seção 2.4) | Confluent Standard com TLS público; Amazon MSK | Decisão do time. Dedicated atende os picos de 25.000 TPS e oferece baixa latência e conectividade privada. Custo de throughput e de transferência precisa ser estimado (seção 2.5). |
| Banco | RDS for MySQL 8.4 Multi-AZ, um banco por serviço | Aurora MySQL | Decisão do time. RDS mantém MySQL 8.4 conforme ADR 0007. Aurora fica como evolução se o spike test mostrar limite de I/O ou necessidade de réplicas de leitura. |
| Conexões de banco | RDS Proxy | Conexão direta | Controla conexões quando o HPA escala os pods. Também aceita autenticação por IAM. Confirmar no spike test. |
| Autenticação dos pods no RDS | IAM database authentication, com credencial de IAM obtida via EKS Pod Identity | Usuário e senha no Secrets Manager | Sem senha de banco armazenada. O token expira em 15 minutos e é renovado pelo driver. Avaliar o custo de geração do token no pool de conexões. |
| Identidade dos pods na AWS | EKS Pod Identity, um role IAM por ServiceAccount, com permissões mínimas | IRSA | Uma associação por serviço, sem permissões compartilhadas entre microsserviços. IRSA é equivalente e pode ser usado se o cluster já estiver nele. |
| Segredos | Secrets Manager com rotação, sincronizado no cluster pelo External Secrets Operator | Variáveis de ambiente em texto puro ou ConfigMap | Credenciais nunca no código, na imagem ou em manifests versionados. |
| Criptografia | TLS em trânsito; KMS em repouso (RDS, Secrets Manager, EBS dos nós, ECR) | — | `architecture.md` §9. Chaves gerenciadas pelo cliente no KMS. |
| Rede | VPC em 3 AZs; subnets públicas (ALB, NAT); privadas (EKS); isoladas (RDS) | — | Bancos nunca expostos à internet. |
| Egress | NAT Gateway por AZ. Endpoints de VPC para ECR, STS, Secrets Manager, KMS e CloudWatch | Egress sem NAT | Necessário para o provedor de notificação (Fase 4). O Confluent é acessado por PrivateLink, sem NAT. Endpoints reduzem tráfego pelo NAT. |
| Observabilidade | CloudWatch (logs e métricas), OpenTelemetry para tracing, métricas de lag e de consumo expostas pelo Confluent Cloud | Prometheus e Grafana próprios | Atende `architecture.md` §8. Revisar se o custo de métricas crescer. |
| CI/CD | GitHub Actions com OIDC para a AWS, imagens no ECR, deploy no EKS (Helm ou GitOps) | Chaves de acesso estáticas | Sem credenciais de longa duração. Pipeline do ADR 0007 é estendida com build de imagem e deploy. |
| Carga | Gerador de carga (ex.: k6) em um Job no EKS, na mesma VPC | Ferramenta externa | Mede latência dentro da rede, sem ruído de internet. |

### 2.3 Identidade e autenticação

Há três fronteiras distintas. Cada uma tem seu mecanismo.

**a) Cliente final → Transaction Service (já autenticado upstream)**

- O sistema não autentica o usuário final. A requisição chega com a identidade já verificada pelo sistema chamador.
- O Transaction Service confia na identidade do sistema chamador por mTLS no ALB, único mecanismo adotado. O certificado do cliente identifica o canal que já autenticou o cliente. Tráfego sem certificado válido não chega ao serviço. O `customerId` do corpo é aceito como informado por esse canal confiável.
- Descartado por ora: JWT assinado pelo upstream, que daria prova da identidade do cliente em cada requisição. Adicionar só se a auditoria exigir essa prova. Confirmar com o time upstream (seção 5).

**b) Serviços AWS ↔ serviços AWS (IAM)**

- Pods EKS → RDS: IAM database authentication pelo RDS Proxy.
- Pods EKS → Secrets Manager, KMS, ECR, CloudWatch: role IAM por serviço via EKS Pod Identity.
- CI/CD → AWS: OIDC do GitHub Actions assumindo role IAM.
- Cada role tem permissões mínimas para o próprio serviço. Nenhum serviço acessa o banco de outro.

**c) Serviços → Confluent Cloud (fora do IAM)**

O Confluent Cloud não aceita SigV4 no protocolo Kafka. Opções para a identidade dos pods no Kafka:

1. **API keys do Confluent, por serviço,** guardadas no Secrets Manager e sincronizadas pelo External Secrets Operator. Simples e suportado. Exige rotação manual ou automatizada e um segredo por serviço.
2. **OAuth/OIDC com identity pool do Confluent,** confiando no emissor OIDC do cluster EKS. A identidade do pod (token projetado do ServiceAccount) substitui o segredo estático. Caminho alinhado ao objetivo de IAM e confirmado para este projeto. Pontos a validar no spike: a documentação do Confluent descreve OAuth/OIDC com cliente Java 4.0 ou posterior e um fluxo de client assertion (KIP-1258). A integração com o emissor do EKS precisa de spike.

Decisão: OAuth/OIDC (opção 2), confiando no emissor OIDC do cluster EKS. Não há segredo de Kafka no cluster. O spike valida o fluxo completo antes da produção: token projetado do ServiceAccount, client assertion e filtros do identity pool. Se o fluxo não funcionar, a alternativa é a opção 1.

### 2.4 Confluent Cloud: tipo de cluster e rede

- A conectividade privada (PrivateLink) depende do tipo de cluster. A documentação de rede da Confluent indica que o tipo Standard usa conectividade pública na AWS, e que PrivateLink de entrada é oferecido para Dedicated. Para Enterprise, as fontes são inconsistentes; confirmar nos documentos atuais antes de decidir.
- O tipo de rede **não pode ser alterado depois da criação do cluster**. A escolha precisa ser feita antes do provisionamento.
- Decisão: Dedicated com PrivateLink. O tráfego com o Confluent não passa pelo NAT Gateway. Os picos de 25.000 TPS e a baixa latência justificam o tipo.
  - Produção: Dedicated com PrivateLink.
  - Homologação: Enterprise ou Standard, sem PrivateLink, com TLS público pelo NAT. A escolha entre os dois depende do custo e da necessidade de representar a rede de produção no spike.
- O cluster fica na **mesma região AWS** dos workloads, sa-east-1 (São Paulo). Isso reduz latência e custo de transferência entre nuvens. Confirmar que o Confluent Dedicated está disponível nessa região.

### 2.5 Custo de transferência (ordem de grandeza)

Com mensagens de 1 KB, 25.000 TPS no pico geram cerca de 25 MB/s, ou aproximadamente 2,2 TB por dia. Com 8.000 TPS de média, cerca de 0,7 TB por dia. Esse tráfego atravessa o NAT Gateway em ambos os sentidos, se o Confluent for acessado por endpoint público. Cobrança por processamento de NAT, por transferência e por throughput do Confluent precisa ser calculada com a tabela de preços vigente antes da escolha da seção 2.4. Com PrivateLink, esse tráfego não passa pelo NAT Gateway, mas o PrivateLink tem cobrança própria por dado processado. As duas cobranças precisam ser confirmadas na estimativa.

### 2.6 Ambientes

| Ambiente | Uso | Características |
|---|---|---|
| Local | Desenvolvimento | Docker Compose (ADR 0007). Kafka local, sem Confluent. Fator de replicação 1. |
| Homologação | Testes de integração e spike test | Mesmo desenho de produção, dimensionamento reduzido. Confluent Enterprise ou Standard, sem PrivateLink. Fator de replicação 3. |
| Produção | Operação | Dimensionamento definido pelo spike test e pela carga progressiva. |

A homologação só é representativa se o dimensionamento e a configuração forem documentados, como exige a meta de 25.000 TPS (`implementation-plan.md`).

### 2.7 Escala e riscos

**Spike test:** teste de carga curto e focado em um risco específico, feito antes de construir o restante. Ele responde se o risco é aceitável e, se não for, leva à mudança de desenho antes de a implementação avançar. No roadmap, o spike é a Fase 3b (regra BR-01 em MySQL). Neste ADR, o spike também valida o fluxo OIDC com o EKS, a alocação do orçamento de 500 ms e o número de partições (R2).

- **R1 (MySQL e BR-01):** o banco do Fraud Detection é o principal risco de capacidade. Se o spike test não atingir a latência, a alternativa é Redis ou janela pré-agregada (ADR 0004 e ADR 0005 já preveem essa evolução). Esta decisão não é tomada aqui.
- **R2 (partições):** 12 partições resultam em cerca de 2.100 eventos/s por partição no pico. Com KEDA, **não há ganho de réplicas acima do número de partições**. Se o pico exigir mais consumidores, o número de partições precisa aumentar antes da Fase 5, o que altera o mapeamento de chaves (`kafka-partitioning.md` §6). No Confluent Dedicated, verificar o limite de partições do cluster escolhido.
- **R3 (outbox):** o polling do publicador consulta o MySQL do Transaction Service em intervalos de 200 ms por instância. A carga é medida no spike test (ADR 0003).
- **R4 (dependência de fornecedor do Kafka):** o Confluent é um fornecedor fora da AWS, com custo e contrato próprios. A camada de aplicação continua usando o protocolo Kafka padrão, então a troca de provedor afeta só a infraestrutura e a configuração de conexão.
- **Custo:** homologação em topologia representativa tem custo recorrente, incluindo o Confluent. Pode ser ligada só durante os testes de carga.

### 2.8 Latência e monitoramento do pipeline

**Meta:** gerar alertas em no máximo 500 ms após o recebimento da transação pelo Transaction Service, até a publicação do evento de alerta em `fraud-alerts`. A meta é um limite máximo, não um percentil. p95 e p99 são medidos para diagnóstico. A meta vale para transações que geram alerta. A latência da análise sem alerta é medida à parte. A entrega ao destinatário tem SLA próprio, condicionado aos provedores externos, e não entra nessa meta.

**Etapas que consomem o orçamento de 500 ms** (a alocação por etapa é definida no spike):

- Recebimento, validação e gravação da transação com o outbox.
- Espera pelo publicador do outbox. Com o intervalo de polling de 200 ms (ADR 0003), essa espera pode chegar a 200 ms e consome parte relevante do orçamento. O intervalo deve ser reavaliado no spike.
- Publicação no Kafka e replicação no Confluent Dedicated.
- Consumo pelo Fraud Detection, análise e publicação do alerta.

**Monitorar continuamente:**

- Consumer lag por consumer group e partição. É o sinal de capacidade (R2).
- Latência de publicação: do registro no outbox até o ack do Kafka.
- Latência de consumo: da publicação do evento até o fim do processamento pelo consumidor.
- Latência ponta a ponta: do recebimento ao alerta publicado, com p50, p95 e p99.
- Idade do registro mais antigo pendente no outbox (L-03).
- Métricas do Confluent Dedicated (throughput e latências de produção e consumo), para correlação.

Os limites dos alertas operacionais (quando o time é acionado) ficam para depois, conforme definição do time.

## 3. Consequências

- O código dos serviços não depende de AWS nem de Confluent. Clientes Kafka, JDBC e configuração por variáveis de ambiente funcionam igual no compose e na nuvem. Para o Confluent, a diferença é a configuração de SASL/TLS.
- Tópicos (12 partições, retenção de 7 dias) são definidos como código de infraestrutura, por exemplo Terraform com o provider do Confluent, e não pelo serviço de inicialização do compose.
- O provedor de notificação e o Confluent (se público) são destinos fora da VPC. Ambos saem pelo NAT e devem ficar restritos a esses destinos.
- Operação de EKS (atualização do cluster, add-ons, nós e políticas de rede) passa a ser responsabilidade do time. Isso é um custo real para um time pequeno.
- Credenciais, endpoints e certificados vêm do Secrets Manager e da configuração de ambiente. Nenhum segredo de banco fica no cluster.

## 4. Decisões

Decisões aceitas:

1. **Computação:** Amazon EKS.
2. **Banco:** RDS for MySQL 8.4 Multi-AZ, com RDS Proxy e IAM database authentication.
3. **Kafka:** Confluent Cloud, na mesma região AWS dos workloads.
4. **Identidade AWS:** IAM via EKS Pod Identity, sem chaves de longa duração.
5. **Região:** sa-east-1 (São Paulo).
6. **Autenticação upstream:** mTLS no ALB como único mecanismo.
7. **Autenticação com o Confluent:** OAuth/OIDC com o emissor do EKS, confirmado. API keys no Secrets Manager como alternativa.
8. **Confluent:** Dedicated com PrivateLink em produção. Homologação em Enterprise ou Standard, sem PrivateLink.
9. **Volumes e SLA:** volume médio de 8.000 TPS, com picos de até 25.000 TPS. Gerar alertas em no máximo 500 ms após o recebimento da transação.

## 5. Pendências

1. **Sistema chamador upstream:** confirmar que ele apresenta certificado de cliente ao ALB e que valida o `customerId` antes de enviar a transação.
2. **Autenticação com o Confluent:** validar o fluxo OIDC no spike. Se falhar, usar API keys no Secrets Manager.
10. **Critério do 500 ms:** confirmado como máximo absoluto, não percentil. Exige margem maior no orçamento de latência. A discussão da alocação por etapa fica para o spike.
11. **Alertas operacionais:** limites e destinatários do alerta de monitoramento, a definir pelo time.
3. **Confluent Dedicated:** confirmar disponibilidade de PrivateLink e de Dedicated em sa-east-1, limite de partições e custo.
4. **Residência de dados:** confirmar os requisitos de LGPD (`architecture.md` §9) para dados em sa-east-1 e que o Confluent oferece cluster nessa região.
5. **Custo:** estimar o Confluent Dedicated (capacidade para 25.000 TPS de pico), PrivateLink, NAT e transferência para 8.000 TPS médios e 25.000 TPS de pico.
6. **Resultado do spike test:** define dimensionamento, necessidade de Redis ou Aurora e necessidade de RDS Proxy.
7. **Requisitos de LGPD e segurança** (`roadmap-execucao.md`, seção 1).
8. **Provedor e SLA de notificação externa** (define o egress da seção 2.2).
