import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class Servidor {
    private static CopyOnWriteArrayList<DataOutputStream> saidas = new CopyOnWriteArrayList<>();
    private static Set<String> arquivosIgnorados = ConcurrentHashMap.newKeySet();

    // Grava os detalhes no arquivo dentro da pasta visível logs_servidor
    private static synchronized void registrarLog(String operacao, String descricao, String origem, String destino) {
        String dataHora = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
        String linhaLog = String.format("[%s] [%s] %s | [%s] -> [%s]", dataHora, operacao, descricao, origem, destino);

        // Cria a pasta visível dentro da pasta do servidor se ela não existir
        File pastaLogs = new File("pasta_servidor/logs_servidor");
        if (!pastaLogs.exists()) {
            pastaLogs.mkdirs();
        }

        File arquivoLog = new File(pastaLogs, "registro.log");

        try (FileWriter fw = new FileWriter(arquivoLog, true);
                BufferedWriter bw = new BufferedWriter(fw);
                PrintWriter pw = new PrintWriter(bw)) {
            pw.println(linhaLog);
        } catch (IOException e) {
            System.out.println("Erro ao salvar log: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        // Cria o socket do servidor
        ServerSocket serverSocket = new ServerSocket(8080);

        // Criação das pastas
        File pastaServ = new File("pasta_servidor");
        File pastaLogs = new File("pasta_servidor/logs_servidor");
        pastaServ.mkdirs();
        pastaLogs.mkdirs();

        System.out.println("Servidor de espelhamento rodando na porta 8080...");
        System.out.println("Os logs estão sendo salvos em: " + pastaLogs.getAbsolutePath() + "/registro.log");
        System.out.println("------------------------------------------------------");

        registrarLog("SISTEMA", "Servidor de espelhamento iniciado na porta 8080", "Localhost", "N/A");

        // Cria uma thread para monitorar as alterações na pasta do servidor
        new Thread(() -> {
            try {
                WatchService watchService = FileSystems.getDefault().newWatchService();
                Paths.get("pasta_servidor").register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);

                // Loop principal para monitorar as alterações na pasta do servidor
                while (true) {
                    WatchKey key = watchService.take();
                    Thread.sleep(400);

                    Set<String> nomes = new HashSet<>();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        String nomeArquivo = event.context().toString();

                        // Ignora arquivos ocultos, temporários e a nossa pasta de logs!
                        if (nomeArquivo.startsWith(".") || nomeArquivo.endsWith("~")
                                || nomeArquivo.equals("logs_servidor"))
                            continue;
                        if (arquivosIgnorados.contains(nomeArquivo))
                            continue;
                        nomes.add(nomeArquivo);
                    }
                    key.reset();

                    // Para cada arquivo alterado, envia a operação para os clientes
                    for (String nomeArquivo : nomes) {
                        File arquivoDetectado = new File("pasta_servidor/" + nomeArquivo);

                        // Se o arquivo for um arquivo, envia a operação SYNC para os clientes
                        if (arquivoDetectado.isFile()) {
                            byte[] dados = Files.readAllBytes(arquivoDetectado.toPath());
                            System.out.println("[Monitor] Adição/Edição manual detectada: '" + nomeArquivo + "'");
                            registrarLog("SYNC_LOCAL", "Adição/Edição manual de '" + nomeArquivo + "'",
                                    "Servidor Central", "Todos os Clientes");

                            for (DataOutputStream saida : saidas) {
                                synchronized (saida) {
                                    try {
                                        saida.writeUTF("SYNC");
                                        saida.writeUTF("Servidor Central");
                                        saida.writeUTF(nomeArquivo);
                                        saida.writeLong(dados.length);
                                        saida.write(dados);
                                    } catch (IOException e) {
                                    }
                                }
                            }
                            // Se o arquivo não for um arquivo, envia a operação DELETE para os clientes
                        } else if (!arquivoDetectado.exists()) {
                            System.out.println("[Monitor] Exclusão manual detectada: '" + nomeArquivo + "'");
                            registrarLog("DELETE_LOCAL", "Exclusão manual de '" + nomeArquivo + "'", "Servidor Central",
                                    "Todos os Clientes");

                            // Para cada cliente conectado, envia a operação DELETE para o cliente
                            for (DataOutputStream saida : saidas) {
                                synchronized (saida) {
                                    try {
                                        saida.writeUTF("DELETE");
                                        saida.writeUTF("Servidor Central");
                                        saida.writeUTF(nomeArquivo);
                                    } catch (IOException e) {
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("Erro no monitoramento da pasta do servidor.");
                registrarLog("ERRO", "Falha no monitoramento da pasta do servidor", "Servidor", "N/A");
            }
        }).start();

        // Loop principal para aceitar novas conexões de clientes
        while (true) {
            Socket socket = serverSocket.accept();
            System.out.println("Novo cliente conectado!");
            registrarLog("CONEXÃO", "Novo cliente conectado ao socket", "Cliente", "Servidor");
            new Thread(new ManipuladorCliente(socket)).start();
        }
    }

    // Classe interna para manipular as conexões dos clientes
    private static class ManipuladorCliente implements Runnable {
        private Socket socket;
        private DataOutputStream saida;
        private DataInputStream entrada;

        public ManipuladorCliente(Socket socket) {
            this.socket = socket;
        }

        public void run() {
            try {
                entrada = new DataInputStream(socket.getInputStream());
                saida = new DataOutputStream(socket.getOutputStream());
                saidas.add(saida);

                File pastaServ = new File("pasta_servidor");
                File[] arqs = pastaServ.listFiles();
                if (arqs != null) {
                    for (File f : arqs) {
                        // Como adicionamos f.isFile(), ele ignora pastas no Handshake (como a
                        // logs_servidor)
                        if (f.isFile() && !f.getName().startsWith(".") && !f.getName().endsWith("~")) {
                            byte[] dados = Files.readAllBytes(f.toPath());
                            synchronized (saida) {
                                registrarLog("HANDSHAKE", "Enviando '" + f.getName() + "' para cliente recém-conectado",
                                        "Servidor", "Novo Cliente");
                                saida.writeUTF("SYNC");
                                saida.writeUTF("Servidor (Sync Inicial)");
                                saida.writeUTF(f.getName());
                                saida.writeLong(dados.length);
                                saida.write(dados);
                            }
                        }
                    }
                }
                synchronized (saida) {
                    registrarLog("HANDSHAKE", "Fim do envio inicial para cliente recém-conectado", "Servidor",
                            "Novo Cliente");
                    saida.writeUTF("HANDSHAKE_FIM");
                    saida.writeUTF("");
                    saida.writeUTF("");
                }

                while (true) {
                    String operacao = entrada.readUTF();
                    String remetente = entrada.readUTF();
                    String nomeArquivo = entrada.readUTF();

                    if (operacao.equals("HANDSHAKE_FIM"))
                        continue;

                    File arquivoDestino = new File("pasta_servidor/" + nomeArquivo);

                    if (operacao.equals("SYNC")) {
                        long tamanhoArquivo = entrada.readLong();
                        if (tamanhoArquivo < 0 || tamanhoArquivo > Integer.MAX_VALUE) {
                            throw new IOException("Tamanho de arquivo inválido: " + tamanhoArquivo);
                        }
                        byte[] dados = new byte[(int) tamanhoArquivo];
                        entrada.readFully(dados);
                        System.out.println("[SYNC] Recebendo '" + nomeArquivo + "' de " + remetente);
                        registrarLog("SYNC", "Recebido arquivo '" + nomeArquivo + "' (" + dados.length + " bytes)",
                                remetente, "Servidor");

                        arquivosIgnorados.add(nomeArquivo);
                        FileOutputStream fos = new FileOutputStream(arquivoDestino);
                        fos.write(dados);
                        fos.close();
                        Thread.sleep(500);
                        arquivosIgnorados.remove(nomeArquivo);

                        registrarLog("BROADCAST", "Repassando '" + nomeArquivo + "'", "Servidor", "Outros Clientes");
                        for (DataOutputStream escritor : saidas) {
                            if (escritor != saida) {
                                synchronized (escritor) {
                                    escritor.writeUTF("SYNC");
                                    escritor.writeUTF(remetente);
                                    escritor.writeUTF(nomeArquivo);
                                    escritor.writeLong(dados.length);
                                    escritor.write(dados);
                                }
                            }
                        }
                    } else if (operacao.equals("DELETE")) {
                        System.out.println("[DELETE] Removendo '" + nomeArquivo + "' a pedido de " + remetente);
                        registrarLog("DELETE", "Removendo '" + nomeArquivo + "'", remetente, "Servidor");

                        arquivosIgnorados.add(nomeArquivo);
                        if (arquivoDestino.exists()) {
                            arquivoDestino.delete();
                        }
                        Thread.sleep(500);
                        arquivosIgnorados.remove(nomeArquivo);

                        registrarLog("BROADCAST", "Repassando ordem de exclusão de '" + nomeArquivo + "'", "Servidor",
                                "Outros Clientes");
                        for (DataOutputStream escritor : saidas) {
                            if (escritor != saida) {
                                synchronized (escritor) {
                                    escritor.writeUTF("DELETE");
                                    escritor.writeUTF(remetente);
                                    escritor.writeUTF(nomeArquivo);
                                }
                            }
                        }
                    }
                }
            } catch (EOFException e) {
            } catch (IOException | InterruptedException e) {
                System.out.println("A conexão com um cliente foi perdida.");
                registrarLog("DESCONEXÃO", "Conexão perdida com um cliente", "Cliente", "Servidor");
            } finally {
                saidas.remove(saida);
                try {
                    socket.close();
                } catch (IOException e) {
                }
            }
        }
    }
}
