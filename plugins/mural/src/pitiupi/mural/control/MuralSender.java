package pitiupi.mural.control;

import pitiupi.application.messaging.TransportMode;
import pitiupi.models.Messages.Message;

import java.util.List;

/**
 * Fronteira entre o mural e a rede. A implementação real
 * ({@link CoreMuralSender}) fala com o núcleo do Pitiupi; os testes usam uma
 * versão em memória.
 */
public interface MuralSender {

    /** Pessoa online na sala. */
    record PeerInfo(String name, String ip) {
    }

    String userName();

    /** IPv4 local que o plugin anuncia dentro das mensagens. */
    String localIp();

    /** Pessoas online agora (sem a própria e sem a linha "Todos"). */
    List<PeerInfo> onlinePeers();

    /**
     * Envia uma mensagem. Pode bloquear (TCP leva até 5 s para conectar):
     * chame fora da thread da interface.
     *
     * @param peerIp {@code null} para a sala inteira, ou o IPv4 de uma pessoa
     * @return quantos envios o núcleo conseguiu fazer; 0 = nada saiu
     */
    int send(Message message, String peerIp, TransportMode mode);
}
