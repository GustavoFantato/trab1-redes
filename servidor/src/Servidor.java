import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class Servidor {
    private static CopyOnWriteArrayList<DataOutputStream> escritores = new CopyOnWriteArrayList<>();
    private static Set<String> arquivosIgnorados = ConcurrentHashMap.newKeySet();
    private static ConcurrentHashMap<String, Long> ultimoEnvio = new ConcurrentHashMap<>();

    // Grava os detalhes silenciosamente no arquivo dentro da pasta visível logs_servidor
    private static synchronized void registrarLog(String operacao, String descricao, String origem, String destino) {
        String dataHora = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
        String linhaLog = String.format("[%s] [%s] %s | [%s] -> [%s]", dataHora, operacao, descricao, origem, destino);

        // Cria a pasta visível dentro da pasta do servidor
        File pastaLogs = new File("pasta_servidor/logs_servidor");
        if (!pastaLogs.exists()) {
            pastaLogs.mkdirs();
        }

        File arquivoLog = new File(pastaLogs, "registro.log");

        try (FileWriter fw = new FileWriter(arquivoLog, true);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter out = new PrintWriter(bw)) {
            out.println(linhaLog);
        } catch (IOException e) {
            System.out.println("Erro ao salvar log: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        ServerSocket serverSocket = new ServerSocket(8080);

        // Criação das pastas
        File pastaServ = new File("pasta_servidor");
        File pastaLogs = new File("pasta_servidor/logs_servidor");
        pastaServ.mkdirs();
        pastaLogs.mkdirs();

        System.out.println("Servidor de espelhamento rodando na porta 8080...");
        System.out.println("📁 Os logs estão sendo salvos em: " + pastaLogs.getAbsolutePath() + "/registro.log");
        System.out.println("------------------------------------------------------");

        registrarLog("SISTEMA", "Servidor de espelhamento iniciado na porta 8080", "Localhost", "N/A");

        new Thread(() -> {
            try {
                WatchService watchService = FileSystems.getDefault().newWatchService();
                Paths.get("pasta_servidor").register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);

                while (true) {
                    WatchKey key = watchService.take();
                    Thread.sleep(100);

                    for (WatchEvent<?> event : key.pollEvents()) {
                        String nomeArquivo = event.context().toString();

                        // Ignora arquivos ocultos, temporários e a nossa pasta de logs!
                        if (nomeArquivo.startsWith(".") || nomeArquivo.endsWith("~") || nomeArquivo.equals("logs_servidor")) continue;
                        if (arquivosIgnorados.contains(nomeArquivo)) continue;

                        long tempoAtual = System.currentTimeMillis();
                        long tempoAnterior = ultimoEnvio.getOrDefault(nomeArquivo, 0L);
                        if (tempoAtual - tempoAnterior < 1000) continue;

                        File arquivoDetectado = new File("pasta_servidor/" + nomeArquivo);

                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE || event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
                            if (arquivoDetectado.exists() && arquivoDetectado.isFile()) {
                                ultimoEnvio.put(nomeArquivo, tempoAtual);
                                System.out.println("[Monitor] Adição/Edição manual detectada: '" + nomeArquivo + "'");
                                registrarLog("SYNC_LOCAL", "Adição/Edição manual de '" + nomeArquivo + "'", "Servidor Central", "Todos os Clientes");

                                for (DataOutputStream escritor : escritores) {
                                    synchronized(escritor) {
                                        try {
                                            escritor.writeUTF("SYNC");
                                            escritor.writeUTF("Servidor Central");
                                            escritor.writeUTF(nomeArquivo);
                                            escritor.writeLong(arquivoDetectado.length());

                                            FileInputStream fis = new FileInputStream(arquivoDetectado);
                                            byte[] buffer = new byte[4096];
                                            int bytesLidos;
                                            while ((bytesLidos = fis.read(buffer)) != -1) {
                                                escritor.write(buffer, 0, bytesLidos);
                                            }
                                            fis.close();
                                        } catch (IOException e) {}
                                    }
                                }
                            }
                        } else if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                            ultimoEnvio.put(nomeArquivo, tempoAtual);
                            System.out.println("[Monitor] Exclusão manual detectada: '" + nomeArquivo + "'");
                            registrarLog("DELETE_LOCAL", "Exclusão manual de '" + nomeArquivo + "'", "Servidor Central", "Todos os Clientes");

                            for (DataOutputStream escritor : escritores) {
                                synchronized(escritor) {
                                    try {
                                        escritor.writeUTF("DELETE");
                                        escritor.writeUTF("Servidor Central");
                                        escritor.writeUTF(nomeArquivo);
                                    } catch (IOException e) {}
                                }
                            }
                        }
                    }
                    key.reset();
                }
            } catch (Exception e) {
                System.out.println("Erro no monitoramento da pasta do servidor.");
                registrarLog("ERRO", "Falha no monitoramento da pasta do servidor", "Servidor", "N/A");
            }
        }).start();

        while (true) {
            Socket socket = serverSocket.accept();
            System.out.println("Novo cliente conectado!");
            registrarLog("CONEXÃO", "Novo cliente conectado ao socket", "Cliente", "Servidor");
            new Thread(new ManipuladorCliente(socket)).start();
        }
    }

    private static class ManipuladorCliente implements Runnable {
        private Socket socket;
        private DataOutputStream out;
        private DataInputStream in;

        public ManipuladorCliente(Socket socket) { this.socket = socket; }

        public void run() {
            try {
                in = new DataInputStream(socket.getInputStream());
                out = new DataOutputStream(socket.getOutputStream());
                escritores.add(out);

                File pastaServ = new File("pasta_servidor");
                File[] arqs = pastaServ.listFiles();
                if (arqs != null) {
                    for (File f : arqs) {
                        // Como adicionamos f.isFile(), ele ignora pastas no Handshake (como a logs_servidor)
                        if (f.isFile() && !f.getName().startsWith(".") && !f.getName().endsWith("~")) {
                            synchronized(out) {
                                registrarLog("HANDSHAKE", "Enviando '" + f.getName() + "' para cliente recém-conectado", "Servidor", "Novo Cliente");
                                out.writeUTF("SYNC");
                                out.writeUTF("Servidor (Sync Inicial)");
                                out.writeUTF(f.getName());
                                out.writeLong(f.length());
                                FileInputStream fis = new FileInputStream(f);
                                byte[] buf = new byte[4096];
                                int lidos;
                                while ((lidos = fis.read(buf)) != -1) {
                                    out.write(buf, 0, lidos);
                                }
                                fis.close();
                            }
                        }
                    }
                }

                while (true) {
                    String operacao = in.readUTF();
                    String remetente = in.readUTF();
                    String nomeArquivo = in.readUTF();

                    File arquivoDestino = new File("pasta_servidor/" + nomeArquivo);

                    if (operacao.equals("SYNC")) {
                        long tamanhoArquivo = in.readLong();
                        System.out.println("[SYNC] Recebendo '" + nomeArquivo + "' de " + remetente);
                        registrarLog("SYNC", "Recebido arquivo '" + nomeArquivo + "' (" + tamanhoArquivo + " bytes)", remetente, "Servidor");

                        arquivosIgnorados.add(nomeArquivo);
                        FileOutputStream fos = new FileOutputStream(arquivoDestino);
                        byte[] buffer = new byte[4096];
                        int bytesLidos;
                        long totalLido = 0;

                        while (totalLido < tamanhoArquivo && (bytesLidos = in.read(buffer, 0, (int)Math.min(buffer.length, tamanhoArquivo - totalLido))) != -1) {
                            fos.write(buffer, 0, bytesLidos);
                            totalLido += bytesLidos;
                        }
                        fos.close();
                        Thread.sleep(500);
                        arquivosIgnorados.remove(nomeArquivo);

                        registrarLog("BROADCAST", "Repassando '" + nomeArquivo + "'", "Servidor", "Outros Clientes");
                        for (DataOutputStream escritor : escritores) {
                            if (escritor != out) {
                                synchronized(escritor) {
                                    escritor.writeUTF("SYNC");
                                    escritor.writeUTF(remetente);
                                    escritor.writeUTF(nomeArquivo);
                                    escritor.writeLong(tamanhoArquivo);

                                    FileInputStream fis = new FileInputStream(arquivoDestino);
                                    while ((bytesLidos = fis.read(buffer)) != -1) {
                                        escritor.write(buffer, 0, bytesLidos);
                                    }
                                    fis.close();
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

                        registrarLog("BROADCAST", "Repassando ordem de exclusão de '" + nomeArquivo + "'", "Servidor", "Outros Clientes");
                        for (DataOutputStream escritor : escritores) {
                            if (escritor != out) {
                                synchronized(escritor) {
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
                escritores.remove(out);
                try { socket.close(); } catch (IOException e) {}
            }
        }
    }
}