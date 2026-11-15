package pitiupi.mural.control;

import pitiupi.mural.net.AvisoMessage;

/**
 * Aviso que chegou de outra pessoa e o estado local de leitura.
 *
 * <p>O estado de leitura só vira "lido" depois que a confirmação foi de fato
 * entregue ao núcleo de rede. Se o envio falhar, o aviso continua não lido e
 * a pessoa pode tentar de novo.</p>
 */
public final class AvisoRecebido {

    private final AvisoMessage aviso;
    private final long receivedAtMillis;
    private volatile boolean read;
    private volatile boolean ackPending;
    private volatile long readAtMillis;

    AvisoRecebido(AvisoMessage aviso, long receivedAtMillis) {
        this.aviso = aviso;
        this.receivedAtMillis = receivedAtMillis;
    }

    public AvisoMessage aviso() {
        return aviso;
    }

    public long receivedAtMillis() {
        return receivedAtMillis;
    }

    public boolean isRead() {
        return read;
    }

    /** {@code true} enquanto a confirmação está sendo enviada. */
    public boolean isAckPending() {
        return ackPending;
    }

    public long readAtMillis() {
        return readAtMillis;
    }

    /** @return {@code false} se já está lido ou já há confirmação em andamento. */
    synchronized boolean beginAck() {
        if (read || ackPending) {
            return false;
        }
        ackPending = true;
        return true;
    }

    synchronized void finishAck(boolean delivered, long atMillis) {
        ackPending = false;
        if (delivered) {
            read = true;
            readAtMillis = atMillis;
        }
    }
}
