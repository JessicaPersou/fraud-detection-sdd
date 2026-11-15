# ADR 0006 — Moedas suportadas e precisão monetária

**Status:** Aceita  
**Data:** 2026-11-15  
**Resolve:** ADR 0001 §5, item 5  
**Relaciona-se com:** ADR 0001, decisão 3

## Contexto

`amountInCents` pressupõe duas casas decimais. Moedas diferentes têm expoentes diferentes (ex.: JPY tem zero casas decimais; alguns códigos usam três). Sem uma regra explícita, o mesmo campo teria significados diferentes conforme a moeda.

## Decisão

- Na v1, somente **BRL** é aceita, com expoente 2 (ISO 4217).
- A lista de moedas aceitas é configuração (`transaction.supported-currencies: BRL`).
- Valores são inteiros positivos em menor unidade (centavos).
- O expoente da moeda é obtido de uma tabela de referência ISO 4217 mantida no módulo de domínio. Não é um valor digitado no código de regra.
- Moeda fora da lista retorna `422`.

## Justificativa

- Restringir o escopo evita tomar decisões de câmbio, arredondamento e expoente sem validação de negócio (ADR 0001 e regra de não inventar regras de negócio).
- Tabela ISO 4217 mantém a conversão correta quando novas moedas forem habilitadas.

## Riscos

| Risco | Como medir | Mitigação |
|---|---|---|
| Moeda não suportada chegando ao sistema | Contagem de respostas `422` por moeda | Lista de moedas configurável |
| Erro de conversão de expoente | Teste unitário com moedas de expoentes 0, 2 e 3 | Testes parametrizados na tabela de referência |

## Evolução

Habilitar novas moedas via configuração e teste, mediante validação de negócio. Mudança de representação (ex.: valor decimal como string) exige nova ADR e versão do contrato.
