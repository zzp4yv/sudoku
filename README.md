# Storyboard da Palestra

Aplicação web em Java com Spring Boot para transcrição contínua ao vivo durante palestras,
com geração automática de um **storyboard visual** (cards com título, emoji e resumo) via IA,
criado a cada pausa natural da fala. Pensada para rodar em um notebook conectado a um projetor
durante a palestra.

## Como funciona

- A transcrição de fala roda inteiramente no navegador, usando a **Web Speech API**.
- Sempre que o(a) palestrante faz uma pausa (~2,5s de silêncio) ou o trecho acumulado fica
  muito longo, o trecho transcrito é enviado ao backend, que chama a **API de Chat Completions
  da OpenAI** para gerar um card de storyboard: um emoji, um título curto e um resumo de 1-2
  frases em português.
- Os cards vão se acumulando em uma grade visual (o "storyboard") enquanto a transcrição
  completa fica disponível em um painel ao lado, sempre rolando para o trecho mais recente.

## Requisitos

- Java 17 (o Maven em si **não** precisa estar instalado — o projeto já inclui o Maven
  Wrapper, `./mvnw` / `mvnw.cmd`, que baixa e usa a versão correta do Maven sozinho)
- Um navegador baseado em Chromium — **Google Chrome** ou **Microsoft Edge** — pois a Web
  Speech API (reconhecimento de fala) não é suportada em todos os navegadores (por exemplo,
  Firefox não suporta).
- Uma variável de ambiente `OPENAI_API_KEY` com uma chave válida da API da OpenAI (gerada em
  [platform.openai.com/api-keys](https://platform.openai.com/api-keys)).
- Uma interface de áudio USB (opcional, mas recomendada para captar o áudio da palestra)
  configurada como o **dispositivo de entrada (microfone) padrão do sistema operacional**.
  O navegador captura automaticamente o microfone padrão do sistema — não é necessário
  selecionar o dispositivo dentro da aplicação.

## Como executar

```bash
export OPENAI_API_KEY=sk-...
./mvnw spring-boot:run
```

No Windows (PowerShell/cmd): `mvnw.cmd spring-boot:run`.

Se preferir usar um Maven já instalado no seu sistema, `mvn spring-boot:run` também funciona.

Depois, acesse [http://localhost:8080](http://localhost:8080).

## Como usar durante a palestra

1. Conecte a interface de áudio USB e configure-a como o dispositivo de entrada padrão do
   sistema operacional.
2. Abra a página no navegador (Chrome ou Edge) no computador que estará conectado ao
   projetor.
3. Clique em **▶ Iniciar** uma única vez, no começo da palestra.
4. Deixe rodando: a transcrição continua automaticamente durante toda a palestra, mesmo que
   o navegador reinicie a sessão de reconhecimento de fala internamente (isso é tratado de
   forma transparente pela aplicação).
5. Ao final, clique em **■ Encerrar**. Qualquer trecho pendente ainda é resumido antes de
   parar. Para uma nova palestra, clique em **▶ Iniciar** novamente — a transcrição e o
   storyboard anteriores são limpos automaticamente.

## Tecnologias

- Spring Boot 3 (Java 17)
- HTML/CSS/JS estático (sem framework de frontend), servido diretamente pelo Spring Boot
- Web Speech API (reconhecimento de fala no navegador)
- API de Chat Completions da OpenAI, modelo `gpt-4o-mini` (geração dos resumos do storyboard)

## Projeto legado

A aplicação anterior deste repositório, **Cadastro de Pessoas Jurídicas**, foi movida para
[`legacy/cadastro-pj/`](legacy/cadastro-pj) e continua sendo um projeto Maven independente,
buildável e executável a partir de lá:

```bash
cd legacy/cadastro-pj
./mvnw spring-boot:run
```
