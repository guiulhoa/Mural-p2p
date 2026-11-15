package pitiupi.mural.control;

import pitiupi.application.messaging.TransportMode;
import pitiupi.mural.net.AvisoMessage;
import pitiupi.mural.net.Escopo;
import pitiupi.mural.net.LeituraMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Regras do protocolo do mural, sem nenhum componente Swing.
 *
 * <p><b>Threads.</b> {@link #publish}, {@link #confirmRead},
 * {@link #onAvisoReceived} e {@link #onLeituraReceived} devem ser chamados na
 * mesma thread (a do Swing, no plugin). O envio de rede roda no
 * {@code ioExecutor}, e o {@link MuralSink} é chamado de qualquer thread.</p>
 */
public final class MuralController {

    /** Reenvios da confirmação em UDP (ms após o primeiro envio). UDP não garante entrega. */
    static final long[] ACK_REPEAT_DELAYS_MS = {1000, 3000};

    private final MuralSender sender;
    private final Executor ioExecutor;
    private final ScheduledExecutorService scheduler;
    private final String ownId = UUID.randomUUID().toString();

    private final Map<String, AvisoRecebido> received = new LinkedHashMap<>();
    private final Map<String, AvisoEnviado> sentById = new HashMap<>();
    private final List<AvisoEnviado> sent = new ArrayList<>();
    private MuralSink sink = new MuralSink() {
        @Override public void recebidoAtualizado(AvisoRecebido aviso) { }
        @Override public void enviadoAtualizado(AvisoEnviado aviso) { }
        @Override public void status(String text) { }
    };

    /**
     * @param ioExecutor executa os envios de rede (uma thread só, para manter a ordem)
     * @param scheduler  agenda os reenvios de confirmação em UDP; pode ser {@code null}
     *                   para desligar os reenvios
     */
    public MuralController(MuralSender sender, Executor ioExecutor, ScheduledExecutorService scheduler) {
        this.sender = sender;
        this.ioExecutor = ioExecutor;
        this.scheduler = scheduler;
    }

    public void setSink(MuralSink sink) {
        if (sink != null) {
            this.sink = sink;
        }
    }

    /** Identificador desta execução do plugin. Vai no campo {@code authorId}/{@code readerId}. */
    public String ownId() {
        return ownId;
    }

    public List<AvisoRecebido> receivedList() {
        List<AvisoRecebido> list = new ArrayList<>(received.values());
        Collections.reverse(list);
        return list;
    }

    public List<AvisoEnviado> sentList() {
        List<AvisoEnviado> list = new ArrayList<>(sent);
        Collections.reverse(list);
        return list;
    }

    public int unreadCount() {
        int n = 0;
        for (AvisoRecebido r : received.values()) {
            if (!r.isRead()) {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- publicar

    /**
     * Publica um aviso.
     *
     * @param text   texto livre (1 a {@value AvisoMessage#MAX_TEXT} caracteres)
     * @param peerIp {@code null} para a sala, ou o IPv4 de uma pessoa
     * @param mode   transporte; a tela só oferece os que o catálogo do núcleo permite
     * @return o aviso registrado, ou {@code null} se o texto é inválido
     */
    public AvisoEnviado publish(String text, String peerIp, TransportMode mode) {
        String body = text == null ? "" : text.strip();
        if (body.isEmpty()) {
            sink.status("Escreva algo antes de publicar.");
            return null;
        }
        if (body.length() > AvisoMessage.MAX_TEXT) {
            sink.status("Aviso grande demais (máximo " + AvisoMessage.MAX_TEXT + " caracteres).");
            return null;
        }
        TransportMode transport = mode != null ? mode : TransportMode.UNICAST_UDP;
        Escopo escopo = peerIp == null ? Escopo.SALA : Escopo.PESSOA;

        List<MuralSender.PeerInfo> peers = sender.onlinePeers();
        int expected = escopo == Escopo.SALA ? peers.size() : 1;
        String destinoLabel = escopo == Escopo.SALA ? "todos na sala" : nameOf(peers, peerIp);

        long now = System.currentTimeMillis();
        String avisoId = UUID.randomUUID().toString();
        AvisoMessage message = new AvisoMessage(
                avisoId, ownId, sender.userName(), sender.localIp(),
                body, now, escopo, transport.name());

        AvisoEnviado enviado = new AvisoEnviado(avisoId, body, now, escopo, transport, destinoLabel, expected);
        sent.add(enviado);
        sentById.put(avisoId, enviado);
        sink.enviadoAtualizado(enviado);

        ioExecutor.execute(() -> {
            int deliveries;
            try {
                deliveries = sender.send(message, peerIp, transport);
            } catch (RuntimeException ex) {
                deliveries = 0;
            }
            enviado.setDeliveries(deliveries);
            if (deliveries == 0) {
                sink.status("Não consegui enviar o aviso (" + destinoLabel + ", " + transport.getLabel() + ").");
            }
            sink.enviadoAtualizado(enviado);
        });
        return enviado;
    }

    // ----------------------------------------------------------------- receber

    /** Chegou um {@code AVISO}. Descarta inválido, eco do próprio envio e duplicata. */
    public void onAvisoReceived(AvisoMessage message) {
        if (message == null || !message.isValid()) {
            return;
        }
        if (ownId.equals(message.getAuthorId())) {
            return; // eco do multicast: eu mesmo publiquei
        }
        if (received.containsKey(message.getAvisoId())) {
            return; // duplicata (ex.: chegou por dois caminhos)
        }
        AvisoRecebido recebido = new AvisoRecebido(message, System.currentTimeMillis());
        received.put(message.getAvisoId(), recebido);
        sink.recebidoAtualizado(recebido);
        sink.status("Novo aviso de " + displayName(message) + " no mural.");
    }

    // ------------------------------------------------------ confirmar a leitura

    /**
     * Confirma a leitura de um aviso recebido. Só o autor é avisado.
     *
     * @return {@code false} se o aviso não existe, já está lido ou já há confirmação em andamento
     */
    public boolean confirmRead(String avisoId) {
        AvisoRecebido recebido = received.get(avisoId);
        if (recebido == null || !recebido.beginAck()) {
            return false;
        }
        AvisoMessage aviso = recebido.aviso();
        TransportMode mode = TransportMode.TCP.name().equals(aviso.getTransport())
                ? TransportMode.TCP
                : TransportMode.UNICAST_UDP;
        long now = System.currentTimeMillis();
        LeituraMessage ack = new LeituraMessage(
                aviso.getAvisoId(), aviso.getAuthorId(), ownId,
                sender.userName(), sender.localIp(), now);

        sink.recebidoAtualizado(recebido); // mostra "enviando confirmação…"
        ioExecutor.execute(() -> {
            int sentCount;
            try {
                sentCount = sender.send(ack, aviso.getAuthorIp(), mode);
            } catch (RuntimeException ex) {
                sentCount = 0;
            }
            boolean ok = sentCount > 0;
            recebido.finishAck(ok, now);
            if (!ok) {
                sink.status("Não consegui entregar a confirmação a " + displayName(aviso) + ". Tente de novo.");
            } else if (mode == TransportMode.UNICAST_UDP) {
                scheduleRepeats(ack, aviso.getAuthorIp());
            }
            sink.recebidoAtualizado(recebido);
        });
        return true;
    }

    private void scheduleRepeats(LeituraMessage ack, String authorIp) {
        if (scheduler == null) {
            return;
        }
        for (long delay : ACK_REPEAT_DELAYS_MS) {
            try {
                scheduler.schedule(
                        () -> ioExecutor.execute(() -> sender.send(ack, authorIp, TransportMode.UNICAST_UDP)),
                        delay, TimeUnit.MILLISECONDS);
            } catch (RuntimeException ignored) {
                return; // scheduler já encerrado
            }
        }
    }

    /** Chegou uma {@code LEITURA}. Só vale se for sobre um aviso meu; repetida é ignorada. */
    public void onLeituraReceived(LeituraMessage message) {
        if (message == null || !message.isValid() || !ownId.equals(message.getAuthorId())) {
            return;
        }
        AvisoEnviado enviado = sentById.get(message.getAvisoId());
        if (enviado == null) {
            return;
        }
        AvisoEnviado.Leitor leitor = new AvisoEnviado.Leitor(
                message.getReaderId(),
                message.getReaderName() == null || message.getReaderName().isBlank()
                        ? "(sem nome)" : message.getReaderName(),
                message.getReaderIp(),
                message.getReadAtMillis());
        if (enviado.addReader(leitor)) {
            sink.enviadoAtualizado(enviado);
            sink.status(leitor.name() + " confirmou a leitura do seu aviso.");
        }
    }

    // ------------------------------------------------------------------- misc

    private static String nameOf(List<MuralSender.PeerInfo> peers, String ip) {
        for (MuralSender.PeerInfo p : peers) {
            if (p.ip().equals(ip)) {
                return p.name();
            }
        }
        return ip;
    }

    private static String displayName(AvisoMessage m) {
        String name = m.getAuthorName();
        return name == null || name.isBlank() ? m.getAuthorIp() : name;
    }
}
