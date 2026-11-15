package pitiupi.mural;

import pitiupi.application.messaging.TransportMode;
import pitiupi.models.Messages.Message;
import pitiupi.mural.control.AvisoEnviado;
import pitiupi.mural.control.AvisoRecebido;
import pitiupi.mural.control.MuralController;
import pitiupi.mural.control.MuralSender;
import pitiupi.mural.control.MuralSink;
import pitiupi.mural.net.AvisoMessage;
import pitiupi.mural.net.Escopo;
import pitiupi.mural.net.LeituraMessage;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Testes sem JUnit (o ambiente de aula não garante a dependência).
 * Rodar: {@code ant selftest} na pasta do plugin. Sai com código 1 se algo falhar.
 */
public final class MuralProtocolTest {

    private static int failures;
    private static int checks;

    // ------------------------------------------------------- dublês de teste

    record Sent(Message message, String ip, TransportMode mode) {
    }

    static final class FakeSender implements MuralSender {
        final String name;
        final String ip;
        final List<PeerInfo> peers = new ArrayList<>();
        final List<Sent> sent = new ArrayList<>();
        int result = 1;

        FakeSender(String name, String ip) {
            this.name = name;
            this.ip = ip;
        }

        @Override public String userName() { return name; }
        @Override public String localIp() { return ip; }
        @Override public List<PeerInfo> onlinePeers() { return peers; }

        @Override
        public synchronized int send(Message message, String peerIp, TransportMode mode) {
            sent.add(new Sent(message, peerIp, mode));
            return result;
        }

        synchronized int count() { return sent.size(); }
        synchronized Sent last() { return sent.get(sent.size() - 1); }
    }

    static final class RecordingSink implements MuralSink {
        final List<String> statuses = new ArrayList<>();
        int recebidos;
        int enviados;

        @Override public void recebidoAtualizado(AvisoRecebido aviso) { recebidos++; }
        @Override public void enviadoAtualizado(AvisoEnviado aviso) { enviados++; }
        @Override public void status(String text) { statuses.add(text); }
    }

    static final Executor DIRECT = Runnable::run;

    static MuralController controller(FakeSender sender) {
        return new MuralController(sender, DIRECT, null);
    }

    // --------------------------------------------------------------- testes

    static void publicarParaSala() {
        FakeSender net = new FakeSender("Ana", "10.0.0.1");
        net.peers.add(new MuralSender.PeerInfo("Bia", "10.0.0.2"));
        net.peers.add(new MuralSender.PeerInfo("Caio", "10.0.0.3"));
        MuralController c = controller(net);

        AvisoEnviado e = c.publish("  Prova na sexta  ", null, TransportMode.MULTICAST_UDP);

        check(e != null, "publish devolve o aviso");
        check(net.count() == 1, "enviou exatamente 1 mensagem");
        check(net.last().ip() == null, "sala: destino null (broadcast)");
        check(net.last().mode() == TransportMode.MULTICAST_UDP, "sala: transporte multicast");
        AvisoMessage m = (AvisoMessage) net.last().message();
        check(m.getText().equals("Prova na sexta"), "texto sem espaços nas pontas");
        check(m.getEscopo() == Escopo.SALA, "escopo SALA");
        check(m.getAuthorId().equals(c.ownId()), "authorId = id desta execução");
        check(m.getAuthorIp().equals("10.0.0.1"), "authorIp preenchido");
        check(m.isValid(), "mensagem enviada é válida");
        check(e.expectedReaders() == 2, "esperados = pessoas online no envio (2)");
        check(e.deliveries() == 1 && !e.isSending(), "resultado do envio registrado");
        check(c.sentList().size() == 1, "aparece em Enviados");
    }

    static void publicarParaUmaPessoa() {
        FakeSender net = new FakeSender("Ana", "10.0.0.1");
        net.peers.add(new MuralSender.PeerInfo("Bia", "10.0.0.2"));
        MuralController c = controller(net);

        AvisoEnviado e = c.publish("Oi", "10.0.0.2", TransportMode.UNICAST_UDP);

        check(net.last().ip().equals("10.0.0.2"), "pessoa: destino é o IP");
        check(((AvisoMessage) net.last().message()).getEscopo() == Escopo.PESSOA, "escopo PESSOA");
        check(e.expectedReaders() == 1, "esperado = 1");
        check(e.destinoLabel().equals("Bia"), "rótulo usa o nome da pessoa");
    }

