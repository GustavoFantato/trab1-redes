# Sistema Distribuído de Sincronização de Arquivos

Este projeto consiste em um sistema cliente-servidor para sincronização de arquivos em tempo real. A aplicação atua de forma semelhante a serviços de armazenamento em nuvem, garantindo que os arquivos inseridos, editados ou excluídos na pasta de um usuário sejam refletidos instantaneamente no servidor e repassados a todos os outros usuários conectados.

A base do sistema foi desenvolvida inteiramente utilizando a linguagem Java, empregando conceitos de redes (Sockets) e concorrência para garantir a comunicação simultânea entre múltiplas máquinas.

## Funcionalidades Implementadas

* **Sincronização em Tempo Real (Criação e Edição):** Qualquer arquivo novo inserido em uma pasta monitorada, ou qualquer edição realizada em um arquivo existente, aciona automaticamente um evento de *Upload* ou *Download* (SYNC) para atualizar os demais diretórios.
* **Exclusão Sincronizada (DELETE):** Quando um arquivo é apagado por um dos clientes, o sistema propaga a ordem de exclusão, removendo o respectivo arquivo no servidor central e, em seguida, nas pastas de todos os outros clientes.
* **Sincronização Inicial (Handshake / SyncStart):** No momento em que um novo cliente se conecta ao servidor, ocorre uma troca inicial de dados. O servidor envia todos os arquivos pré-existentes da rede para o novo cliente, e o novo cliente envia quaisquer arquivos que já possuísse localmente para o servidor.
* **Geração de Logs Isolados:** O servidor possui um sistema de auditoria silencioso. Ele cria uma pasta visível chamada `logs_servidor` e registra todo o tráfego da rede, conexões e detalhamento de bytes em um arquivo de texto. Esses logs são ignorados pelo processo de espelhamento e não são enviados aos clientes.
* **Filtro de Arquivos Temporários:** O sistema identifica e ignora automaticamente arquivos ocultos e arquivos temporários de backup gerados por editores de texto ou sistemas operacionais.
* **Escalabilidade Dinâmica:** O sistema suporta um número indefinido de clientes. Não é necessário criar pastas manualmente; o próprio programa se encarrega de criar os diretórios necessários no momento da execução.

---

## Preparação do Ambiente (Pré-requisitos)

Para executar o sistema, é necessário possuir o **Java Development Kit (JDK) versão 8 ou superior** instalado na máquina.
Para ambientes baseados em Unix (Linux/MacOS), recomenda-se também a instalação da ferramenta **Make** para automação da execução.

**Aviso:** Caso o seu computador já possua o Java e o Make instalados, avance diretamente para a seção **"Como Executar o Sistema"**.

### Instalação do Java e Make no Linux (Ubuntu/Debian)
1. Abra o terminal.
2. Atualize a lista de pacotes do sistema:
   `sudo apt update`
3. Instale o pacote padrão do JDK:
   `sudo apt install default-jdk`
4. Instale a ferramenta Make:
   `sudo apt install make`

### Instalação do Java e Make no MacOS
1. Abra o terminal.
2. Instale as ferramentas de linha de comando da Apple (que já incluem o Make):
   `xcode-select --install`
3. Instale o Java via Homebrew:
   `brew install java`

### Instalação do Java no Windows
*Nota: A ferramenta Make não é nativa no Windows. Portanto, configure apenas o Java.*
1. Acesse o site oficial da Oracle ou a plataforma Adoptium (Eclipse Temurin) para baixar o instalador do JDK.
2. Execute o arquivo `.exe` baixado e siga o assistente de instalação utilizando as configurações padrão.
3. **Configuração das Variáveis de Ambiente:**
   * Abra o menu Iniciar, pesquise por "Variáveis de Ambiente" e selecione "Editar as variáveis de ambiente do sistema".
   * Na seção "Variáveis do sistema", encontre e selecione a variável `Path`, e clique em "Editar...".
   * Clique em "Novo" e cole o caminho para a pasta `bin` onde o Java foi instalado (Geralmente em: `C:\Program Files\Java\jdk-XX\bin`). Salve as alterações.

---

## Como Executar o Sistema (Linux / MacOS)

O projeto acompanha um `Makefile` na sua raiz que automatiza a navegação entre as pastas (`servidor/src` e `cliente/src`) e a compilação do código.

Siga os passos abaixo em terminais separados, todos abertos na **pasta raiz** do projeto:

### Passo 1: Inicialização do Servidor Central
No primeiro terminal, inicie o servidor. Ele compilará os arquivos automaticamente, passará a escutar conexões e criará as pastas necessárias.
```bash
make run-server
```

