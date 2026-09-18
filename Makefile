# Variáveis
JC = javac
JFLAGS = -g

# Regra principal: compila tudo
all: compile

# Compila os arquivos .java dentro de suas respectivas pastas src
compile:
	$(JC) $(JFLAGS) servidor/src/Servidor.java
	$(JC) $(JFLAGS) cliente/src/Cliente.java

# Limpa apenas os binários compilados (.class) nas duas pastas
clean:
	rm -f servidor/src/*.class
	rm -f cliente/src/*.class

# Limpa os binários E reseta todo o ambiente (apaga as pastas de teste e logs)
reset: clean
	rm -rf servidor/src/pasta_servidor
	rm -rf cliente/src/*/
	@echo "Ambiente resetado. Todas as pastas de sincronização e logs foram apagadas."

# Regra para rodar o servidor
run-server: compile
	cd servidor/src && java Servidor

# Regra para rodar o cliente com argumentos dinâmicos
# O word 2 e 3 capturam exatamente o que você digitar após "make run-client"
run-client: compile
	cd cliente/src && java Cliente $(word 2, $(MAKECMDGOALS)) $(word 3, $(MAKECMDGOALS))

# Regra fantasma "pega-tudo": impede que o make dê erro ao ler os argumentos do cliente
%:
	@: