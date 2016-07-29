package de.pixart.messenger.xmpp;

import de.pixart.messenger.entities.Account;
import de.pixart.messenger.xmpp.stanzas.MessagePacket;

public interface OnMessagePacketReceived extends PacketReceived {
	public void onMessagePacketReceived(Account account, MessagePacket packet);
}
