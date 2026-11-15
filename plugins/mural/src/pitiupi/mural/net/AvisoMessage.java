package pitiupi.mural.net;

import pitiupi.models.Messages.Message;

/**
 * Mensagem {@code AVISO}: alguém publica um texto no mural.
 *
 * <p>O destinatário só pode responder com {@link LeituraMessage}. Não existe
 * "responder", "editar" nem "apagar" no protocolo.</p>
 */
public class AvisoMessage extends Message {

    private static final long serialVersionUID = 1L;

    /** Versão do protocolo do mural. Receptor ignora versões que não conhece. */
    public static final int PROTOCOL_VERSION = 1;

    /** Limite do texto (caracteres). Receptor descarta avisos acima disso. */
    public static final int MAX_TEXT = 1000;

    private int version;
    private String avisoId;
    private String authorId;
    private String authorName;
    private String authorIp;
    private String text;
    private long createdAtMillis;
    private Escopo escopo;
    /** Nome do {@code TransportMode} usado no envio (ex.: MULTICAST_UDP). */
    private String transport;

    public AvisoMessage() {
    }

    public AvisoMessage(
            String avisoId,
            String authorId,
            String authorName,
            String authorIp,
            String text,
            long createdAtMillis,
            Escopo escopo,
            String transport
    ) {
        this.version = PROTOCOL_VERSION;
        this.avisoId = avisoId;
        this.authorId = authorId;
        this.authorName = authorName;
        this.authorIp = authorIp;
        this.text = text;
        this.createdAtMillis = createdAtMillis;
        this.escopo = escopo;
        this.transport = transport;
    }

    public int getVersion() { return version; }
    public String getAvisoId() { return avisoId; }
    public String getAuthorId() { return authorId; }
    public String getAuthorName() { return authorName; }
    public String getAuthorIp() { return authorIp; }
    public String getText() { return text; }
    public long getCreatedAtMillis() { return createdAtMillis; }
    public Escopo getEscopo() { return escopo; }
    public String getTransport() { return transport; }

    /** Campos obrigatórios presentes, versão conhecida e texto dentro do limite. */
    public boolean isValid() {
        return version == PROTOCOL_VERSION
                && notBlank(avisoId)
                && notBlank(authorId)
                && notBlank(authorIp)
                && text != null && !text.isBlank() && text.length() <= MAX_TEXT
                && escopo != null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
