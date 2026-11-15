# ADR 0007 — Stack e ferramentas de desenvolvimento

**Status:** Aceita  
**Data:** 2026-11-15  
**Relaciona-se com:** ADR 0001 (estrutura do repositório), `docs/implementation-plan.md` (Fase 1)

## Decisão

| Item | Versão / escolha | Observação |
|---|---|---|
| Java | 21 (Temurin) | LTS, conforme `project-overview.md` |
| Spring Boot | 4.1.2 | Última versão estável no momento da decisão |
| Build | Maven 3.9.x, multi-módulo | Decisão da ADR 0001 |
| Mensageria | Apache Kafka 3.9 em modo KRaft | Sem ZooKeeper |
| Banco | MySQL 8.4 LTS | Conforme `project-overview.md` |
| Infra local | Docker Compose v2 | Kafka, MySQL e tópicos criados por serviço de inicialização |
| CI | GitHub Actions: `mvn verify` | Build e testes em cada push |
| Testes | JUnit 5, Spring Boot Test | Unitários, integração (Testcontainers na Fase 2) |

## Justificativa

- Versões LTS ou estáveis reduzem risco operacional.
- KRaft elimina o ZooKeeper, simplificando a infraestrutura local.
- Spring Boot 4 é a linha atual; a migração a partir de 3.x não é necessária porque o projeto começa do zero.

## Evolução

Atualizar versões em PRs dedicados, com a suíte de testes passando. Mudanças de major version do Spring Boot ou do Kafka exigem nota de revisão neste ADR.
