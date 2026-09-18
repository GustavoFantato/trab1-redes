import java.io.*;
import java.net.*;
import java.nio.file.*;
import static java.nio.file.StandardWatchEventKinds.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Cliente {
    private static Set<String> arquivosIgnorados = ConcurrentHashMap.newKeySet();
    private static ConcurrentHashMap<String, Long> ultimoEnvio = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Erro: Informe o diretório local e o seu identificador.");
            System.out.println("Exemplo: java Cliente pasta_cliente_1 Alice");
            return;
        }
        String pastaLocal = args[0];
        String meuIdentificador = args[1];

        new File(pastaLocal).mkdirs();

        Socket socket = new Socket("127.0.0.1", 8080);
        System.out.println("Conectado como [" + meuIdentificador + "]! Monitorando: " + pastaLocal);

        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        DataInputStream in = new DataInputStream(socket.getInputStream());

        new Thread(() -> {
            try {
                while (true) {
                    String operacao = in.readUTF();
                    String remetente = in.readUTF();
                    String nomeArquivo = in.readUTF();
                    File arquivoLocal = new File(pastaLocal + "/" + nomeArquivo);

                    if (operacao.equals("SYNC")) {
                        long tamanhoArquivo = in.readLong();
                        System.out.println("\n[Download] Sincronizando '" + nomeArquivo + "' (Fonte: " + remetente + ")");

                        arquivosIgnorados.add(nomeArquivo);
                        FileOutputStream fos = new FileOutputStream(arquivoLocal);
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

                    } else if (operacao.equals("DELETE")) {
                        System.out.println("\n[Delete] Removendo '" + nomeArquivo + "' (Fonte: " + remetente + ")");
                        arquivosIgnorados.add(nomeArquivo);
                        if (arquivoLocal.exists()) {
                            arquivoLocal.delete();
                        }
                        Thread.sleep(500);
                        arquivosIgnorados.remove(nomeArquivo);
                    }
                }
            } catch (Exception e) {
                System.out.println("\nA conexão com o servidor foi encerrada.");
                System.exit(0);
            }
        }).start();

        // --- INÍCIO DO HANDSHAKE: Envia arquivos locais pré-existentes para o Servidor ---
        File pastaLoc = new File(pastaLocal);
        File[] arqsLocais = pastaLoc.listFiles();
        if (arqsLocais != null) {
            for (File f : arqsLocais) {
                if (f.isFile() && !f.getName().startsWith(".") && !f.getName().endsWith("~")) {
                    synchronized (out) {
                        out.writeUTF("SYNC");
                        out.writeUTF(meuIdentificador + " (Sync Inicial)");
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
        // --- FIM DO HANDSHAKE ---

        WatchService watchService = FileSystems.getDefault().newWatchService();
        Path path = Paths.get(pastaLocal);
        path.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);

        while (true) {
            WatchKey key = watchService.take();
            Thread.sleep(100);

            for (WatchEvent<?> event : key.pollEvents()) {
                String nomeArquivo = event.context().toString();

                if (nomeArquivo.startsWith(".") || nomeArquivo.endsWith("~")) continue;
                if (arquivosIgnorados.contains(nomeArquivo)) continue;

                long tempoAtual = System.currentTimeMillis();
                long tempoAnterior = ultimoEnvio.getOrDefault(nomeArquivo, 0L);
                if (tempoAtual - tempoAnterior < 1000) continue;

                File arquivoDetectado = new File(pastaLocal + "/" + nomeArquivo);

                synchronized (out) {
                    if (event.kind() == ENTRY_CREATE || event.kind() == ENTRY_MODIFY) {
                        if (arquivoDetectado.exists() && arquivoDetectado.isFile()) {
                            ultimoEnvio.put(nomeArquivo, tempoAtual);
                            System.out.println("[Upload] Alteração detectada em '" + nomeArquivo + "'. Enviando...");
                            out.writeUTF("SYNC");
                            out.writeUTF(meuIdentificador);
                            out.writeUTF(nomeArquivo);
                            out.writeLong(arquivoDetectado.length());

                            FileInputStream fis = new FileInputStream(arquivoDetectado);
                            byte[] buffer = new byte[4096];
                            int bytesLidos;
                            while ((bytesLidos = fis.read(buffer)) != -1) {
                                out.write(buffer, 0, bytesLidos);
                            }
                            fis.close();
                        }
                    } else if (event.kind() == ENTRY_DELETE) {
                        ultimoEnvio.put(nomeArquivo, tempoAtual);
                        System.out.println("[Upload] Exclusão detectada em '" + nomeArquivo + "'. Sincronizando...");
                        out.writeUTF("DELETE");
                        out.writeUTF(meuIdentificador);
                        out.writeUTF(nomeArquivo);
                    }
                }
            }
            key.reset();
        }
    }
}