package de.pixart.messenger.xmpp;

import de.pixart.messenger.entities.Contact;

public interface OnContactStatusChanged {
    void onContactStatusChanged(final Contact contact, final boolean online);
}
