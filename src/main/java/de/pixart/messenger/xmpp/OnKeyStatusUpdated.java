package de.pixart.messenger.xmpp;

import de.pixart.messenger.crypto.axolotl.AxolotlService;

public interface OnKeyStatusUpdated {
    void onKeyStatusUpdated(AxolotlService.FetchStatus report);
}
