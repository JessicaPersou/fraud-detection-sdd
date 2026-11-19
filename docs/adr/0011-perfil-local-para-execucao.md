# ADR 0011 — Perfil `local` para execução com valores de demonstração

**Status:** Aceita  
**Data:** 2026-11-18  
**Relaciona-se com:** ADR 0001 (decisão 10), ADR 0010 (canal de notificação), `business-rules.md` (seção 6)

## 1. Contexto

O ADR 0001, decisão 10, externalizou os limites de negócio (BR-02, BR-03 e BR-04) e o destinatário das notificações em configuração, sem valor padrão no código. Isso é correto para ambientes reais, mas tornou a execução local trabalhosa. Para subir Fraud Detection e Notification, era preciso exportar cinco ou seis variáveis em cada terminal, e a aplicação não iniciava se alguma faltasse. Os valores de demonstração ficavam só no README, sem vínculo com a configuração.

Os valores de negócio ainda não foram validados (`business-rules.md`, seção 6), então qualquer valor escolhido é de demonstração.

## 2. Decisão

1. Cada serviço que tem variáveis obrigatórias (Fraud Detection e Notification) ganha um arquivo `application-local.yml` com os valores de demonstração.
2. O `application.yml` de cada um desses serviços ativa o perfil `local` com `spring.profiles.active: local`.
3. Fora do perfil `local`, as variáveis continuam obrigatórias e a aplicação não sobe sem elas. Para outros ambientes, o perfil é trocado por `SPRING_PROFILES_ACTIVE=prod` (ou outro valor diferente de `local`).
4. Variáveis de ambiente têm precedência sobre o arquivo do perfil, então sobrescrevem um valor de demonstração sem editar arquivos.
5. O canal do perfil `local` é `smtp`, que envia para o Mailpit do docker-compose (ADR 0010).
6. Os testes automatizados carregam o `application.yml` e, portanto, também ativam o perfil `local`. Por isso, cada teste que depende de um valor do perfil o fixa explicitamente: os testes de fraud fixam os limites, e os testes de notificação e o e2e fixam `notification.provider=simulated`.

## 3. Alternativas consideradas

- **Constantes em uma classe Java como valores padrão.** Rejeitada. Colocaria os limites de negócio no código de produção, o que o ADR 0001, decisão 10, quis evitar. Também tornaria impossível saber, sem olhar o código, se um ambiente está usando um valor padrão.
- **Tabela no banco de dados.** Adiada. Dá histórico e troca sem redeploy, mas cria uma nova fonte de verdade, exige ferramenta de administração e aumenta o risco de divergência entre ambientes (R7). Faz sentido quando a área de negócio precisar alterar os limites em produção, depois da validação.
- **Redis para os valores de configuração.** Rejeitada. São poucos valores que mudam raramente. Redis adicionaria uma dependência e um ponto de falha na inicialização. Os ADR 0004 e 0005 só admitem Redis após um gargalo medido.

## 4. Consequências

- **Positivas:** a execução local sobe com `java -jar` sem configuração manual. Os valores de demonstração ficam versionados junto do código de configuração, com comentário de que não são de negócio.
- **Negativas:** o padrão do repositório deixa de exigir configuração. Um ambiente que ative `local` por engano sobe com valores de demonstração. A proteção é a exigência de `SPRING_PROFILES_ACTIVE=prod` em qualquer ambiente real, que deve ser cobrada na implantação. Essa exigência ainda não é verificada por teste automatizado.
- **Impacto nos testes:** nenhum teste depende da ausência das variáveis. Mas o perfil `local` é carregado pelos testes, então qualquer valor do perfil que um teste não fixe passa a valer nele. Sem essa fixação, o provedor `smtp` faria os testes de notificação e o e2e falharem, porque não há Mailpit no ambiente de teste. A suíte ainda não foi executada depois dessa mudança, então isso é uma análise do código, não um resultado de teste. Esse é o ponto que mais precisa de atenção em mudanças futuras no perfil.

## 5. Evolução

- Verificar por teste que, com o perfil `prod`, a aplicação falha na inicialização sem as variáveis obrigatórias. Hoje isso é garantido pela configuração, mas não por teste.
- Revisar a decisão se a área de negócio pedir alteração de limites em tempo de execução. Nesse caso, a alternativa de tabela (seção 3) passa a ser avaliada, com um novo ADR.