    static void rejeitaTextoInvalido() {
        FakeSender net = new FakeSender("Ana", "10.0.0.1");
        MuralController c = controller(net);
        RecordingSink sink = new RecordingSink();
        c.setSink(sink);

        check(c.publish("   ", null, TransportMode.MULTICAST_UDP) == null, "texto em branco é rejeitado");
        check(c.publish(null, null, TransportMode.MULTICAST_UDP) == null, "texto null é rejeitado");
        check(c.publish("x".repeat(AvisoMessage.MAX_TEXT + 1), null, TransportMode.MULTICAST_UDP) == null,
                "texto acima do limite é rejeitado");
        check(c.publish("x".repeat(AvisoMessage.MAX_TEXT), null, TransportMode.MULTICAST_UDP) != null,
                "texto exatamente no limite é aceito");
        check(net.count() == 1, "só o válido saiu");
        check(sink.statuses.size() == 3, "cada rejeição gerou status");
    }

    static void falhaDeEnvioAparece() {
        FakeSender net = new FakeSender("Ana", "10.0.0.1");
        net.result = 0;
        MuralController c = controller(net);
        RecordingSink sink = new RecordingSink();
        c.setSink(sink);

        AvisoEnviado e = c.publish("Oi", null, TransportMode.UNICAST_UDP);

        check(e.deliveries() == 0, "deliveries = 0 quando nada saiu");
        check(!sink.statuses.isEmpty(), "falha gera status");
    }

    static void receberAviso() {
        FakeSender net = new FakeSender("Bia", "10.0.0.2");
        MuralController c = controller(net);
        RecordingSink sink = new RecordingSink();
        c.setSink(sink);

        AvisoMessage valido = aviso("a1", "autor-1", "Ana", "10.0.0.1", "Oi", "MULTICAST_UDP");
        c.onAvisoReceived(valido);
        check(c.receivedList().size() == 1, "aviso válido entra em Recebidos");
        check(c.unreadCount() == 1, "começa não lido");
        check(sink.recebidos == 1, "sink avisado");

        c.onAvisoReceived(valido);
        check(c.receivedList().size() == 1, "duplicata ignorada");

        c.onAvisoReceived(aviso("a2", c.ownId(), "Bia", "10.0.0.2", "eco", "MULTICAST_UDP"));
        check(c.receivedList().size() == 1, "eco do próprio multicast ignorado");

        c.onAvisoReceived(aviso("a3", "autor-1", "Ana", "10.0.0.1", "   ", "MULTICAST_UDP"));
        c.onAvisoReceived(aviso("a4", "autor-1", "Ana", "10.0.0.1", "x".repeat(AvisoMessage.MAX_TEXT + 1), "MULTICAST_UDP"));
        c.onAvisoReceived(aviso("", "autor-1", "Ana", "10.0.0.1", "sem id", "MULTICAST_UDP"));
        c.onAvisoReceived(aviso("a5", "autor-1", "Ana", "", "sem ip", "MULTICAST_UDP"));
        c.onAvisoReceived(new AvisoMessage()); // versão 0: protocolo desconhecido
        c.onAvisoReceived(null);
        check(c.receivedList().size() == 1, "inválidos descartados");
    }

    static void confirmarLeitura() {
        FakeSender net = new FakeSender("Bia", "10.0.0.2");
        MuralController c = controller(net);
        c.onAvisoReceived(aviso("a1", "autor-1", "Ana", "10.0.0.1", "Oi", "MULTICAST_UDP"));

        check(c.confirmRead("a1"), "primeira confirmação aceita");
        Sent s = net.last();
        check(s.message() instanceof LeituraMessage, "enviou LEITURA");
        check("10.0.0.1".equals(s.ip()), "LEITURA vai só para o IP do autor");
        check(s.mode() == TransportMode.UNICAST_UDP, "aviso por multicast -> confirmação em UDP unicast");
        LeituraMessage l = (LeituraMessage) s.message();
        check(l.getAvisoId().equals("a1") && l.getAuthorId().equals("autor-1"), "LEITURA referencia o aviso e o autor");
        check(l.getReaderId().equals(c.ownId()) && l.getReaderName().equals("Bia"), "LEITURA identifica o leitor");
        check(l.isValid(), "LEITURA válida");
        check(c.unreadCount() == 0 && c.receivedList().get(0).isRead(), "marcado como lido");

        check(!c.confirmRead("a1"), "segunda confirmação não reenvia");
        check(net.count() == 1, "nenhum envio extra");
        check(!c.confirmRead("nao-existe"), "aviso inexistente");
    }

