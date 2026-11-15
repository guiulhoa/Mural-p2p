package pitiupi.mural.control;

/**
 * A tela implementa esta interface para ser avisada pelo controller.
 *
 * <p><b>Os métodos podem ser chamados de qualquer thread</b> (o envio de rede
 * roda fora da thread do Swing). A implementação da tela deve usar
 * {@code SwingUtilities.invokeLater} antes de mexer em componentes.</p>
 */
public interface MuralSink {

    /** Aviso novo chegou, ou mudou o estado de leitura de um já existente. */
    void recebidoAtualizado(AvisoRecebido aviso);

    /** Aviso meu: acabou de ser publicado, resultado do envio, ou nova confirmação. */
    void enviadoAtualizado(AvisoEnviado aviso);

    /** Mensagem curta de status (barra da shell). */
    void status(String text);
}
