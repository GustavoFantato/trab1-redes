import java.io.*;
import java.net.*;
import java.nio.file.*;
import static java.nio.file.StandardWatchEventKinds.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

public class Cliente {
    private static Set<String> arquivosIgnorados = ConcurrentHashMap.newKeySet();
    private static volatile boolean envioInicialConcluido;

    public static void main(String[] args) throws Exception {
        // Verifica se o usuário informou o diretório local e seu ID, salva em variáveis
        // e cria a pasta local se não existir
        if (args.length < 2) {
            System.out.println("Erro: Informe o diretório local e o seu identificador.");
            System.out.println("Exemplo: java Cliente pasta_cliente_1 Alice");
            return;
        }
        String pastaLocal = args[0];
        String identificador = args[1];

        new File(pastaLocal).mkdirs();

        // Copia o que já existe antes de receber qualquer arquivo do servidor
        // Exceção: arquivos que começam com "." ou terminam com "~" são ignorados
        HashMap<String, byte[]> locais = new HashMap<>();
        File[] arqsLocais = new File(pastaLocal).listFiles();
        if (arqsLocais != null) {
            for (File f : arqsLocais) {
                if (f.isFile() && !f.getName().startsWith(".") && !f.getName().endsWith("~")) {
                    locais.put(f.getName(), Files.readAllBytes(f.toPath()));
                }
            }
        }

        // Conecta ao servidor
        Socket socket = new Socket("127.0.0.1", 8080);
        System.out.println("Conectado como [" + identificador + "]! Monitorando: " + pastaLocal);

        // Cria os streams de entrada e saída da rede
        DataOutputStream saida = new DataOutputStream(socket.getOutputStream());
        DataInputStream entrada = new DataInputStream(socket.getInputStream());

        // Cria o latch para esperar o handshake do servidor
        CountDownLatch handshakePronto = new CountDownLatch(1);

        // Cria uma thread para receber as mensagens do servidor
        new Thread(() -> {
            try {
                while (true) {
                    String operacao = entrada.readUTF();
                    String remetente = entrada.readUTF();
                    String nomeArquivo = entrada.readUTF();

                    if (operacao.equals("HANDSHAKE_FIM")) {
                        handshakePronto.countDown();
                        continue;
                    }

                    // Verifica se o arquivo existe localmente e se deve ser preservado
                    File arquivoLocal = new File(pastaLocal + "/" + nomeArquivo);
                    boolean preservarLocal = !envioInicialConcluido && locais.containsKey(nomeArquivo);

                    // Se a operação for SYNC, recebe o tamanho do arquivo e os dados
                    if (operacao.equals("SYNC")) {
                        long tamanhoArquivo = entrada.readLong();
                        if (tamanhoArquivo < 0 || tamanhoArquivo > Integer.MAX_VALUE) {
                            throw new IOException("Tamanho de arquivo inválido: " + tamanhoArquivo);
                        }
                        byte[] dados = new byte[(int) tamanhoArquivo];
                        entrada.readFully(dados);
                        if (preservarLocal)
                            continue;

                        System.out.println("\n[Download] Sincronizando '" +
                                arquivoLocal.getName() + "' (Fonte: " + remetente + ")");

                        arquivosIgnorados.add(nomeArquivo);
                        FileOutputStream fos = new FileOutputStream(arquivoLocal);
                        fos.write(dados);
                        fos.close();

                        Thread.sleep(500);
                        arquivosIgnorados.remove(nomeArquivo);

                        // Se a operação for DELETE, remove o arquivo local
                    } else if (operacao.equals("DELETE")) {
                        if (preservarLocal)
                            continue;
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

        // --- INÍCIO DO HANDSHAKE: espera o servidor e só então envia os arquivos
        // locais
        handshakePronto.await();
        for (String nome : locais.keySet()) {
            byte[] dados = locais.get(nome);
            synchronized (saida) {
                saida.writeUTF("SYNC");
                saida.writeUTF(identificador + " (Sync Inicial)");
                saida.writeUTF(nome);
                saida.writeLong(dados.length);
                saida.write(dados);
            }
        }
        envioInicialConcluido = true;
        // --- FIM DO HANDSHAKE ---

        // Cria o watch service para monitorar as alterações no diretório local
        WatchService watchService = FileSystems.getDefault().newWatchService();
        Path path = Paths.get(pastaLocal);
        path.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);

        // Loop principal para monitorar as alterações no diretório local
        while (true) {
            WatchKey key = watchService.take();
            Thread.sleep(400);

            Set<String> nomes = new HashSet<>();
            for (WatchEvent<?> event : key.pollEvents()) {
                String nomeArquivo = event.context().toString();
                if (nomeArquivo.startsWith(".") || nomeArquivo.endsWith("~"))
                    continue;
                if (arquivosIgnorados.contains(nomeArquivo))
                    continue;
                nomes.add(nomeArquivo);
            }
            key.reset();

            // Para cada arquivo alterado, envia a operação para o servidor
            for (String nomeArquivo : nomes) {
                File arquivoDetectado = new File(pastaLocal + "/" + nomeArquivo);
                synchronized (saida) {
                    if (arquivoDetectado.isFile()) {
                        byte[] dados = Files.readAllBytes(arquivoDetectado.toPath());
                        System.out.println("[Upload] Alteração detectada em '" + nomeArquivo + "'. Enviando...");
                        saida.writeUTF("SYNC");
                        saida.writeUTF(identificador);
                        saida.writeUTF(nomeArquivo);
                        saida.writeLong(dados.length);
                        saida.write(dados);
                    } else if (!arquivoDetectado.exists()) {
                        System.out.println("[Upload] Exclusão detectada em '" + nomeArquivo + "'. Sincronizando...");
                        saida.writeUTF("DELETE");
                        saida.writeUTF(identificador);
                        saida.writeUTF(nomeArquivo);
                    }
                }
            }
        }
    }
}
