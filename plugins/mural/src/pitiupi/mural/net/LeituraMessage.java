package pitiupi.mural.net;

import pitiupi.models.Messages.Message;

/**
 * Mensagem {@code LEITURA}: confirmação de que um aviso foi lido.
 *
 * <p>Vai sempre em unicast para o autor do aviso (UDP unicast, ou TCP se o
 * aviso chegou por TCP). É idempotente: o autor identifica o leitor por
 * {@code readerId} e ignora confirmações repetidas.</p>
 */
public class LeituraMessage extends Message {

    private static final long serialVersionUID = 1L;

    private int version;
    private String avisoId;
    /** {@code authorId} do aviso confirmado: quem não é o autor descarta. */
    private String authorId;
    private String readerId;
    private String readerName;
    private String readerIp;
    private long readAtMillis;

    public LeituraMessage() {
    }

    public LeituraMessage(
            String avisoId,
            String authorId,
            String readerId,
            String readerName,
            String readerIp,
            long readAtMillis
    ) {
        this.version = AvisoMessage.PROTOCOL_VERSION;
        this.avisoId = avisoId;
        this.authorId = authorId;
        this.readerId = readerId;
        this.readerName = readerName;
        this.readerIp = readerIp;
        this.readAtMillis = readAtMillis;
    }

    public int getVersion() { return version; }
    public String getAvisoId() { return avisoId; }
    public String getAuthorId() { return authorId; }
    public String getReaderId() { return readerId; }
    public String getReaderName() { return readerName; }
    public String getReaderIp() { return readerIp; }
    public long getReadAtMillis() { return readAtMillis; }

    public boolean isValid() {
        return version == AvisoMessage.PROTOCOL_VERSION
                && avisoId != null && !avisoId.isBlank()
                && authorId != null && !authorId.isBlank()
                && readerId != null && !readerId.isBlank();
    }
}