    static void confirmacaoPorTcpQuandoAvisoVeioPorTcp() {
        FakeSender net = new FakeSender("Bia", "10.0.0.2");
        MuralController c = controller(net);
        c.onAvisoReceived(aviso("t1", "autor-1", "Ana", "10.0.0.1", "Oi", "TCP"));
        c.confirmRead("t1");
        check(net.last().mode() == TransportMode.TCP, "aviso por TCP -> confirmação por TCP");
    }

    static void falhaNaConfirmacaoPermiteTentarDeNovo() {
        FakeSender net = new FakeSender("Bia", "10.0.0.2");
        MuralController c = controller(net);
        RecordingSink sink = new RecordingSink();
        c.setSink(sink);
        c.onAvisoReceived(aviso("a1", "autor-1", "Ana", "10.0.0.1", "Oi", "MULTICAST_UDP"));

        net.result = 0;
        c.confirmRead("a1");
        check(!c.receivedList().get(0).isRead(), "envio falhou: continua não lido");
        check(!c.receivedList().get(0).isAckPending(), "não fica preso em 'enviando'");

        net.result = 1;
        check(c.confirmRead("a1"), "pode tentar de novo");
        check(c.receivedList().get(0).isRead(), "agora lido");
    }

    static void receberLeitura() {
        FakeSender net = new FakeSender("Ana", "10.0.0.1");
        MuralController c = controller(net);
        RecordingSink sink = new RecordingSink();
        c.setSink(sink);
        AvisoEnviado e = c.publish("Oi", null, TransportMode.MULTICAST_UDP);
        String id = e.avisoId();

        c.onLeituraReceived(new LeituraMessage(id, c.ownId(), "leitor-1", "Bia", "10.0.0.2", 1000L));
        check(e.readCount() == 1, "leitura contada");
        check(e.readers().get(0).name().equals("Bia"), "nome do leitor guardado");

        c.onLeituraReceived(new LeituraMessage(id, c.ownId(), "leitor-1", "Bia", "10.0.0.2", 2000L));
        check(e.readCount() == 1, "leitura repetida (mesmo readerId) não conta duas vezes");

        c.onLeituraReceived(new LeituraMessage(id, c.ownId(), "leitor-2", "Caio", "10.0.0.3", 3000L));
        check(e.readCount() == 2, "outro leitor conta");

        c.onLeituraReceived(new LeituraMessage(id, "outro-autor", "leitor-3", "Dani", "10.0.0.4", 4000L));
        check(e.readCount() == 2, "LEITURA de aviso de outro autor é ignorada");

        c.onLeituraReceived(new LeituraMessage("desconhecido", c.ownId(), "leitor-4", "Edu", "10.0.0.5", 5000L));
        check(e.readCount() == 2, "LEITURA de aviso desconhecido é ignorada");

        c.onLeituraReceived(new LeituraMessage(id, c.ownId(), "", "Fred", "10.0.0.6", 6000L));
        c.onLeituraReceived(new LeituraMessage());
        c.onLeituraReceived(null);
        check(e.readCount() == 2, "LEITURA inválida ignorada");
    }

    /** Dois participantes ligados por uma "rede" em memória: ciclo completo. */
    static void cicloCompletoAnaBia() {
        FakeSender netAna = new FakeSender("Ana", "10.0.0.1");
        FakeSender netBia = new FakeSender("Bia", "10.0.0.2");
        netAna.peers.add(new MuralSender.PeerInfo("Bia", "10.0.0.2"));
        MuralController ana = controller(netAna);
        MuralController bia = controller(netBia);

        AvisoEnviado e = ana.publish("Reunião às 14h", null, TransportMode.MULTICAST_UDP);
        bia.onAvisoReceived((AvisoMessage) netAna.last().message());
        ana.onAvisoReceived((AvisoMessage) netAna.last().message()); // eco multicast

        check(ana.receivedList().isEmpty(), "Ana não recebe o próprio aviso");
        check(bia.receivedList().size() == 1, "Bia recebeu");

        bia.confirmRead(e.avisoId());
        ana.onLeituraReceived((LeituraMessage) netBia.last().message());

        check(e.readCount() == 1, "Ana vê que Bia leu");
        check(e.readers().get(0).name().equals("Bia"), "Ana sabe quem leu");
    }

