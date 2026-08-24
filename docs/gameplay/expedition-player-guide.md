# Guia do Jogador — Expedições (BigBangCraft)

> Versão curta: a expedição é um território urbano temporário. Entre, saque,
> sobreviva e volte. Quando fecha, TUDO que ficou para trás se perde.

## O que é a expedição?

Sobreviventes deixam os assentamentos persistentes e entram numa zona urbana
interditada em busca de suprimentos, equipamentos e oportunidades. A zona é
**temporária**: depois de um ciclo ela é isolada e reaberta renovada, com loot
novamente disponível — mas tudo que os jogadores deixaram lá desaparece.

## Como entrar

```
/expedition status     → mostra se a expedição está ABERTA
/expedition enter      → teleporta você para a zona
```

Entrada permitida **apenas com a expedição ABERTA**. Durante manutenção ou
interdição o acesso é negado (portais e teleportes alternativos também são
bloqueados).

Ao entrar você recebe avisos importantes:

* **nada do que construir ou guardar sobrevive** ao fim da zona;
* itens deixados no seu corpo são perdidos quando a zona fecha;
* o território não pode ser reivindicado (claims desativados).

## Dentro da zona

* Construção temporária é permitida (abrigo, fogueira, bancadas) — mas é
  descartável por definição.
* Indústria permanente (Create/IE/RS) não tem propósito aqui: será destruída.
* Veículos estacionados na zona são perdidos no fechamento.
* Mochilas e itens no inventário voltam com você normalmente.

## Como sair

```
/expedition leave      → retorna ao seu ponto de retorno
/expedition where      → mostra o distrito e suas coordenadas
```

Se o ponto de retorno estiver inválido, você vai para o abrigo central do
mundo persistente. Você nunca fica preso.

## Morte

Consequências hardcore normais: itens ficam no local da morte (Corpse mod).
Dormir na zona pula a noite, mas a zona **nunca vira ponto de renascimento** —
você sempre acorda no mundo persistente.

## Fechamento

Quando uma interdição é ordenada há avisos escalonados no chat/barra de ação:

```
☢ INTERDIÇÃO DA EXPEDIÇÃO EM 15 MINUTOS...
☢ INTERDIÇÃO DA EXPEDIÇÃO EM 5 MINUTOS...
☢ INTERDIÇÃO DA EXPEDIÇÃO EM 1 MINUTO.
☢ Expedição interditada. Todos os sobreviventes foram extraídos.
```

No prazo final todos ainda dentro são extraídos automaticamente para o mundo
persistente. Desconectar não protege: ao voltar durante a interdição você já
estará em segurança no mundo persistente.

## Reabertura

```
☢ NOVA ZONA DE EXPEDIÇÃO DISPONÍVEL
Reconhecimento confirmou novas rotas pela zona urbana... [Zona nº X]
```

Cada reabertura é um novo ciclo (nº da zona), com prédios e baús renovados.