### Passo 2: Inicialização dos Clientes
Abra novos terminais na raiz do projeto (um para cada cliente). O comando para conectar um cliente exige o nome da pasta local a ser criada/monitorada e o identificador do usuário. O endereço do servidor é opcional: se for omitido, o cliente conecta em `127.0.0.1` (a própria máquina).

**Terminal do Cliente 1:**
```bash
make run-client pasta_cliente_1 Alice
```

**Terminal do Cliente 2:**
```bash
make run-client pasta_cliente_2 Bob
```

*Nota: As pastas dos clientes serão criadas automaticamente pelo código caso não estejam previamente criadas.*

---

## Limitações de rede

O cliente conecta direto no IP privado do servidor, na porta **8080**. Por isso todas as máquinas precisam estar no **mesmo Wi-Fi**.

* Um hotspot de celular compartilhado pelas máquinas também funciona, porque elas entram na mesma rede.
* A eduroam da USP isola um computador do outro. Os IPs podem estar na mesma faixa e, ainda assim, o ping e a conexão falham.
* Cada pessoa no próprio 4G, ou em hotspots diferentes, também falha. O IP do servidor só existe dentro da rede dele, e o 4G não aceita conexão vinda de fora.
* Ao trocar de Wi-Fi, o IP do servidor muda. É preciso encerrar o servidor, subir de novo e usar o endereço novo.

## Executando em Máquinas Distintas

### 1. Coloque as máquinas no mesmo Wi-Fi
Conecte o computador do servidor e o do cliente na mesma rede. Confira, em cada um, que o IPv4 é daquela rede:

```bash
ip -4 addr
```

### 2. Inicie o servidor
Na máquina do servidor, na raiz do projeto:

```bash
make run-server
```

Anote o endereço da linha `Clientes em outras máquinas devem usar`. Use o IP do Wi-Fi. Se o firewall estiver ativo, libere a porta:

```bash
sudo ufw allow 8080/tcp
```

### 3. Teste o caminho até o servidor
Na máquina do cliente, troque o IP pelo endereço anotado:

```bash
ping -c 3 192.168.0.10
nc -vz 192.168.0.10 8080
```

O ping precisa responder e o `nc` precisa indicar que a porta 8080 está aberta. Se o ping falhar, as máquinas não estão se enxergando e o cliente Java também não vai conectar.

### 4. Inicie o cliente
Ainda na outra máquina, na raiz do projeto, passe a pasta local, o identificador e o IP do servidor:

```bash
make run-client pasta_cliente_1 Alice 192.168.0.10
```

A pasta do cliente é criada automaticamente. Cada cliente usa a própria pasta e o próprio identificador.

---

## Como Executar o Sistema (Fluxo Manual - Windows)

Caso esteja no Windows (ou não deseje usar o Make), é necessário compilar e executar os arquivos entrando nas suas respectivas pastas manualmente.

**Terminal 1 (Servidor):**
```bash
cd servidor/src
javac Servidor.java
java Servidor
```

**Terminal 2 (Cliente 1):**
```bash
cd cliente/src
javac Cliente.java
java Cliente pasta_cliente_1 Alice
```

Em outra máquina, informe o IP do servidor:

```bash
cd cliente/src
javac Cliente.java
java Cliente pasta_cliente_1 Alice 192.168.0.10
```

---

## Adicionando Novos Clientes
O projeto permite a conexão de quantos clientes o usuário desejar simultaneamente. Para adicionar um terceiro, quarto ou quinto usuário à rede, basta abrir um novo terminal e executar o comando informando um novo nome de pasta e um novo identificador. Exemplo:
```bash
make run-client pasta_cliente_3 Carlos
```

Em outra máquina, inclua o IP do servidor:

```bash
make run-client pasta_cliente_3 Carlos 192.168.0.10
```
Os arquivos já presentes na rede serão imediatamente baixados para esta nova pasta através da rotina de Sincronização Inicial (SyncStart).

## Limpeza do Ambiente
Caso deseje interromper os testes e resetar completamente o ambiente para uma nova demonstração do zero (removendo arquivos compilados e as pastas sincronizadas geradas):

**Via Makefile (Linux/MacOS):**
```bash
make reset
```

**Manualmente (Windows):**
1. Encerre os processos nos terminais pressionando `Ctrl + C`.
2. Navegue até `servidor/src` e apague manualmente a pasta `pasta_servidor` e os arquivos `.class`.
3. Navegue até `cliente/src` e apague manualmente as pastas dos clientes (ex: `pasta_cliente_1`) e os arquivos `.class`.


**Integrantes do grupo:**
1. Aron Costa da Silva Araújo
2. Gustavo Fantato Fernandes 
3. Renan Silva Blasques 
4. Victor Kayky Zaneti Antunes