    static void serializacaoDeIda() throws Exception {
        AvisoMessage a = aviso("a1", "autor-1", "Ana", "10.0.0.1", "Olá, ação!", "MULTICAST_UDP");
        AvisoMessage a2 = (AvisoMessage) roundTrip(a);
        check(a2.getText().equals("Olá, ação!") && a2.getEscopo() == Escopo.SALA && a2.isValid(),
                "AvisoMessage sobrevive à serialização (acentos incluídos)");
        check(a2.isPublic(), "AvisoMessage é pública (entra por getMessages)");

        LeituraMessage l = new LeituraMessage("a1", "autor-1", "r1", "Bia", "10.0.0.2", 123L);
        LeituraMessage l2 = (LeituraMessage) roundTrip(l);
        check(l2.getReaderName().equals("Bia") && l2.getReadAtMillis() == 123L && l2.isValid(),
                "LeituraMessage sobrevive à serialização");
    }

    static void reenviosDaConfirmacaoEmUdp() throws Exception {
        FakeSender net = new FakeSender("Bia", "10.0.0.2");
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        try {
            MuralController c = new MuralController(net, DIRECT, sched);
            c.onAvisoReceived(aviso("a1", "autor-1", "Ana", "10.0.0.1", "Oi", "UNICAST_UDP"));
            c.confirmRead("a1");
            check(net.count() == 1, "1 envio imediato");
            Thread.sleep(3600);
            check(net.count() == 3, "mais 2 reenvios da confirmação (UDP não garante entrega); total=" + net.count());

            FakeSender netTcp = new FakeSender("Bia", "10.0.0.2");
            MuralController c2 = new MuralController(netTcp, DIRECT, sched);
            c2.onAvisoReceived(aviso("t1", "autor-1", "Ana", "10.0.0.1", "Oi", "TCP"));
            c2.confirmRead("t1");
            Thread.sleep(3600);
            check(netTcp.count() == 1, "TCP não reenvia (já é confiável); total=" + netTcp.count());
        } finally {
            sched.shutdownNow();
        }
    }

    // ------------------------------------------------------------- utilitários

    static AvisoMessage aviso(String id, String authorId, String name, String ip, String text, String transport) {
        return new AvisoMessage(id, authorId, name, ip, text, 1_700_000_000_000L, Escopo.SALA, transport);
    }

    static Object roundTrip(Message m) throws Exception {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(m.toByteArray()))) {
            return in.readObject();
        }
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FALHOU: " + what);
        }
    }

    interface Case {
        void run() throws Exception;
    }

    static void run(String name, Case c) {
        int before = failures;
        try {
            c.run();
        } catch (Exception | AssertionError ex) {
            failures++;
            System.out.println("  EXCEÇÃO: " + ex);
            ex.printStackTrace(System.out);
        }
        System.out.println((failures == before ? "[ok]    " : "[FALHA] ") + name);
    }

    public static void main(String[] args) {
        run("publicar para a sala", MuralProtocolTest::publicarParaSala);
        run("publicar para uma pessoa", MuralProtocolTest::publicarParaUmaPessoa);
        run("rejeitar texto inválido", MuralProtocolTest::rejeitaTextoInvalido);
        run("falha de envio é visível", MuralProtocolTest::falhaDeEnvioAparece);
        run("receber aviso (validação, eco, duplicata)", MuralProtocolTest::receberAviso);
        run("confirmar leitura", MuralProtocolTest::confirmarLeitura);
        run("confirmação por TCP se aviso veio por TCP", MuralProtocolTest::confirmacaoPorTcpQuandoAvisoVeioPorTcp);
        run("falha na confirmação permite repetir", MuralProtocolTest::falhaNaConfirmacaoPermiteTentarDeNovo);
        run("receber leitura (idempotência, autor)", MuralProtocolTest::receberLeitura);
        run("ciclo completo Ana -> Bia -> Ana", MuralProtocolTest::cicloCompletoAnaBia);
        run("serialização das mensagens", MuralProtocolTest::serializacaoDeIda);
        run("reenvios da confirmação em UDP", MuralProtocolTest::reenviosDaConfirmacaoEmUdp);

        System.out.println();
        System.out.println(checks + " verificações, " + failures + " falha(s).");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
