package pitiupi.mural.control;

import pitiupi.application.messaging.TransportMode;
import pitiupi.mural.net.Escopo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aviso publicado por mim e quem já confirmou a leitura. */
public final class AvisoEnviado {

    /** Quem confirmou e quando. */
    public record Leitor(String readerId, String name, String ip, long readAtMillis) {
    }

    private final String avisoId;
    private final String text;
    private final long createdAtMillis;
    private final Escopo escopo;
    private final TransportMode transport;
    private final String destinoLabel;
    /** Quantas pessoas estavam online no momento do envio (estimativa para a sala). */
    private final int expectedReaders;
    private final Map<String, Leitor> readers = new LinkedHashMap<>();
    /** -1 enquanto envia; depois, quantos envios o núcleo conseguiu fazer (0 = falhou). */
    private volatile int deliveries = -1;

    AvisoEnviado(
            String avisoId,
            String text,
            long createdAtMillis,
            Escopo escopo,
            TransportMode transport,
            String destinoLabel,
            int expectedReaders
    ) {
        this.avisoId = avisoId;
        this.text = text;
        this.createdAtMillis = createdAtMillis;
        this.escopo = escopo;
        this.transport = transport;
        this.destinoLabel = destinoLabel;
        this.expectedReaders = expectedReaders;
    }

    public String avisoId() {
        return avisoId;
    }

    public String text() {
        return text;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public Escopo escopo() {
        return escopo;
    }

    public TransportMode transport() {
        return transport;
    }

    public String destinoLabel() {
        return destinoLabel;
    }

    public int expectedReaders() {
        return expectedReaders;
    }

    public List<Leitor> readers() {
        return Collections.unmodifiableList(new ArrayList<>(readers.values()));
    }

    public int readCount() {
        return readers.size();
    }

    public int deliveries() {
        return deliveries;
    }

    public boolean isSending() {
        return deliveries < 0;
    }

    void setDeliveries(int deliveries) {
        this.deliveries = deliveries;
    }

    /** @return {@code true} se é uma confirmação nova (não repetida). */
    boolean addReader(Leitor leitor) {
        return readers.putIfAbsent(leitor.readerId(), leitor) == null;
    }
}
