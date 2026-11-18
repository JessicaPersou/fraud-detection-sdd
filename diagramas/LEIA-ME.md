# Diagramas

| Arquivo | Conteúdo |
|---|---|
| `arquitetura-simples.drawio` | Execução local: três serviços como processos Java, Kafka, MySQL e Mailpit no Docker Compose |
| `arquitetura-aws.drawio` | Arquitetura alvo na AWS (ADR 0008): WAF, ALB, EKS, RDS Proxy, RDS MySQL Multi-AZ, Confluent via PrivateLink, NAT e provedor externo |
| `erd-transaction-db.drawio` | Modelo de dados do `transaction_db` |
| `erd-fraud-db.drawio` | Modelo de dados do `fraud_db` |
| `erd-notification-db.drawio` | Modelo de dados do `notification_db` |
| `ddl/*.sql` | DDL consolidado de cada banco, gerado a partir das migrações Flyway |

Os modelos de dados foram gerados a partir dos DDLs consolidados. Se uma migração mudar, gere de novo os diagramas antes de revisar.

## Como abrir no Lucid

Os arquivos estão no formato draw.io (mxfile). Para importar no Lucid, use o recurso de importação do Lucid, se a sua conta oferecer importação de draw.io. Se não oferecer, abra o arquivo em https://app.diagrams.net, confira o desenho e refaça no Lucid a partir dele. As setas tracejadas são relações lógicas sem chave estrangeira no banco, de propósito: cada serviço é dono dos próprios dados.

Os desenhos são um rascunho de layout. Os nomes e as relações seguem o código e os ADRs, mas o posicionamento foi feito por coordenadas e precisa de ajuste visual.
